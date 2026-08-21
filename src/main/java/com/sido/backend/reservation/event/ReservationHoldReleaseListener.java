package com.sido.backend.reservation.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sido.backend.reservation.service.DateHoldService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * DB 상태 전이가 커밋된 뒤에만 Redis 날짜 hold·alarm 키를 정리한다.
 * <p>
 * 트랜잭션이 롤백되면 이벤트가 발화하지 않아 hold가 그대로 남고, 커밋되면 이 시점에 해제된다.
 * release가 실패해도 이미 커밋된 예약 결과를 되돌리지 않는다 — 로깅만 하고 hold TTL을 최종 정리 상한으로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationHoldReleaseListener {

	private final DateHoldService dateHoldService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onRelease(ReservationHoldReleaseEvent event) {
		try {
			dateHoldService.releaseAll(event.stayId(), event.dates(), event.reservationId(), event.holdToken());
		} catch (Exception e) {
			log.error("커밋 후 hold release 실패 (TTL 정리에 위임): reservationId={}", event.reservationId(), e);
		}
	}
}
