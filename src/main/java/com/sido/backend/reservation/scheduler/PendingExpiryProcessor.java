package com.sido.backend.reservation.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.event.ReservationHoldReleaseEvent;
import com.sido.backend.reservation.repository.ReservationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 만료된 PENDING 한 건을 회수하는 항목별 트랜잭션 단위.
 * <p>
 * 즉시 리스너(PendingExpiryListener)와 유실 대비 스위퍼(PendingExpirySweeper)가 공통으로 호출한다.
 * 예약 행을 잠근 뒤 최신 상태·deadline을 재검증하므로, confirm이 먼저 잠금을 얻어 RESERVED로 확정했다면
 * 여기서는 상태를 보고 no-op 한다. 별도 트랜잭션으로 분리해 스위퍼가 만료 건을 한꺼번에 붙잡지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingExpiryProcessor {

	private final ReservationRepository reservationRepository;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public void expireIfStillPending(Long reservationId) {
		Reservation reservation = reservationRepository.findByIdForUpdate(reservationId).orElse(null);
		if (reservation == null) {
			return;
		}

		// 잠금 획득 뒤 최신 값으로 재검증: 이미 확정/취소됐거나 아직 만료 전이면 건드리지 않는다
		if (reservation.getResrvStatus() != ResrvStatus.PENDING) {
			return;
		}
		LocalDateTime deadline = reservation.getPendingExpiresAt();
		if (deadline != null && LocalDateTime.now().isBefore(deadline)) {
			return;
		}

		String holdToken = reservation.getHoldToken();

		reservation.setResrvStatus(ResrvStatus.CANCELLED);
		reservation.setHoldToken(null); // CANCELLED 전이 시 소유 토큰 비움 (상태별 불변식)
		log.info("PENDING 만료로 예약 취소 처리: reservationId={}", reservationId);

		// 커밋 이후 이 예약의 토큰과 일치하는 날짜 키만 해제 (best-effort, TTL로 이미 소멸됐을 수 있음)
		if (reservation.getStay() != null) {
			List<LocalDate> dates = reservation.getStartDate()
				.datesUntil(reservation.getEndDate())
				.toList();
			eventPublisher.publishEvent(
				new ReservationHoldReleaseEvent(reservation.getStay().getId(), dates, reservationId, holdToken));
		}
	}
}
