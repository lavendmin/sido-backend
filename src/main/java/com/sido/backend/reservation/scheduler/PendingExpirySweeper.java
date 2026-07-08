package com.sido.backend.reservation.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.repository.ReservationRepository;
import com.sido.backend.reservation.service.DateHoldService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keyspace Notification은 at-most-once라 이벤트 발생 순간 서버가 죽어 있으면 유실된다.
 * 유실된 만료 이벤트를 pendingExpiresAt 기준으로 회수하는 2차 안전망.
 * (1차: PendingExpiryListener — 즉시성 담당 / 2차: 본 스케줄러 — 최종 일관성 담당)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingExpirySweeper {

	private final ReservationRepository reservationRepository;
	private final DateHoldService dateHoldService;

	@Scheduled(fixedDelay = 10 * 60 * 1000) // 10분 주기
	@Transactional
	public void sweepExpiredPendings() {
		List<Reservation> expired = reservationRepository
			.findByResrvStatusAndPendingExpiresAtBefore(ResrvStatus.PENDING, LocalDateTime.now());

		for (Reservation reservation : expired) {
			reservation.setResrvStatus(ResrvStatus.CANCELLED);

			// hold 키는 TTL로 이미 소멸됐을 가능성이 높지만 best-effort로 삭제 시도
			if (reservation.getStay() != null && reservation.getMember() != null) {
				List<LocalDate> dates = reservation.getStartDate()
					.datesUntil(reservation.getEndDate())
					.collect(Collectors.toList());
				dateHoldService.releaseDateHolds(
					reservation.getStay().getId(), dates, reservation.getMember().getId());
			}
		}

		if (!expired.isEmpty()) {
			log.info("만료된 PENDING 회수 처리 (Notification 유실 대비): {}건", expired.size());
		}
	}
}
