package com.sido.backend.reservation.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DateHoldService {

	private static final String HOLD_PREFIX = "reservation:hold:";
	private static final String ALARM_PREFIX = "reservation:expire:";
	static final long HOLD_MINUTES = 10;

	private final StringRedisTemplate redisTemplate;

	// 모든 날짜 SETNX 선점 시도. 하나라도 실패하면 성공한 것들 롤백 후 false 반환
	public boolean tryHoldAll(Long stayId, List<LocalDate> dates, Long memberId) {
		List<LocalDate> acquired = new ArrayList<>();

		for (LocalDate date : dates) {
			String key = buildHoldKey(stayId, date);
			Boolean success = redisTemplate.opsForValue()
				.setIfAbsent(key, memberId.toString(), HOLD_MINUTES, TimeUnit.MINUTES);

			if (!Boolean.TRUE.equals(success)) {
				releaseDateHolds(stayId, acquired, memberId);
				return false;
			}
			acquired.add(date);
		}
		return true;
	}

	// 예약 저장 후 reservationId 확보된 시점에 호출 — TTL 만료 시 Keyspace Notification 발생
	public void setExpiryAlarm(Long reservationId) {
		redisTemplate.opsForValue()
			.set(buildAlarmKey(reservationId), "1", HOLD_MINUTES, TimeUnit.MINUTES);
	}

	// confirm 완료 / 취소 시 호출 — 날짜 선점 키 + 알람 키 모두 해제
	public void releaseAll(Long stayId, List<LocalDate> dates, Long reservationId, Long memberId) {
		releaseDateHolds(stayId, dates, memberId);
		redisTemplate.delete(buildAlarmKey(reservationId));
	}

	// 소유자(memberId)가 일치하는 키만 삭제 — TTL 만료 후 다른 사용자가 재선점한 키를 지우는 것 방지
	// GET-비교-DEL 사이 원자성은 미보장. confirm 단계 비관적 락이 최종 안전망이므로 허용 (원자성 필요 시 Lua 스크립트)
	public void releaseDateHolds(Long stayId, List<LocalDate> dates, Long memberId) {
		for (LocalDate date : dates) {
			String key = buildHoldKey(stayId, date);
			String owner = redisTemplate.opsForValue().get(key);
			if (memberId.toString().equals(owner)) {
				redisTemplate.delete(key);
			}
		}
	}

	private String buildHoldKey(Long stayId, LocalDate date) {
		return HOLD_PREFIX + stayId + ":" + date;
	}

	private String buildAlarmKey(Long reservationId) {
		return ALARM_PREFIX + reservationId;
	}
}
