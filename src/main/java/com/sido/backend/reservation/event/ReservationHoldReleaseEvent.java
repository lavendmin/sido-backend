package com.sido.backend.reservation.event;

import java.time.LocalDate;
import java.util.List;

/**
 * 예약 수명주기 전이(confirm·cancel·만료 회수)에서 발행하는 Redis hold 해제 이벤트.
 * <p>
 * DB 상태 전이를 먼저 확정하고, 파생된 임시 hold 정리는 커밋 이후로 미룬다(정상 상태의 release는 커밋 뒤).
 * 이벤트 값은 발행 시점에 확정된 불변 스냅샷이라 이후 엔티티 변경(토큰 비우기 등)의 영향을 받지 않는다.
 */
public record ReservationHoldReleaseEvent(
	Long stayId,
	List<LocalDate> dates,
	Long reservationId,
	String holdToken
) {
	public ReservationHoldReleaseEvent {
		dates = List.copyOf(dates); // 방어적 불변 복사
	}
}
