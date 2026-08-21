package com.sido.backend.stay.event;

/**
 * 숙소 상세 정적 정보가 변경돼 상세 캐시가 더 이상 유효하지 않다는 최소 의미의 도메인 이벤트.
 *
 * 변경 유형(수정/삭제)을 구분하지 않는 이유: 무효화 계약은 "이 stay의 상세 캐시를 버려야 한다"로
 * 동일하므로 수정·삭제가 같은 이벤트·같은 AFTER_COMMIT 리스너를 공유한다.
 *
 * 서비스 트랜잭션 안에서 발행하고, 커밋이 성공한 뒤에만 리스너가 evict 하도록 해
 * (1) 커밋 전 무효화로 낡은 값이 재적재되는 공백과 (2) 롤백 시 불필요한 evict 를 함께 막는다.
 */
public record StayDetailChangedEvent(Long stayId) {
}
