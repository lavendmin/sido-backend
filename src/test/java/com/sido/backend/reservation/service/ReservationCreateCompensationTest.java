package com.sido.backend.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sido.backend.member.entity.Member;
import com.sido.backend.member.entity.MemberRole;
import com.sido.backend.member.repository.MemberRepository;
import com.sido.backend.reservation.dto.ReservationCreateRequestDTO;
import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.repository.ReservationRepository;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.entity.StayAvailDate;
import com.sido.backend.stay.entity.StayImage;
import com.sido.backend.stay.repository.StayAvailDateRepository;
import com.sido.backend.stay.repository.StayImageRepository;
import com.sido.backend.stay.repository.StayRepository;

/**
 * 결정 D 검증 — create 영속화 실패에 따른 트랜잭션 롤백 보상.
 * <p>
 * 증명 범위를 정확히: 이 테스트가 재현하는 것은 {@code saveAndFlush}의 **영속화 시점 예외 → 롤백**이며,
 * 그때 {@code afterCompletion(STATUS_ROLLED_BACK)} 콜백이 이번 토큰의 hold를 정리하고 alarm을 만들지
 * 않음을 검증한다. 서비스 반환 이후 트랜잭션 매니저의 **실제 commit 단계 실패**를 직접 재현한 것은 아니다 —
 * 같은 afterCompletion(ROLLED_BACK) 경로로 처리되는 구조이나, 그 부분의 근거는 구조 논증까지다.
 */
@SpringBootTest
class ReservationCreateCompensationTest {

	private static final String HOLD_PREFIX = "reservation:hold:";
	private static final String ALARM_PREFIX = "reservation:expire:";

	@Autowired private ReservationService reservationService;
	@Autowired private StayRepository stayRepository;
	@Autowired private StayAvailDateRepository stayAvailDateRepository;
	@Autowired private StayImageRepository stayImageRepository;
	@Autowired private MemberRepository memberRepository;
	@Autowired private StringRedisTemplate redisTemplate;

	@MockitoBean private ReservationRepository reservationRepository; // 커밋 실패 주입용

	private Long stayId;
	private Long memberId;
	private LocalDate start;
	private LocalDate end;
	private List<LocalDate> nights;

	@BeforeEach
	void setUp() {
		start = LocalDate.now().plusDays(60);
		end = start.plusDays(2);
		nights = start.datesUntil(end).collect(Collectors.toList());

		long n = System.nanoTime();
		Member member = memberRepository.save(Member.builder()
			.loginId("cf-" + n)
			.password("pw-pw-pw-pw-pw-pw-pw-pw-1234567890")
			.name("tester")
			.role(MemberRole.ROLE_USER)
			.phone("010-" + String.format("%04d", (int) (n % 10000)) + "-"
				+ String.format("%04d", (int) ((n / 10000) % 10000)))
			.build());
		memberId = member.getId();

		Stay stay = stayRepository.save(Stay.builder()
			.isHomestay(Boolean.TRUE)
			.title("compensation stay")
			.address("compensation-" + n)
			.detailAddress("101")
			.capacity(5)
			.areaSize(30)
			.description("compensation test")
			.build());
		StayImage image = new StayImage();
		image.setS3Key("img/compensation.jpg");
		image.setStay(stay);
		stayImageRepository.save(image);
		for (LocalDate d : nights) {
			stayAvailDateRepository.save(StayAvailDate.builder().stay(stay).availableDate(d).build());
		}
		stayId = stay.getId();
	}

	@AfterEach
	void tearDown() {
		List<String> keys = new ArrayList<>();
		nights.forEach(d -> keys.add(HOLD_PREFIX + stayId + ":" + d));
		redisTemplate.delete(keys);
	}

	@Test
	@DisplayName("create 영속화 실패(saveAndFlush 강제 예외) → 롤백 보상: 이번 토큰의 hold 정리, alarm 미생성")
	void createPersistFailure_rollbackCompensation_releasesHold_noAlarm() {
		// 영속화 시점 실패 주입 (실제 commit 단계 실패의 직접 재현은 아님 — 클래스 javadoc 참조)
		given(reservationRepository.saveAndFlush(any(Reservation.class)))
			.willThrow(new DataIntegrityViolationException("forced persist failure"));

		java.util.Set<String> alarmKeysBefore = redisTemplate.keys(ALARM_PREFIX + "*");

		assertThatThrownBy(() -> reservationService.createReservation(memberId, stayId,
			new ReservationCreateRequestDTO(start, end, 2)))
			.isInstanceOf(DataIntegrityViolationException.class);

		// 롤백 콜백이 이번 요청 토큰으로 hold를 정리했어야 한다
		for (LocalDate d : nights) {
			assertThat(redisTemplate.hasKey(HOLD_PREFIX + stayId + ":" + d))
				.as("롤백 시 이번 요청의 hold가 남아있으면 안 된다")
				.isFalse();
		}
		// alarm 직접 단언: afterCommit이 실행되지 않았으므로 새 alarm 키가 하나도 생기지 않아야 한다
		java.util.Set<String> alarmKeysAfter = redisTemplate.keys(ALARM_PREFIX + "*");
		assertThat(alarmKeysAfter)
			.as("롤백 시 alarm 키가 새로 생성되면 안 된다")
			.isSubsetOf(alarmKeysBefore == null ? java.util.Set.of() : alarmKeysBefore);
		then(reservationRepository).should().saveAndFlush(any(Reservation.class));
	}
}
