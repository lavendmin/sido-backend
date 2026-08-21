package com.sido.backend.reservation.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sido.backend.common.exception.ResourceGoneException;
import com.sido.backend.member.entity.Member;
import com.sido.backend.member.entity.MemberRole;
import com.sido.backend.member.repository.MemberRepository;
import com.sido.backend.reservation.dto.ReservationConfirmRequestDTO;
import com.sido.backend.reservation.dto.ReservationCreateRequestDTO;
import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.repository.ReservationDayRepository;
import com.sido.backend.reservation.repository.ReservationRepository;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.entity.StayAvailDate;
import com.sido.backend.stay.entity.StayImage;
import com.sido.backend.stay.repository.StayAvailDateRepository;
import com.sido.backend.stay.repository.StayImageRepository;
import com.sido.backend.stay.repository.StayRepository;

/**
 * TTL 만료 경계 검증 — Redis hold·alarm 만료가 DB deadline(pendingExpiresAt)보다 먼저 오지 않는다.
 * <p>
 * 초 단위 내림 TTL이면 hold가 deadline보다 최대 1초 먼저 풀려, 그 창에서 다른 요청이 선점하면
 * 아직 확정 가능한 기존 PENDING과 이중 PENDING이 공존할 수 있었다(밀리초 올림으로 수정).
 * 짧은 deadline으로 실제 TTL 만료를 통과시키는 통합 테스트 — 실 Redis(Keyspace Notification xE)·MySQL 필요.
 */
@SpringBootTest
class PendingExpiryBoundaryTest {

	private static final String HOLD_PREFIX = "reservation:hold:";
	private static final String ALARM_PREFIX = "reservation:expire:";

	@Autowired private ReservationService reservationService;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private ReservationDayRepository reservationDayRepository;
	@Autowired private StayRepository stayRepository;
	@Autowired private StayAvailDateRepository stayAvailDateRepository;
	@Autowired private StayImageRepository stayImageRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private StringRedisTemplate redisTemplate;
	@Autowired private DateHoldService dateHoldService;
	@Autowired private com.sido.backend.reservation.scheduler.PendingExpiryProcessor pendingExpiryProcessor;

	private Long stayId;
	private Long memberId;
	private LocalDate start;
	private LocalDate end;
	private List<LocalDate> nights;
	private final List<Long> createdReservationIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		start = LocalDate.now().plusDays(70);
		end = start.plusDays(2);
		nights = start.datesUntil(end).collect(Collectors.toList());

		memberId = newMember().getId();
		stayId = newStayWithOpenDates(nights);
	}

	@AfterEach
	void tearDown() {
		List<String> keys = new ArrayList<>();
		nights.forEach(d -> keys.add(holdKey(stayId, d)));
		createdReservationIds.forEach(id -> keys.add(ALARM_PREFIX + id));
		redisTemplate.delete(keys);
	}

	// ─────────────────────────── helpers ───────────────────────────

	private Member newMember() {
		long n = System.nanoTime();
		return memberRepository.save(Member.builder()
			.loginId("bd-" + n)
			.password("pw-pw-pw-pw-pw-pw-pw-pw-1234567890")
			.name("tester")
			.role(MemberRole.ROLE_USER)
			.phone("010-" + String.format("%04d", (int) (n % 10000)) + "-"
				+ String.format("%04d", (int) ((n / 10000) % 10000)))
			.build());
	}

	private Long newStayWithOpenDates(List<LocalDate> dates) {
		Stay stay = stayRepository.save(Stay.builder()
			.isHomestay(Boolean.TRUE)
			.title("boundary stay")
			.address("boundary-" + System.nanoTime())
			.detailAddress("101")
			.capacity(5)
			.areaSize(30)
			.description("boundary test")
			.build());
		StayImage image = new StayImage();
		image.setS3Key("img/boundary.jpg");
		image.setStay(stay);
		stayImageRepository.save(image);
		for (LocalDate d : dates) {
			stayAvailDateRepository.save(StayAvailDate.builder().stay(stay).availableDate(d).build());
		}
		return stay.getId();
	}

	/** 짧은 deadline의 PENDING을 서비스 우회로 직접 구성 (서비스 create는 10분 고정이라 경계 재현 불가) */
	private Reservation newShortDeadlinePending(String token, LocalDateTime expiresAt) {
		Reservation r = new Reservation();
		r.setStay(stayRepository.findById(stayId).orElseThrow());
		r.setMember(memberRepository.findById(memberId).orElseThrow());
		r.setResrvStatus(ResrvStatus.PENDING);
		r.setStartDate(start);
		r.setEndDate(end);
		r.setPersonCnt(2);
		r.setPendingExpiresAt(expiresAt);
		r.setHoldToken(token);
		r = reservationRepository.save(r);
		createdReservationIds.add(r.getId());
		return r;
	}

	private String holdKey(Long stay, LocalDate date) {
		return HOLD_PREFIX + stay + ":" + date;
	}

	private boolean awaitTrue(BooleanSupplier condition, long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return condition.getAsBoolean();
	}

	// ─────────────────────────── tests ───────────────────────────

	@Test
	@DisplayName("TTL 올림 — hold·alarm의 Redis 만료가 DB deadline보다 빠르지 않다 (소수점 초 deadline)")
	void ttlCeiling_redisNeverExpiresBeforeDbDeadline() {
		// 소수점 초 성분이 있는 deadline — 초 단위 내림이었다면 TTL이 remaining보다 짧아진다
		LocalDateTime expiresAt = LocalDateTime.now().plus(90_500, ChronoUnit.MILLIS);
		String token = "boundary-ttl-token";

		assertThat(dateHoldService.tryHoldAll(stayId, nights, token, expiresAt)).isTrue();
		dateHoldService.setExpiryAlarm(-1L, expiresAt); // 검증용 임시 alarm 키
		createdReservationIds.add(-1L);

		for (LocalDate d : nights) {
			Long pttl = redisTemplate.getExpire(holdKey(stayId, d), TimeUnit.MILLISECONDS);
			LocalDateTime afterRead = LocalDateTime.now();
			long remaining = java.time.Duration.between(afterRead, expiresAt).toMillis();
			assertThat(pttl)
				.as("hold TTL은 읽은 시점 기준 남은 deadline 이상이어야 한다 (만료가 deadline보다 빠르면 안 됨)")
				.isGreaterThanOrEqualTo(remaining);
		}
		Long alarmPttl = redisTemplate.getExpire(ALARM_PREFIX + "-1", TimeUnit.MILLISECONDS);
		long remaining = java.time.Duration.between(LocalDateTime.now(), expiresAt).toMillis();
		assertThat(alarmPttl).isGreaterThanOrEqualTo(remaining);
	}

	@Test
	@DisplayName("만료 경계 — hold가 풀린 시점에는 기존 PENDING이 이미 만료라 confirm 410, 재선점 안전")
	void boundary_whenHoldFreed_oldPendingAlreadyUnconfirmable() {
		// 실제 TTL 만료를 통과: deadline을 약 2.1초 뒤로 (alarm은 등록하지 않아 listener 개입 배제 — 순수 경계 검증)
		LocalDateTime expiresAt = LocalDateTime.now().plus(2_100, ChronoUnit.MILLIS);
		String tokenA = "boundary-gen-A";
		Reservation oldPending = newShortDeadlinePending(tokenA, expiresAt);
		assertThat(dateHoldService.tryHoldAll(stayId, nights, tokenA, expiresAt)).isTrue();

		// hold 키가 실제 TTL로 소멸할 때까지 대기
		boolean freed = awaitTrue(
			() -> nights.stream().noneMatch(d -> Boolean.TRUE.equals(redisTemplate.hasKey(holdKey(stayId, d)))),
			10_000);
		assertThat(freed).as("hold 키가 TTL로 만료돼야 한다").isTrue();

		// 핵심 불변식: hold가 풀렸다면 DB deadline도 이미 지났다 (올림 TTL의 보장)
		assertThat(LocalDateTime.now())
			.as("hold가 풀린 시점에는 pendingExpiresAt이 지나 있어야 한다")
			.isAfterOrEqualTo(oldPending.getPendingExpiresAt());

		// 따라서 기존 PENDING은 확정 불가 — 410
		assertThatThrownBy(() -> reservationService.confirmReservation(
			memberId, oldPending.getId(), new ReservationConfirmRequestDTO(2, false)))
			.isInstanceOf(ResourceGoneException.class);

		// 다른 사용자가 같은 날짜를 재선점해 새 PENDING 생성 가능 — 이때 기존 PENDING은 이미 확정 불가 상태
		Long memberB = newMember().getId();
		Long ridB = reservationService.createReservation(memberB, stayId,
			new ReservationCreateRequestDTO(start, end, 2)).reservationId();
		createdReservationIds.add(ridB);
		String tokenB = reservationRepository.findById(ridB).orElseThrow().getHoldToken();
		assertThat(tokenB).isNotNull();

		// 만료 회수가 기존 PENDING을 CANCELLED로 수렴시켜도 B의 hold(다른 토큰)는 유지
		pendingExpiryProcessor.expireIfStillPending(oldPending.getId());
		assertThat(reservationRepository.findById(oldPending.getId()).orElseThrow().getResrvStatus())
			.isEqualTo(ResrvStatus.CANCELLED);
		for (LocalDate d : nights) {
			assertThat(redisTemplate.opsForValue().get(holdKey(stayId, d))).isEqualTo(tokenB);
		}
		assertThat(reservationRepository.findById(ridB).orElseThrow().getResrvStatus())
			.isEqualTo(ResrvStatus.PENDING);
		assertThat(reservationDayRepository.findAllReserved(stayId)).isEmpty();
	}

	@Test
	@DisplayName("alarm 만료 이벤트 — 리스너가 만료 PENDING을 CANCELLED로 수렴시킨다 (실 Keyspace Notification)")
	void alarmExpiry_listenerConvergesToCancelled() {
		LocalDateTime expiresAt = LocalDateTime.now().plus(2_100, ChronoUnit.MILLIS);
		String token = "boundary-alarm-token";
		Reservation pending = newShortDeadlinePending(token, expiresAt);
		assertThat(dateHoldService.tryHoldAll(stayId, nights, token, expiresAt)).isTrue();
		dateHoldService.setExpiryAlarm(pending.getId(), expiresAt);

		// alarm TTL 만료 → __keyevent@0__:expired → PendingExpiryListener → PendingExpiryProcessor.
		// 올림 TTL이므로 alarm은 deadline 이후에 발화 → 처리 시점에는 이미 만료라 no-op 없이 즉시 CANCELLED.
		boolean cancelled = awaitTrue(
			() -> reservationRepository.findById(pending.getId()).orElseThrow()
				.getResrvStatus() == ResrvStatus.CANCELLED,
			10_000);

		assertThat(cancelled)
			.as("alarm 만료 이벤트가 sweeper 없이 PENDING을 CANCELLED로 수렴시켜야 한다")
			.isTrue();
		Reservation after = reservationRepository.findById(pending.getId()).orElseThrow();
		assertThat(after.getHoldToken()).isNull();
		assertThat(reservationDayRepository.findAllReserved(stayId)).isEmpty();
	}
}
