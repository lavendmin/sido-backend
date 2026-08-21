package com.sido.backend.reservation.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DateHoldService {

	private static final String HOLD_PREFIX = "reservation:hold:";
	private static final String ALARM_PREFIX = "reservation:expire:";
	static final long HOLD_MINUTES = 10;

	// 소유 토큰이 일치할 때만 삭제하는 compare-and-delete 스크립트.
	// GET → 비교 → DEL을 Redis 서버에서 원자적으로 실행해, 비교와 삭제 사이 TTL 만료·재선점이 끼어들 여지를 없앤다.
	private static final RedisScript<Long> COMPARE_AND_DELETE = RedisScript.of(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class);

	private final StringRedisTemplate redisTemplate;

	// 모든 날짜 SETNX 선점 시도. 값은 요청별 고유 holdToken. 하나라도 실패하면 이번 토큰으로 잡은 것들만 롤백 후 false 반환
	// 모든 키는 생성 시 한 번 정한 expiresAt과 같은 deadline으로 만료한다 (DB pendingExpiresAt과 동일 기준).
	public boolean tryHoldAll(Long stayId, List<LocalDate> dates, String holdToken, LocalDateTime expiresAt) {
		List<LocalDate> acquired = new ArrayList<>();

		for (LocalDate date : dates) {
			String key = buildHoldKey(stayId, date);
			Boolean success = redisTemplate.opsForValue()
				.setIfAbsent(key, holdToken, remainingMillis(expiresAt), TimeUnit.MILLISECONDS);

			if (!Boolean.TRUE.equals(success)) {
				releaseDateHolds(stayId, acquired, holdToken);
				return false;
			}
			acquired.add(date);
		}
		return true;
	}

	// 예약 저장 후 reservationId 확보된 시점에 호출 — TTL 만료 시 Keyspace Notification 발생.
	// 날짜 hold·DB pendingExpiresAt과 동일한 expiresAt deadline을 사용한다.
	public void setExpiryAlarm(Long reservationId, LocalDateTime expiresAt) {
		redisTemplate.opsForValue()
			.set(buildAlarmKey(reservationId), "1", remainingMillis(expiresAt), TimeUnit.MILLISECONDS);
	}

	// confirm 완료 / 취소 시 호출 — 날짜 선점 키 + 알람 키 모두 해제
	public void releaseAll(Long stayId, List<LocalDate> dates, Long reservationId, String holdToken) {
		releaseDateHolds(stayId, dates, holdToken);
		redisTemplate.delete(buildAlarmKey(reservationId));
	}

	// 소유 토큰이 일치하는 키만 Lua compare-and-delete로 삭제.
	// TTL 만료 후 다른 요청이 재선점한 키(다른 토큰)나 같은 사용자가 새로 잡은 키는 지우지 않는다.
	// null 토큰은 어떤 키도 삭제하지 않는 no-op — 토큰 없는 상태 전이 경로가 남의 키를 지우지 못하게 하는 계약.
	public void releaseDateHolds(Long stayId, List<LocalDate> dates, String holdToken) {
		if (holdToken == null) {
			return;
		}
		for (LocalDate date : dates) {
			String key = buildHoldKey(stayId, date);
			redisTemplate.execute(COMPARE_AND_DELETE, List.of(key), holdToken);
		}
	}

	// Redis 만료가 DB deadline(pendingExpiresAt)보다 절대 먼저 오지 않도록 밀리초 올림으로 계산한다.
	// 초 단위 내림(getSeconds)이면 hold·alarm이 deadline보다 최대 1초 먼저 만료될 수 있고,
	// 그 창에서 hold가 풀린 날짜를 다른 요청이 선점하면 아직 deadline 전이라 확정 가능한 기존 PENDING과
	// 이중 PENDING이 공존한다. 올림이면 Redis 만료는 deadline과 같거나 그 뒤 — hold가 풀린 시점에는
	// 기존 PENDING이 이미 만료돼 confirm이 410으로 거부되므로 정합성이 유지된다.
	private long remainingMillis(LocalDateTime expiresAt) {
		long nanos = Duration.between(LocalDateTime.now(), expiresAt).toNanos();
		long millisCeil = Math.floorDiv(nanos + 999_999, 1_000_000); // 올림
		return Math.max(1, millisCeil); // 이미 지난 deadline도 최소 1ms를 줘 SETNX가 TTL 없는 영구 키를 만들지 않게 한다
	}

	private String buildHoldKey(Long stayId, LocalDate date) {
		return HOLD_PREFIX + stayId + ":" + date;
	}

	private String buildAlarmKey(Long reservationId) {
		return ALARM_PREFIX + reservationId;
	}
}
