package com.sido.backend.stay.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.sido.backend.stay.dto.StayResponseDetailDTO;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.repository.StayRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * 숙소 상세의 "정적 정보"만 캐싱한다.
 *
 * 캐싱 범위를 정적 정보로 한정한 이유:
 * - 상세 응답의 stayResrvStatus는 날짜 범위 기반 가용성 파생 데이터 → 캐싱 시 낡은 예약 상태가 서빙됨.
 *   가용성 계열은 실시간 이벤트 기반 무효화와 함께 도입해야 하므로 이번 범위에서 제외.
 * - 정적 정보(제목·주소·설명·이미지)는 호스트 수정 시에만 변함 → 수정 시 evict + TTL 상한으로 일관성 확보.
 *
 * StayServiceImpl에서 클래스를 분리한 이유: @Cacheable은 프록시 기반이라
 * 같은 빈 내부에서 자기 메서드를 호출(self-invocation)하면 캐시가 적용되지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StayDetailCacheService {

	private final StayRepository stayRepository;

	@Value("${app.s3.publicBaseUrl}")
	private String publicBaseUrl;

	// 캐시 히트 시 DB(Stay + StayImage) 조회 없이 Redis의 복사본을 역직렬화해 반환.
	// 미스 시에만 본문이 실행되고 결과가 "stay::{stayId}" 키로 저장된다 (TTL은 CacheConfig).
	// 존재하지 않는 stayId는 예외가 던져져 캐시에 저장되지 않는다.
	@Cacheable(value = "stay", key = "#stayId")
	public StayResponseDetailDTO getDetailBase(Long stayId) {
		Stay stay = stayRepository.findById(stayId).orElseThrow(
			() -> new EntityNotFoundException("해당 사랑방을 찾을 수 없습니다.")
		);
		return toResponseDetailDTO(stay);
	}

	// 숙소 수정·삭제 시 호출 — 낡은 상세 정보가 TTL 만료 전까지 서빙되는 것을 막는 주 경로
	@CacheEvict(value = "stay", key = "#stayId")
	public void evictDetail(Long stayId) {
	}

	// 정적 정보만 담는 매퍼 — stayResrvStatus는 의도적으로 채우지 않는다 (캐시에 가용성 미포함)
	public StayResponseDetailDTO toResponseDetailDTO(Stay stay) {
		List<String> imageUrls = stay.getImages().stream()
			.map(img -> publicBaseUrl + "/" + img.getS3Key())
			.toList();

		return StayResponseDetailDTO.builder()
			.id(stay.getId())
			.title(stay.getTitle())
			.address(stay.getAddress())
			.detailAddress(stay.getDetailAddress())
			.capacity(stay.getCapacity())
			.areaSize(stay.getAreaSize())
			.description(stay.getDescription())
			.isHomestay(stay.getIsHomestay())
			.isDeleted(!stay.getIsActive())
			.images(imageUrls)
			.build();
	}
}
