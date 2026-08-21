package com.sido.backend.stay.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sido.backend.stay.service.StayDetailCacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 숙소 상세 변경 이벤트를 받아 상세 캐시를 무효화하는 AFTER_COMMIT 리스너.
 *
 * phase = AFTER_COMMIT: 서비스 트랜잭션이 실제로 커밋된 뒤에만 실행된다.
 * - 커밋 전 evict 후 재조회가 아직 커밋되지 않은 이전 DB 값을 다시 적재하던 공백을 닫는다.
 * - 롤백된 변경에는 리스너가 아예 실행되지 않아 정상 캐시가 불필요하게 사라지지 않는다.
 *
 * 동기 실행(@Async 미사용): 요청 스레드에서 커밋 직후 순차로 evict 해 응답 반환 전에 무효화를 마친다.
 *
 * Redis 예외 격리(결정 C): evict 실패는 리스너 내부에서 로깅하고 삼킨다.
 * - 이미 커밋된 수정·삭제를 사용자에게 500 실패로 되돌리지 않기 위함.
 * - evict 누락의 상한선은 캐시 TTL 5분이 담당한다.
 *
 * 남는 한계: 커밋 전에 시작한 cache miss 조회가 AFTER_COMMIT evict 이후 이전 값을 늦게 put 하면
 * 최대 TTL 5분까지 낡은 값이 남을 수 있다. 이 잔여 경합은 이번 범위에서 닫지 않고 한계로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StayDetailCacheEvictListener {

	private final StayDetailCacheService stayDetailCacheService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onStayDetailChanged(StayDetailChangedEvent event) {
		try {
			stayDetailCacheService.evictDetail(event.stayId());
		} catch (RuntimeException ex) {
			// Redis evict 실패가 이미 커밋된 DB 변경을 실패로 되돌리지 않도록 여기서 종료 처리한다.
			// 낡은 상세 캐시는 최대 TTL 5분 뒤 만료로 수렴한다.
			log.error("숙소 상세 캐시 evict 실패 stayId={} — DB 변경은 이미 커밋됨, 최대 TTL 5분까지 낡은 캐시 가능",
				event.stayId(), ex);
		}
	}
}
