package com.sido.backend.reservation.scheduler;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.repository.ReservationRepository;
import com.sido.backend.reservation.service.DateHoldService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingExpiryListener implements MessageListener {

	private static final String ALARM_PREFIX = "reservation:expire:";

	private final ReservationRepository reservationRepository;
	private final DateHoldService dateHoldService;

	@Override
	@Transactional
	public void onMessage(Message message, byte[] pattern) {
		String expiredKey = new String(message.getBody());

		if (!expiredKey.startsWith(ALARM_PREFIX)) {
			return;
		}

		try {
			Long reservationId = Long.parseLong(expiredKey.substring(ALARM_PREFIX.length()));

			reservationRepository.findById(reservationId).ifPresent(reservation -> {
				if (reservation.getResrvStatus() != ResrvStatus.PENDING) {
					return;
				}

				reservation.setResrvStatus(ResrvStatus.CANCELLED);
				reservationRepository.save(reservation);
				log.info("PENDING 만료로 예약 취소 처리: reservationId={}", reservationId);

				// hold 키도 만료됐겠지만 best-effort로 삭제 시도 (member가 탈퇴로 null이면 TTL 소멸에 맡김)
				if (reservation.getStay() != null && reservation.getMember() != null) {
					Long stayId = reservation.getStay().getId();
					List<LocalDate> dates = reservation.getStartDate()
						.datesUntil(reservation.getEndDate())
						.collect(Collectors.toList());
					dateHoldService.releaseDateHolds(stayId, dates, reservation.getMember().getId());
				}
			});

		} catch (Exception e) {
			log.error("PENDING 만료 처리 실패: key={}", expiredKey, e);
		}
	}
}
