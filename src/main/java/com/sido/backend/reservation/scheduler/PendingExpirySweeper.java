package com.sido.backend.reservation.scheduler;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.repository.ReservationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keyspace Notification은 at-most-once라 이벤트 발생 순간 서버가 죽어 있으면 유실된다.
 * 유실된 만료 이벤트를 pendingExpiresAt 기준으로 회수하는 2차 안전망.
 * (1차: PendingExpiryListener — 즉시성 담당 / 2차: 본 스케줄러 — 최종 일관성 담당)
 * <p>
 * 후보 ID 조회와 항목별 잠금·상태 재검증을 분리해, 한 트랜잭션이 모든 만료 건을 오래 붙잡지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingExpirySweeper {

	private final ReservationRepository reservationRepository;
	private final PendingExpiryProcessor pendingExpiryProcessor;

	@Scheduled(fixedDelay = 10 * 60 * 1000) // 10분 주기
	public void sweepExpiredPendings() {
		List<Long> expiredIds = reservationRepository
			.findIdsByResrvStatusAndPendingExpiresAtBefore(ResrvStatus.PENDING, LocalDateTime.now());

		for (Long reservationId : expiredIds) {
			try {
				pendingExpiryProcessor.expireIfStillPending(reservationId); // 항목별 새 트랜잭션 + 행 잠금 재검증
			} catch (Exception e) {
				log.error("만료 PENDING 회수 실패: reservationId={}", reservationId, e);
			}
		}

		if (!expiredIds.isEmpty()) {
			log.info("만료된 PENDING 회수 처리 (Notification 유실 대비): {}건", expiredIds.size());
		}
	}
}
