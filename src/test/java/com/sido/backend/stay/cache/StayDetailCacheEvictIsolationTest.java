package com.sido.backend.stay.cache;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.sido.backend.member.entity.HostMember;
import com.sido.backend.member.repository.HostMemberRepository;
import com.sido.backend.stay.dto.StaySpecDTO;
import com.sido.backend.stay.dto.StayUpdateDTO;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.event.StayDetailCacheEvictListener;
import com.sido.backend.stay.repository.StayRepository;
import com.sido.backend.stay.service.StayDetailCacheService;
import com.sido.backend.stay.service.StayService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 결정 C 검증 — AFTER_COMMIT evict 실패가 이미 커밋된 DB 변경을 실패로 되돌리지 않는다.
 *
 * StayDetailCacheService 를 spy 로 두고 evictDetail 이 예외를 던지게 강제한다.
 * evict 는 커밋 이후에 실행되므로, 여기서 던진 예외가 리스너 밖으로 전파되면
 * 이미 성공한 수정이 API 500(거짓 실패)이 된다. 리스너가 예외를 격리하는지 확인한다.
 */
@SpringBootTest
class StayDetailCacheEvictIsolationTest {

	@Autowired private StayService stayService;
	@Autowired private StayRepository stayRepository;
	@Autowired private HostMemberRepository hostMemberRepository;

	@MockitoSpyBean private StayDetailCacheService stayDetailCacheService;

	private Long stayId;
	private Long hostId;

	@BeforeEach
	void setUp() {
		long uniq = System.nanoTime();
		String tail = String.valueOf(uniq);
		tail = tail.substring(tail.length() - 10);

		HostMember host = hostMemberRepository.save(HostMember.builder()
			.loginId("evict-iso-host-" + uniq)
			.password("pw12345678!")
			.phone("010" + tail)
			.villageName("테스트마을")
			.region("테스트지역")
			.build());
		hostId = host.getId();

		Stay stay = stayRepository.save(Stay.builder()
			.title("evict 격리 테스트 숙소")
			.address("evict-iso-addr-" + uniq)
			.detailAddress("101호")
			.capacity(4)
			.areaSize(40)
			.description("원본 설명")
			.isHomestay(Boolean.TRUE)
			.host(host)
			.build());
		stayId = stay.getId();
	}

	@AfterEach
	void tearDown() {
		if (stayId != null) {
			stayRepository.deleteById(stayId);
		}
		if (hostId != null) {
			hostMemberRepository.deleteById(hostId);
		}
	}

	@Test
	@DisplayName("evict 예외 격리: Redis evict 실패해도 DB 커밋·API 성공 유지, ERROR 로그 기록")
	void evict예외_격리_DB커밋_API성공_로그기록() {
		Logger listenerLogger = (Logger) LoggerFactory.getLogger(StayDetailCacheEvictListener.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		listenerLogger.addAppender(appender);

		try {
			// 커밋 이후 실행되는 evict 를 강제 실패시킨다
			doThrow(new RuntimeException("forced redis evict failure"))
				.when(stayDetailCacheService).evictDetail(stayId);

			// API 성공 계약: 예외가 전파되면 이 호출에서 던져진다
			StayUpdateDTO result = stayService.editStay(stayId, 0L,
				StayUpdateDTO.builder().staySpec(new StaySpecDTO(7, 77, "evict 실패에도 반영될 설명")).build());

			assertThat(result).as("evict 실패해도 editStay 는 정상 반환").isNotNull();

			// 이미 커밋된 DB 변경은 유지되어야 한다
			Stay reloaded = stayRepository.findById(stayId).orElseThrow();
			assertThat(reloaded.getDescription())
				.as("evict 실패와 무관하게 DB 변경은 커밋됨").isEqualTo("evict 실패에도 반영될 설명");

			// evict 는 실제로 시도되었고(리스너가 호출), 실패는 ERROR 로그로 남는다
			verify(stayDetailCacheService).evictDetail(stayId);
			assertThat(appender.list)
				.as("evict 실패는 stayId 를 포함한 ERROR 로그로 기록")
				.anyMatch(e -> e.getLevel() == Level.ERROR
					&& e.getFormattedMessage().contains(String.valueOf(stayId)));
		} finally {
			listenerLogger.detachAppender(appender);
		}
	}
}
