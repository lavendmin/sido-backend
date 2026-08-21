package com.sido.backend.stay.cache;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sido.backend.member.entity.HostMember;
import com.sido.backend.member.repository.HostMemberRepository;
import com.sido.backend.stay.dto.StayResponseDetailDTO;
import com.sido.backend.stay.dto.StaySpecDTO;
import com.sido.backend.stay.dto.StayUpdateDTO;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.repository.StayRepository;
import com.sido.backend.stay.service.StayDetailCacheService;
import com.sido.backend.stay.service.StayService;

/**
 * 숙소 상세 캐시 "커밋 이후(after-commit) 무효화"의 트랜잭션 phase 불변식 검증.
 *
 * 실제 MySQL·Redis를 사용하는 통합 테스트다. 경합 창을 운에 맡긴 반복이 아니라
 * TransactionTemplate + TransactionSynchronization 으로 커밋 전/후 시점을 결정적으로 관찰한다.
 *
 * 캐시 존재 여부는 CacheManager("stay")로 직접 확인한다(키 포맷에 의존하지 않음).
 * evict 는 @TransactionalEventListener(AFTER_COMMIT) → evictDetail(@CacheEvict) 경로로만 일어난다.
 */
@SpringBootTest
class StayDetailCacheInvalidationTest {

	private static final String ORIGINAL_DESC = "원본 설명 - 캐시에 적재된 값";

	@Autowired private StayService stayService;
	@Autowired private StayDetailCacheService stayDetailCacheService;
	@Autowired private StayRepository stayRepository;
	@Autowired private HostMemberRepository hostMemberRepository;
	@Autowired private CacheManager cacheManager;
	@Autowired private PlatformTransactionManager txManager;

	private Long stayId;
	private Long hostId;
	private TransactionTemplate txTemplate;

	@BeforeEach
	void setUp() {
		txTemplate = new TransactionTemplate(txManager);
		long uniq = System.nanoTime();
		String tail = String.valueOf(uniq);
		tail = tail.substring(tail.length() - 10); // 빠르게 바뀌는 하위 자리로 유니크 phone 구성

		HostMember host = hostMemberRepository.save(HostMember.builder()
			.loginId("cache-inv-host-" + uniq)
			.password("pw12345678!")
			.phone("010" + tail)
			.villageName("테스트마을")
			.region("테스트지역")
			.build());
		hostId = host.getId();

		Stay stay = stayRepository.save(Stay.builder()
			.title("캐시무효화 테스트 숙소")
			.address("cache-inv-addr-" + uniq)
			.detailAddress("101호")
			.capacity(4)
			.areaSize(40)
			.description(ORIGINAL_DESC)
			.isHomestay(Boolean.TRUE)
			.host(host)
			.build());
		stayId = stay.getId();
	}

	@AfterEach
	void tearDown() {
		Cache cache = cacheManager.getCache("stay");
		if (cache != null && stayId != null) {
			cache.evict(stayId);
		}
		if (stayId != null) {
			stayRepository.deleteById(stayId);
		}
		if (hostId != null) {
			hostMemberRepository.deleteById(hostId);
		}
	}

	@Test
	@DisplayName("수정: 커밋 전에는 캐시 유지, 커밋 후에만 evict")
	void 수정_커밋전_캐시유지_커밋후_evict() {
		warm(); // 캐시 워밍
		assertThat(cached(stayId)).as("워밍 후 캐시 존재").isTrue();

		AtomicBoolean presentBeforeCommit = new AtomicBoolean();
		txTemplate.executeWithoutResult(status -> {
			stayService.editStay(stayId, 0L, updateDto("커밋 후 반영될 설명", 9, 90));
			// 같은 트랜잭션의 커밋 직전 시점을 관찰 — AFTER_COMMIT evict 는 아직 실행 전이어야 한다
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void beforeCommit(boolean readOnly) {
					presentBeforeCommit.set(cached(stayId));
				}
			});
		});

		assertThat(presentBeforeCommit.get()).as("커밋 전에는 evict 되지 않아 캐시 유지").isTrue();
		assertThat(cached(stayId)).as("커밋 후 evict 로 캐시 제거").isFalse();
	}

	@Test
	@DisplayName("삭제: 커밋 전에는 캐시 유지, 커밋 후 evict → 재조회 시 최신 삭제 상태")
	void 삭제_커밋전_캐시유지_커밋후_evict() {
		warm(); // isDeleted=false 상태를 캐시에 적재
		assertThat(cached(stayId)).as("워밍 후 캐시 존재").isTrue();

		AtomicBoolean presentBeforeCommit = new AtomicBoolean();
		txTemplate.executeWithoutResult(status -> {
			var result = stayService.deleteStay(hostId, stayId);
			assertThat(result.deleted()).as("소프트 삭제 성공").isTrue();
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void beforeCommit(boolean readOnly) {
					presentBeforeCommit.set(cached(stayId));
				}
			});
		});

		assertThat(presentBeforeCommit.get()).as("커밋 전에는 캐시 유지").isTrue();
		assertThat(cached(stayId)).as("커밋 후 evict 로 캐시 제거").isFalse();
		// 통제된 순차 재조회: 캐시가 비었으므로 DB 기준 최신 삭제 상태를 반환
		assertThat(readDetail().getIsDeleted())
			.as("재조회 시 isDeleted=true (stale 아님)").isTrue();
	}

	@Test
	@DisplayName("강제 롤백: DB·캐시 모두 이전 상태 유지 (불필요한 evict 없음)")
	void 강제롤백_캐시유지_DB유지() {
		warm(); // 캐시 워밍
		assertThat(cached(stayId)).as("워밍 후 캐시 존재").isTrue();

		txTemplate.executeWithoutResult(status -> {
			stayService.editStay(stayId, 0L, updateDto("롤백되어 사라질 설명", 99, 999));
			status.setRollbackOnly(); // 강제 롤백 → AFTER_COMMIT 리스너 미실행
		});

		assertThat(cached(stayId)).as("롤백 시 evict 되지 않아 캐시 유지").isTrue();
		assertThat(stayRepository.findById(stayId).orElseThrow().getDescription())
			.as("롤백으로 DB 는 원본 유지").isEqualTo(ORIGINAL_DESC);
		assertThat(readDetail().getDescription())
			.as("캐시 히트도 원본 유지").isEqualTo(ORIGINAL_DESC);
	}

	private boolean cached(Long id) {
		Cache cache = cacheManager.getCache("stay");
		return cache != null && cache.get(id) != null;
	}

	// getDetailBase 캐시 미스 경로는 Stay.images 를 지연 로딩한다. 운영에선 OSIV 로 세션이 열려 있지만
	// 테스트의 직접 서비스 호출엔 OSIV 가 없으므로, 읽기 전용 트랜잭션으로 세션을 열어 준다.
	private StayResponseDetailDTO readDetail() {
		return txTemplate.execute(status -> stayDetailCacheService.getDetailBase(stayId));
	}

	private void warm() {
		readDetail();
	}

	private StayUpdateDTO updateDto(String description, int capacity, int areaSize) {
		return StayUpdateDTO.builder()
			.staySpec(new StaySpecDTO(capacity, areaSize, description))
			.build();
	}
}
