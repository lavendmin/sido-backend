package com.sido.backend.reservation.service;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import com.sido.backend.reservation.scheduler.PendingExpiryProcessor;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.entity.StayAvailDate;
import com.sido.backend.stay.entity.StayImage;
import com.sido.backend.stay.repository.StayAvailDateRepository;
import com.sido.backend.stay.repository.StayImageRepository;
import com.sido.backend.stay.repository.StayRepository;

/**
 * T1·T2 검증 — 예약 수명주기 정합성 통합 테스트.
 * <p>
 * 정상 상태의 release는 AFTER_COMMIT에서 실행되므로, 커밋이 실제로 일어나도록 테스트를 @Transactional로 두지 않고
 * 서비스(각자 @Transactional)를 호출한 뒤 DB·Redis 상태를 직접 단언한다. 실 MySQL(3310)·Redis(6379) 필요.
 */
@SpringBootTest
class ReservationLifecycleIntegrationTest {

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
		start = LocalDate.now().plusDays(40);
		end = start.plusDays(2);
		nights = start.datesUntil(end).collect(Collectors.toList()); // [start, start+1]

		memberId = newMember().getId();
		stayId = newStayWithOpenDates(nights);
	}

	@AfterEach
	void tearDown() {
		// 실 Redis는 ddl-auto로 정리되지 않으므로 이 테스트가 만든 키만 삭제
		List<String> keys = new ArrayList<>();
		nights.forEach(d -> keys.add(holdKey(stayId, d)));
		createdReservationIds.forEach(id -> keys.add(ALARM_PREFIX + id));
		redisTemplate.delete(keys);
	}

	// ─────────────────────────── helpers ───────────────────────────

	private Member newMember() {
		long n = System.nanoTime();
		Member m = Member.builder()
			.loginId("lc-" + n)
			.password("pw-pw-pw-pw-pw-pw-pw-pw-1234567890")
			.name("tester")
			.role(MemberRole.ROLE_USER)
			.phone(phone(n))
			.build();
		return memberRepository.save(m);
	}

	private String phone(long n) {
		return "010-" + String.format("%04d", (int) (n % 10000)) + "-" + String.format("%04d", (int) ((n / 10000) % 10000));
	}

	private Long newStayWithOpenDates(List<LocalDate> dates) {
		Stay stay = stayRepository.save(Stay.builder()
			.isHomestay(Boolean.TRUE)
			.title("lifecycle stay")
			.address("lifecycle-" + System.nanoTime())
			.detailAddress("101")
			.capacity(5)
			.areaSize(30)
			.description("lifecycle test")
			.build());
		StayImage image = new StayImage();
		image.setS3Key("img/lifecycle.jpg");
		image.setStay(stay);
		stayImageRepository.save(image);
		for (LocalDate d : dates) {
			stayAvailDateRepository.save(StayAvailDate.builder().stay(stay).availableDate(d).build());
		}
		return stay.getId();
	}

	private Long createPending(Long member, Long stay) {
		Long id = reservationService.createReservation(member, stay,
			new ReservationCreateRequestDTO(start, end, 2)).reservationId();
		createdReservationIds.add(id);
		return id;
	}

	private String holdKey(Long stay, LocalDate date) {
		return HOLD_PREFIX + stay + ":" + date;
	}

	// ─────────────────────────── tests ───────────────────────────

	@Test
	@DisplayName("confirm 요청 계약 — startDate/endDate 없이 personCnt·isFarm만 노출")
	void confirmRequestDto_hasNoDateFields() {
		List<String> components = Stream.of(ReservationConfirmRequestDTO.class.getRecordComponents())
			.map(RecordComponent::getName)
			.toList();

		assertThat(components).containsExactlyInAnyOrder("personCnt", "isFarm");
		assertThat(components).doesNotContain("startDate", "endDate");
	}

	@Test
	@DisplayName("create → 공통 deadline·상태별 holdToken·hold 값이 토큰과 일치")
	void create_commonDeadlineAndHoldToken() {
		LocalDateTime before = LocalDateTime.now();
		Long rid = createPending(memberId, stayId);

		Reservation r = reservationRepository.findById(rid).orElseThrow();
		assertThat(r.getResrvStatus()).isEqualTo(ResrvStatus.PENDING);
		assertThat(r.getHoldToken()).as("PENDING은 holdToken 보유").isNotNull();
		assertThat(r.getPendingExpiresAt())
			.isBetween(before.plusMinutes(9), before.plusMinutes(11)); // 단일 expiresAt = now + 10분

		// 날짜 hold 값이 예약의 holdToken과 동일하고, 같은 deadline 기준 TTL을 가진다
		for (LocalDate d : nights) {
			assertThat(redisTemplate.opsForValue().get(holdKey(stayId, d))).isEqualTo(r.getHoldToken());
			Long ttl = redisTemplate.getExpire(holdKey(stayId, d));
			assertThat(ttl).isBetween(1L, 600L);
		}
		// alarm 키도 커밋 이후 같은 deadline으로 등록됨
		assertThat(redisTemplate.getExpire(ALARM_PREFIX + rid)).isBetween(1L, 600L);
	}

	@Test
	@DisplayName("confirm → 저장된 원래 기간으로 정확히 점유 투영, 체크아웃일 미포함, 커밋 후 hold 제거")
	void confirm_projectsExactOccupancy_andReleasesAfterCommit() {
		Long rid = createPending(memberId, stayId);

		reservationService.confirmReservation(memberId, rid, new ReservationConfirmRequestDTO(2, false));

		Reservation r = reservationRepository.findById(rid).orElseThrow();
		assertThat(r.getResrvStatus()).isEqualTo(ResrvStatus.RESERVED);
		assertThat(r.getHoldToken()).as("RESERVED는 holdToken 비움").isNull();
		assertThat(r.getIsFarm()).isFalse();

		// 점유는 정확히 [start, end) — 체크아웃일(end)은 포함되지 않음
		assertThat(reservationDayRepository.findAllReserved(stayId)).isEqualTo(nights);
		assertThat(reservationDayRepository.findAllReserved(stayId)).doesNotContain(end);

		// 커밋 이후 날짜 hold·alarm 키 제거됨
		for (LocalDate d : nights) {
			assertThat(redisTemplate.hasKey(holdKey(stayId, d))).isFalse();
		}
		assertThat(redisTemplate.hasKey(ALARM_PREFIX + rid)).isFalse();
	}

	@Test
	@DisplayName("confirm deadline 경과 → 410, 점유 0, 상태 PENDING 유지")
	void confirm_pastDeadline_returns410_noOccupancy() {
		Long rid = createPending(memberId, stayId);

		// deadline을 과거로 강제 (만료 스케줄러가 아직 CANCELLED로 못 바꾼 상황 모사)
		Reservation pending = reservationRepository.findById(rid).orElseThrow();
		pending.setPendingExpiresAt(LocalDateTime.now().minusMinutes(1));
		reservationRepository.save(pending);

		assertThatThrownBy(() ->
			reservationService.confirmReservation(memberId, rid, new ReservationConfirmRequestDTO(2, false)))
			.isInstanceOf(ResourceGoneException.class);

		assertThat(reservationDayRepository.findAllReserved(stayId)).isEmpty();
		assertThat(reservationRepository.findById(rid).orElseThrow().getResrvStatus())
			.isEqualTo(ResrvStatus.PENDING);
	}

	@Test
	@DisplayName("RESERVED 취소 → 점유 0, 기간 이력 유지, holdToken 비움")
	void cancel_reserved_clearsOccupancy_keepsPeriod() {
		Long rid = createPending(memberId, stayId);
		reservationService.confirmReservation(memberId, rid, new ReservationConfirmRequestDTO(2, false));

		reservationService.cancelReservation(memberId, rid);

		Reservation r = reservationRepository.findById(rid).orElseThrow();
		assertThat(r.getResrvStatus()).isEqualTo(ResrvStatus.CANCELLED);
		assertThat(r.getVisitStatus()).isNull();
		assertThat(r.getHoldToken()).isNull();
		assertThat(r.getStartDate()).isEqualTo(start); // 기간 이력 유지
		assertThat(r.getEndDate()).isEqualTo(end);
		assertThat(reservationDayRepository.findAllReserved(stayId)).isEmpty();
	}

	@Test
	@DisplayName("만료된 이전 예약 정리 → 같은 날짜를 재선점한 새 토큰 hold는 삭제하지 않음")
	void expiredPrevious_cleanup_doesNotDeleteNewGenerationHold() {
		// 세대 A: 선점 후, TTL 만료를 모사해 hold 키 삭제 + deadline 과거로
		Long ridA = createPending(memberId, stayId);
		nights.forEach(d -> redisTemplate.delete(holdKey(stayId, d)));
		Reservation a = reservationRepository.findById(ridA).orElseThrow();
		a.setPendingExpiresAt(LocalDateTime.now().minusMinutes(1));
		reservationRepository.save(a);

		// 세대 B(다른 사용자)가 같은 날짜를 새로 선점
		Long memberB = newMember().getId();
		Long ridB = createPending(memberB, stayId);
		Reservation b = reservationRepository.findById(ridB).orElseThrow();
		String tokenB = b.getHoldToken();
		assertThat(tokenB).isNotNull();

		// A의 만료 회수 실행 — A는 CANCELLED, 하지만 B의 hold(다른 토큰)는 건드리면 안 됨
		pendingExpiryProcessor.expireIfStillPending(ridA);

		assertThat(reservationRepository.findById(ridA).orElseThrow().getResrvStatus())
			.isEqualTo(ResrvStatus.CANCELLED);
		for (LocalDate d : nights) {
			assertThat(redisTemplate.opsForValue().get(holdKey(stayId, d)))
				.as("이전 예약의 만료 정리가 새 세대 B의 hold를 삭제하면 안 된다")
				.isEqualTo(tokenB);
		}
		assertThat(reservationRepository.findById(ridB).orElseThrow().getResrvStatus())
			.isEqualTo(ResrvStatus.PENDING);
	}
}
