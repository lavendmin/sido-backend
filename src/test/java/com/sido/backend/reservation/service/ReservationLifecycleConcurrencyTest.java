package com.sido.backend.reservation.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sido.backend.member.entity.Member;
import com.sido.backend.member.entity.MemberRole;
import com.sido.backend.member.repository.MemberRepository;
import com.sido.backend.reservation.dto.ReservationConfirmRequestDTO;
import com.sido.backend.reservation.dto.ReservationCreateRequestDTO;
import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.entity.ReservationDay;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.entity.VisitStatus;
import com.sido.backend.reservation.repository.ReservationDayRepository;
import com.sido.backend.reservation.repository.ReservationRepository;
import com.sido.backend.reservation.scheduler.PendingExpiryProcessor;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.entity.StayAvailDate;
import com.sido.backend.stay.entity.StayImage;
import com.sido.backend.stay.repository.StayAvailDateRepository;
import com.sido.backend.stay.repository.StayImageRepository;
import com.sido.backend.stay.repository.StayRepository;

/**
 * T2-2 검증 — 같은 예약의 수명주기 전이를 예약 행 잠금으로 직렬화한다.
 * <p>
 * 어느 경로가 먼저 잠금을 얻든 나중 경로는 최신 상태를 재검증하므로, 상태와 점유(ReservationDay) 불변식이
 * 깨지지 않는다. 정상 release가 커밋에 묶여 있음을 롤백 케이스로도 단언한다. 실 MySQL·Redis 필요.
 */
@SpringBootTest
class ReservationLifecycleConcurrencyTest {

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
	@Autowired private PendingExpiryProcessor pendingExpiryProcessor;

	private Long stayId;
	private Long memberId;
	private LocalDate start;
	private LocalDate end;
	private List<LocalDate> nights;
	private final List<Long> createdReservationIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		start = LocalDate.now().plusDays(50);
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
			.loginId("cc-" + n)
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
			.title("concurrency stay")
			.address("concurrency-" + System.nanoTime())
			.detailAddress("101")
			.capacity(5)
			.areaSize(30)
			.description("concurrency test")
			.build());
		StayImage image = new StayImage();
		image.setS3Key("img/concurrency.jpg");
		image.setStay(stay);
		stayImageRepository.save(image);
		for (LocalDate d : dates) {
			stayAvailDateRepository.save(StayAvailDate.builder().stay(stay).availableDate(d).build());
		}
		return stay.getId();
	}

	private Long createPending() {
		Long id = reservationService.createReservation(memberId, stayId,
			new ReservationCreateRequestDTO(start, end, 2)).reservationId();
		createdReservationIds.add(id);
		return id;
	}

	private String holdKey(Long stay, LocalDate date) {
		return HOLD_PREFIX + stay + ":" + date;
	}

	private ResrvStatus statusOf(Long rid) {
		return reservationRepository.findById(rid).orElseThrow().getResrvStatus();
	}

	// ─────────────────────────── tests ───────────────────────────

	@Test
	@DisplayName("confirm이 먼저 확정 → 이후 만료 회수는 최신 상태(RESERVED)를 보고 no-op")
	void confirmThenExpiry_expiryNoops_staysReserved() {
		Long rid = createPending(); // deadline 미래
		reservationService.confirmReservation(memberId, rid, new ReservationConfirmRequestDTO(2, false));

		pendingExpiryProcessor.expireIfStillPending(rid); // 잠금 후 재검증 → PENDING 아님 → skip

		assertThat(statusOf(rid)).isEqualTo(ResrvStatus.RESERVED);
		assertThat(reservationDayRepository.findAllReserved(stayId)).isEqualTo(nights);
	}

	@Test
	@DisplayName("만료 회수가 먼저 취소 → 이후 confirm은 최신 상태(CANCELLED)를 보고 거부, 점유 0")
	void expiryThenConfirm_confirmRejected_noOccupancy() {
		Long rid = createPending();
		Reservation r = reservationRepository.findById(rid).orElseThrow();
		r.setPendingExpiresAt(LocalDateTime.now().minusMinutes(1)); // 만료 상황
		reservationRepository.save(r);

		pendingExpiryProcessor.expireIfStillPending(rid); // CANCELLED

		assertThatThrownBy(() ->
			reservationService.confirmReservation(memberId, rid, new ReservationConfirmRequestDTO(2, false)))
			.isInstanceOf(RuntimeException.class);

		assertThat(statusOf(rid)).isEqualTo(ResrvStatus.CANCELLED);
		assertThat(reservationDayRepository.findAllReserved(stayId)).isEmpty();
	}

	@Test
	@DisplayName("confirm 대 cancel 동시 실행 — 행 잠금으로 직렬화, 최종 CANCELLED·점유 0")
	void confirmVsCancel_concurrent_finalCancelledNoOccupancy() throws InterruptedException {
		Long rid = createPending();
		CountDownLatch gate = new CountDownLatch(1);
		AtomicReference<Throwable> confirmErr = new AtomicReference<>();
		AtomicReference<Throwable> cancelErr = new AtomicReference<>();

		Thread confirmThread = new Thread(() -> {
			await(gate);
			try {
				reservationService.confirmReservation(memberId, rid, new ReservationConfirmRequestDTO(2, false));
			} catch (Throwable t) {
				confirmErr.set(t); // cancel이 먼저면 ConflictException 등 예상됨
			}
		}, "confirm");
		Thread cancelThread = new Thread(() -> {
			await(gate);
			try {
				reservationService.cancelReservation(memberId, rid);
			} catch (Throwable t) {
				cancelErr.set(t);
			}
		}, "cancel");

		confirmThread.start();
		cancelThread.start();
		gate.countDown();
		confirmThread.join(20_000);
		cancelThread.join(20_000);

		// 두 경로 어느 순서로 직렬화돼도 cancel은 항상 취소로 수렴 → 최종 CANCELLED, 점유 0
		assertThat(statusOf(rid)).isEqualTo(ResrvStatus.CANCELLED);
		assertThat(reservationDayRepository.findAllReserved(stayId)).isEmpty();
		assertThat(cancelErr.get()).as("cancel은 성공해야 한다").isNull();
	}

	@Test
	@DisplayName("confirm 롤백 → hold·alarm 유지, 점유·상태 변경 없음(PENDING)")
	void confirmRollback_keepsHold_noOccupancy_staysPending() {
		Long rid = createPending(); // A: 두 날짜 hold 보유

		// 다른 예약 B가 night0을 점유 → A confirm의 가용성 검증이 실패해 트랜잭션 롤백
		occupyNightWithOtherReservation(nights.get(0));

		assertThatThrownBy(() ->
			reservationService.confirmReservation(memberId, rid, new ReservationConfirmRequestDTO(2, false)))
			.isInstanceOf(RuntimeException.class);

		// 롤백이므로 AFTER_COMMIT release 미발화 → A의 hold·alarm 유지
		Reservation a = reservationRepository.findById(rid).orElseThrow();
		String tokenA = a.getHoldToken();
		assertThat(statusOf(rid)).isEqualTo(ResrvStatus.PENDING);
		assertThat(tokenA).isNotNull();
		for (LocalDate d : nights) {
			assertThat(redisTemplate.opsForValue().get(holdKey(stayId, d))).isEqualTo(tokenA);
		}
		assertThat(redisTemplate.hasKey(ALARM_PREFIX + rid)).isTrue();
		// A는 점유를 만들지 않았다 — stay 점유는 B의 night0 하나뿐
		assertThat(reservationDayRepository.findAllReserved(stayId)).containsExactly(nights.get(0));
	}

	private void occupyNightWithOtherReservation(LocalDate night) {
		Stay stay = stayRepository.findById(stayId).orElseThrow();
		Member member = memberRepository.findById(memberId).orElseThrow();

		Reservation other = new Reservation();
		other.setStay(stay);
		other.setMember(member);
		other.setStartDate(night);
		other.setEndDate(night.plusDays(1));
		other.setPersonCnt(2);
		other.setResrvStatus(ResrvStatus.RESERVED);
		other.setVisitStatus(VisitStatus.UPCOMING);
		other.setIsFarm(Boolean.FALSE);
		other.setReservedAt(LocalDateTime.now()); // RESERVED: holdToken null
		other = reservationRepository.save(other);

		reservationDayRepository.save(ReservationDay.builder()
			.date(night).reservation(other).stay(stay).build());
	}

	private void await(CountDownLatch gate) {
		try {
			gate.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
