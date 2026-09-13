package com.sido.backend.reservation.concurrency;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sido.backend.member.entity.HostMember;
import com.sido.backend.member.entity.Member;
import com.sido.backend.member.entity.MemberRole;
import com.sido.backend.member.repository.HostMemberRepository;
import com.sido.backend.member.repository.MemberRepository;
import com.sido.backend.reservation.dto.ReservationConfirmRequestDTO;
import com.sido.backend.reservation.dto.ReservationCreateRequestDTO;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.repository.ReservationDayRepository;
import com.sido.backend.reservation.repository.ReservationRepository;
import com.sido.backend.reservation.service.ReservationService;
import com.sido.backend.stay.dto.StayDeleteDTO;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.entity.StayAvailDate;
import com.sido.backend.stay.entity.StayImage;
import com.sido.backend.stay.repository.StayAvailDateRepository;
import com.sido.backend.stay.repository.StayImageRepository;
import com.sido.backend.stay.repository.StayRepository;
import com.sido.backend.stay.service.StayService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 예약 확정(고객)과 예약 가능일 변경·숙소 비활성화(운영자)의 경합 재현·계약 검증.
 * <p>
 * 두 운영자 경로는 "예약됐나?"를 <b>잠금 없는 SELECT</b>로 검증한 뒤 삭제/비활성화한다. 이 검증과 변경 사이에
 * 확정이 끼어들면 확정 점유일이 예약 가능일에서 사라질 수 있다(계획 §1의 가설). 검증 직후 지점을
 * {@link MockitoSpyBean} 래치로 잡아, 그 창에서 확정을 완주시켜 경합을 결정론적으로 만든다.
 * <p>
 * 검증하는 서비스 계약(§2):
 * <ul>
 *   <li>충돌하는 확정과 날짜 닫기가 <b>모두 성공</b>하지 않는다(공통 보호 구간을 먼저 확보한 쪽이 이긴다).</li>
 *   <li>확정된 점유일({@code ReservationDay})은 반드시 예약 가능일({@code StayAvailDate})로 남아 있다.</li>
 *   <li>확정된 예정 예약이 있으면 숙소 비활성화는 거절된다(결정 2: 기존 정책 유지).</li>
 * </ul>
 * 보호 규칙 도입 전(baseline)에는 이 계약이 깨져 RED가 정상이다. 실 MySQL·Redis 필요.
 * 래치는 잠금으로 확정이 막혀 신호가 오지 않는 경우에도 타임아웃으로 재개해 테스트 교착을 막는다.
 */
@SpringBootTest
class ReservationVsDateChangeConcurrencyTest {

	private static final Logger log = LoggerFactory.getLogger(ReservationVsDateChangeConcurrencyTest.class);
	private static final String HOLD_PREFIX = "reservation:hold:";
	private static final String ALARM_PREFIX = "reservation:expire:";
	private static final long PAUSE_TIMEOUT_SEC = 10;  // 확정이 잠금으로 막히면 이 시간 뒤 운영자 경로가 재개(교착 방지)
	private static final long JOIN_TIMEOUT_MS = 45_000;

	@Autowired private ReservationService reservationService;
	@Autowired private StayService stayService;
	@Autowired private ReservationRepository reservationRepository;
	@Autowired private ReservationDayRepository reservationDayRepository;
	@Autowired private StayRepository stayRepository;
	@Autowired private StayAvailDateRepository stayAvailDateRepository;
	@Autowired private StayImageRepository stayImageRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private HostMemberRepository hostMemberRepository;
	@Autowired private StringRedisTemplate redisTemplate;
	@PersistenceContext private EntityManager entityManager;

	// 운영자 경로의 첫 문장(StayRepository.findById)을 seam 으로 가로채 트랜잭션 스냅샷을 고정시킨 뒤 확정을 끼워 넣는다.
	// StayRepository 는 confirmReservation 이 전혀 호출하지 않으므로(생성 경로에서만 사용), 경합 구간에는
	// 운영자 스레드만 이 spy 를 건드린다 → Mockito 동시 호출 불안정이 원천 제거된다.
	// confirm 이 쓰는 ReservationRepository·ReservationDayRepository·StayAvailDateRepository 를 spy 로 감싸면
	// 확정 스레드와 동시 호출돼 MockitoException·데드락이 발생했다.
	@MockitoSpyBean private StayRepository spiedStayRepository;

	private Long stayId;
	private Long memberId;
	private Long hostId;
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
		hostId = newHost().getId();
		stayId = newStayWithOpenDates(hostId, nights);

		warmUpConcurrentPaths();
	}

	/**
	 * 경합 구간 전에 confirm·updateOpenDates·deleteStay 경로의 람다 콜사이트·클래스 초기화를 미리 링크한다.
	 * 첫 링크가 경합 중에 일어나면, 운영자 스레드가 Mockito answer 안에서 클래스 초기화 락을 쥔 채 멈추고
	 * confirm 이 그 락을 기다리는 클래스 초기화 데드락이 발생한다. 단일 스레드 warmup 으로 이를 제거한다.
	 */
	private void warmUpConcurrentPaths() {
		HostMember warmHost = newHost();
		List<LocalDate> warmNights = start.plusDays(200).datesUntil(start.plusDays(202)).collect(Collectors.toList());
		Long warmStay = newStayWithOpenDates(warmHost.getId(), warmNights);
		Long warmMember = newMember().getId();

		Long warmRid = reservationService.createReservation(warmMember, warmStay,
			new ReservationCreateRequestDTO(warmNights.get(0), warmNights.get(warmNights.size() - 1).plusDays(1), 2))
			.reservationId();
		createdReservationIds.add(warmRid);
		reservationService.confirmReservation(warmMember, warmRid, new ReservationConfirmRequestDTO(2, false));

		// 예약 없는 숙소에서 운영자 경로를 링크한다(닫을 날짜가 있어야 findReservedDatesIn 까지 링크됨)
		Long updStay = newStayWithOpenDates(newHost().getId(), warmNights);
		stayService.updateOpenDates(updStay, List.of());        // updateOpenDates + findReservedDatesIn 경로 링크

		Long delHostId = newHost().getId();
		Long delStay = newStayWithOpenDates(delHostId, warmNights);
		stayService.deleteStay(delHostId, delStay);             // deleteStay + existsUpcomingByStay 경로 링크
	}

	@AfterEach
	void tearDown() {
		List<String> keys = new ArrayList<>();
		nights.forEach(d -> keys.add(holdKey(stayId, d)));
		createdReservationIds.forEach(id -> keys.add(ALARM_PREFIX + id));
		redisTemplate.delete(keys);
	}

	// ─────────────────────────── tests ───────────────────────────

	@Test
	@DisplayName("[확정 ↔ 날짜 닫기] 운영자가 확정 중인 날짜를 닫아도, 확정 점유일이 예약 가능일에서 사라지지 않는다")
	void confirmVsCloseDates_confirmedOccupancyNeverLosesAvailDate() throws InterruptedException {
		Long rid = createPending();

		CountDownLatch reachedCheck = new CountDownLatch(1);
		CountDownLatch confirmDone = new CountDownLatch(1);
		AtomicBoolean pausedOnce = new AtomicBoolean(false);

		// updateOpenDates의 첫 읽기(findById) 직후에 멈춘다. 여기서 스냅샷이 고정되고, 이후
		// "예약된 날짜 삭제 불가" 검증(findReservedDatesIn)이 그 스냅샷으로 확정 점유를 놓치는 것이 근본 원인이다.
		doAnswer(invocation -> pauseThenFind(invocation, reachedCheck, confirmDone, pausedOnce, "updateOpenDates"))
			.when(spiedStayRepository).findById(stayId);

		AtomicBoolean updateSucceeded = new AtomicBoolean(false);
		AtomicBoolean confirmSucceeded = new AtomicBoolean(false);
		AtomicReference<Throwable> updateErr = new AtomicReference<>();
		AtomicReference<Throwable> confirmErr = new AtomicReference<>();

		// 운영자: 모든 오픈 날짜를 닫는다(확정 중인 날짜 포함)
		Thread updateThread = new Thread(() -> {
			try {
				stayService.updateOpenDates(stayId, List.of());
				updateSucceeded.set(true);
			} catch (Throwable t) {
				updateErr.set(t);
			}
		}, "update-open-dates");

		// 고객: 운영자의 검증이 통과해 멈춘 창에서 확정을 시도한다
		Thread confirmThread = new Thread(() -> {
			awaitGate(reachedCheck);
			try {
				reservationService.confirmReservation(memberId, rid, new ReservationConfirmRequestDTO(2, false));
				confirmSucceeded.set(true);
			} catch (Throwable t) {
				confirmErr.set(t);
			} finally {
				confirmDone.countDown();
			}
		}, "confirm");

		updateThread.start();
		confirmThread.start();
		updateThread.join(JOIN_TIMEOUT_MS);
		confirmThread.join(JOIN_TIMEOUT_MS);

		assertThat(updateThread.isAlive()).as("updateOpenDates 스레드가 제한시간 안에 종료돼야 한다").isFalse();
		assertThat(confirmThread.isAlive()).as("confirm 스레드가 제한시간 안에 종료돼야 한다").isFalse();

		List<LocalDate> availDates = stayAvailDateRepository.findAllDatesByStayId(stayId);
		List<LocalDate> reservedDates = reservationDayRepository.findAllReserved(stayId);
		boolean confirmed = statusOf(rid) == ResrvStatus.RESERVED;

		log.info("결과: updateSucceeded={}, confirmSucceeded={}, status={}, avail={}, reserved={}",
			updateSucceeded.get(), confirmSucceeded.get(), statusOf(rid), availDates, reservedDates);

		// §2: 충돌하는 두 변경이 모두 성공하면 안 된다
		assertThat(updateSucceeded.get() && confirmSucceeded.get())
			.as("날짜 닫기와 확정이 모두 성공하면 확정 점유일이 예약 가능일에서 사라진다(§2 위반)")
			.isFalse();

		// 핵심 불변식: 확정된 점유일은 반드시 예약 가능일로 남아 있어야 한다
		assertThat(availDates)
			.as("확정된 점유일(ReservationDay)은 모두 예약 가능일(StayAvailDate)에 남아 있어야 한다")
			.containsAll(reservedDates);

		// 확정됐다면 그 기간 전체가 점유·예약가능일에 정합적으로 존재해야 한다
		if (confirmed) {
			assertThat(reservedDates).as("확정 성공 시 예약 기간 전체가 점유돼야 한다").containsAll(nights);
			assertThat(availDates).as("확정 성공 시 점유일은 예약 가능일로 유지돼야 한다").containsAll(nights);
		}
	}

	@Test
	@DisplayName("[확정 ↔ 숙소 비활성화] 확정이 완료되면, 그 예약이 있는 숙소는 비활성화되지 않는다(결정 2)")
	void confirmVsDeactivate_confirmedReservationBlocksDeactivation() throws InterruptedException {
		Long rid = createPending();

		CountDownLatch reachedCheck = new CountDownLatch(1);
		CountDownLatch confirmDone = new CountDownLatch(1);
		AtomicBoolean pausedOnce = new AtomicBoolean(false);

		// deleteStay의 첫 읽기(findById) 직후에 멈춘다. 여기서 스냅샷이 고정되고, 이후
		// "예정 예약 있음" 검증(existsUpcomingByStay)이 그 스냅샷으로 확정된 예정 예약을 놓치는 것이 근본 원인이다.
		doAnswer(invocation -> pauseThenFind(invocation, reachedCheck, confirmDone, pausedOnce, "deleteStay"))
			.when(spiedStayRepository).findById(stayId);

		AtomicReference<StayDeleteDTO> deleteResult = new AtomicReference<>();
		AtomicBoolean confirmSucceeded = new AtomicBoolean(false);
		AtomicReference<Throwable> deleteErr = new AtomicReference<>();
		AtomicReference<Throwable> confirmErr = new AtomicReference<>();

		Thread deleteThread = new Thread(() -> {
			try {
				deleteResult.set(stayService.deleteStay(hostId, stayId));
			} catch (Throwable t) {
				deleteErr.set(t);
			}
		}, "delete-stay");

		Thread confirmThread = new Thread(() -> {
			awaitGate(reachedCheck);
			try {
				reservationService.confirmReservation(memberId, rid, new ReservationConfirmRequestDTO(2, false));
				confirmSucceeded.set(true);
			} catch (Throwable t) {
				confirmErr.set(t);
			} finally {
				confirmDone.countDown();
			}
		}, "confirm");

		deleteThread.start();
		confirmThread.start();
		deleteThread.join(JOIN_TIMEOUT_MS);
		confirmThread.join(JOIN_TIMEOUT_MS);

		dumpIfAlive(deleteThread, confirmThread);
		assertThat(deleteThread.isAlive()).as("deleteStay 스레드가 제한시간 안에 종료돼야 한다").isFalse();
		assertThat(confirmThread.isAlive()).as("confirm 스레드가 제한시간 안에 종료돼야 한다").isFalse();

		Stay reloaded = stayRepository.findById(stayId).orElseThrow();
		List<LocalDate> availDates = stayAvailDateRepository.findAllDatesByStayId(stayId);
		List<LocalDate> reservedDates = reservationDayRepository.findAllReserved(stayId);
		boolean confirmed = statusOf(rid) == ResrvStatus.RESERVED;
		boolean deactivated = !Boolean.TRUE.equals(reloaded.getIsActive());

		log.info("결과: deleteResult={}, confirmSucceeded={}, active={}, avail={}, reserved={}",
			deleteResult.get(), confirmSucceeded.get(), reloaded.getIsActive(), availDates, reservedDates);
		log.info("에러: deleteErr={}, confirmErr={}",
			String.valueOf(deleteErr.get()), String.valueOf(confirmErr.get()));

		// §2 / 결정 2: 확정 성공과 숙소 비활성화가 모두 성공하면 안 된다
		boolean deletedReported = deleteResult.get() != null && deleteResult.get().deleted();
		assertThat(confirmSucceeded.get() && deletedReported)
			.as("확정 성공과 숙소 비활성화가 동시에 성공하면, 확정 예약이 비활성 숙소에 남는다(§2 위반)")
			.isFalse();

		// 확정됐다면 숙소는 활성 상태로 유지되고, 점유일은 예약 가능일에 남아 있어야 한다
		if (confirmed) {
			assertThat(deactivated).as("확정된 예정 예약이 있으면 숙소가 비활성화되면 안 된다").isFalse();
			assertThat(availDates).as("확정 점유일은 예약 가능일에 남아 있어야 한다").containsAll(reservedDates);
		}
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

	private HostMember newHost() {
		long n = System.nanoTime();
		return hostMemberRepository.save(HostMember.builder()
			.loginId("host-" + n)
			.password("pw-pw-pw-pw-pw-pw-pw-pw-1234567890")
			.name("host")
			.role(MemberRole.ROLE_ADMIN)
			.villageName("테스트마을")
			.region("테스트지역")
			.phone("010-" + String.format("%04d", (int) (n % 10000)) + "-"
				+ String.format("%04d", (int) ((n / 10000) % 10000)))
			.build());
	}

	private Long newStayWithOpenDates(Long hostId, List<LocalDate> dates) {
		HostMember host = hostMemberRepository.findById(hostId).orElseThrow();
		Stay stay = stayRepository.save(Stay.builder()
			.isHomestay(Boolean.TRUE)
			.title("date-change concurrency stay")
			.address("date-change-" + System.nanoTime())
			.detailAddress("101")
			.capacity(5)
			.areaSize(30)
			.description("concurrency test")
			.host(host)
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

	// 운영자 경로의 첫 읽기(findById)를 실제 조회한 뒤, 그 직후에 한 번만 멈춰 확정이 끼어들 창을 연다.
	// Spring Data 내장 findById 는 spy 의 callRealMethod 가 MockitoException 을 던지므로, 운영자 트랜잭션에
	// 바인딩된 EntityManager.find 로 실제 엔티티를 조회한다(이 조회가 트랜잭션 스냅샷을 고정한다).
	// confirm 이 잠금으로 막혀 신호가 오지 않아도 PAUSE_TIMEOUT_SEC 뒤 재개해 교착을 막는다.
	private Object pauseThenFind(org.mockito.invocation.InvocationOnMock invocation,
		CountDownLatch reachedCheck, CountDownLatch confirmDone, AtomicBoolean pausedOnce, String who) throws Throwable {
		Long id = invocation.getArgument(0);
		Stay real = entityManager.find(Stay.class, id);
		if (pausedOnce.compareAndSet(false, true)) {
			reachedCheck.countDown();
			boolean signaled = confirmDone.await(PAUSE_TIMEOUT_SEC, TimeUnit.SECONDS);
			log.info("{} 첫 읽기 후 재개 (confirm 완료 신호={})", who, signaled);
		}
		return Optional.ofNullable(real);
	}

	private void dumpIfAlive(Thread... threads) {
		for (Thread t : threads) {
			if (t != null && t.isAlive()) {
				StringBuilder sb = new StringBuilder("[HANG] 스레드 '" + t.getName() + "' 스택:\n");
				for (StackTraceElement e : t.getStackTrace()) {
					sb.append("\tat ").append(e).append('\n');
				}
				log.error(sb.toString());
			}
		}
	}

	private void awaitGate(CountDownLatch gate) {
		try {
			if (!gate.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				log.warn("게이트 대기 타임아웃 — 운영자 경로가 검증 지점에 도달하지 못했다");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
