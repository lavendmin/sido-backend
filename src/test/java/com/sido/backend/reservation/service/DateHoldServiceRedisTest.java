package com.sido.backend.reservation.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * T3 검증 — 요청별 hold 토큰 + Lua compare-and-delete 원자 해제.
 * <p>
 * 실제 Redis(6379)에 대해 소유 토큰 기반 해제의 안전성을 단언한다.
 * hold 키 형식({@code reservation:hold:{stayId}:{date}})은 서비스 내부 상수와 동일하게 복제해 검증한다.
 */
@SpringBootTest
class DateHoldServiceRedisTest {

	private static final String HOLD_PREFIX = "reservation:hold:";
	private static final String TOKEN_A = "token-A";
	private static final String TOKEN_B = "token-B";
	private static final String TOKEN_C = "token-C";

	@Autowired
	private DateHoldService dateHoldService;
	@Autowired
	private StringRedisTemplate redisTemplate;

	private long stayId;
	private LocalDate d1;
	private LocalDate d2;
	private LocalDate d3;
	private LocalDateTime expiresAt;

	@BeforeEach
	void setUp() {
		// 실제 예약 키·다른 테스트와 겹치지 않도록 고유 stayId 사용
		stayId = 900_000_000L + (System.nanoTime() & 0xF_FFFF);
		d1 = LocalDate.of(2099, 1, 1);
		d2 = d1.plusDays(1);
		d3 = d1.plusDays(2);
		expiresAt = LocalDateTime.now().plusMinutes(10);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete(List.of(key(d1), key(d2), key(d3)));
	}

	private String key(LocalDate date) {
		return HOLD_PREFIX + stayId + ":" + date;
	}

	@Test
	@DisplayName("토큰 일치 release → 키 삭제됨")
	void tokenMatch_release_deletesKey() {
		boolean held = dateHoldService.tryHoldAll(stayId, List.of(d1), TOKEN_A, expiresAt);
		assertThat(held).isTrue();
		assertThat(redisTemplate.opsForValue().get(key(d1))).isEqualTo(TOKEN_A);

		dateHoldService.releaseDateHolds(stayId, List.of(d1), TOKEN_A);

		assertThat(redisTemplate.hasKey(key(d1))).isFalse();
	}

	@Test
	@DisplayName("토큰 불일치 release → 키 유지 (남의 hold를 지우지 않음)")
	void tokenMismatch_release_keepsKey() {
		dateHoldService.tryHoldAll(stayId, List.of(d1), TOKEN_A, expiresAt);

		dateHoldService.releaseDateHolds(stayId, List.of(d1), TOKEN_B); // 다른 토큰으로 해제 시도

		assertThat(redisTemplate.opsForValue().get(key(d1)))
			.as("소유 토큰이 다르면 삭제되지 않아야 한다")
			.isEqualTo(TOKEN_A);
	}

	@Test
	@DisplayName("키가 이미 없음 → 예외 없이 no-op")
	void missingKey_release_noError() {
		assertThatCode(() -> dateHoldService.releaseDateHolds(stayId, List.of(d1), TOKEN_A))
			.doesNotThrowAnyException();
		assertThat(redisTemplate.hasKey(key(d1))).isFalse();
	}

	@Test
	@DisplayName("null 토큰 release → 어떤 키도 삭제하지 않는 no-op")
	void nullToken_release_noop() {
		dateHoldService.tryHoldAll(stayId, List.of(d1), TOKEN_A, expiresAt);

		assertThatCode(() -> dateHoldService.releaseDateHolds(stayId, List.of(d1), null))
			.doesNotThrowAnyException();

		assertThat(redisTemplate.opsForValue().get(key(d1))).isEqualTo(TOKEN_A);
	}

	@Test
	@DisplayName("부분 acquire 실패 롤백 → 이번 토큰으로 잡은 키만 제거, 경쟁 키는 유지")
	void partialAcquire_rollback_releasesOnlyOwnKeys() {
		// d2를 다른 요청(TOKEN_C)이 먼저 선점
		redisTemplate.opsForValue().set(key(d2), TOKEN_C);

		// [d1, d2, d3]를 TOKEN_A로 시도 → d1 획득 후 d2에서 실패 → d1 롤백, d3 미시도
		boolean held = dateHoldService.tryHoldAll(stayId, List.of(d1, d2, d3), TOKEN_A, expiresAt);

		assertThat(held).isFalse();
		assertThat(redisTemplate.hasKey(key(d1))).as("롤백으로 d1 제거").isFalse();
		assertThat(redisTemplate.opsForValue().get(key(d2))).as("경쟁자 키 유지").isEqualTo(TOKEN_C);
		assertThat(redisTemplate.hasKey(key(d3))).as("d3는 시도조차 안 됨").isFalse();
	}

	@Test
	@DisplayName("ABA — 이전 토큰 A로 해제해도 재선점한 B의 키는 유지")
	void aba_previousTokenRelease_doesNotDeleteNewHold() {
		// 세대 A: 잡았다가 해제 (TTL 만료 상황 모사)
		dateHoldService.tryHoldAll(stayId, List.of(d1), TOKEN_A, expiresAt);
		dateHoldService.releaseDateHolds(stayId, List.of(d1), TOKEN_A);
		assertThat(redisTemplate.hasKey(key(d1))).isFalse();

		// 세대 B: 같은 날짜를 새 토큰으로 재선점
		dateHoldService.tryHoldAll(stayId, List.of(d1), TOKEN_B, expiresAt);

		// 뒤늦게 도착한 세대 A의 release가 B의 키를 지우면 안 된다
		dateHoldService.releaseDateHolds(stayId, List.of(d1), TOKEN_A);

		assertThat(redisTemplate.opsForValue().get(key(d1)))
			.as("이전 세대 토큰 A의 release가 새 세대 B의 hold를 삭제하면 안 된다")
			.isEqualTo(TOKEN_B);
	}
}
