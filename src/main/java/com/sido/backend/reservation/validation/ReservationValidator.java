package com.sido.backend.reservation.validation;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.sido.backend.common.exception.BadRequestException;
import com.sido.backend.common.exception.ConflictException;
import com.sido.backend.common.exception.ForbiddenException;
import com.sido.backend.common.exception.ResourceGoneException;
import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.stay.entity.Stay;

@Component
public class ReservationValidator {
	public void assertCoreRules(Stay stay, LocalDate start, LocalDate end, Integer cnt) {
		LocalDate today = LocalDate.now();
		if (Boolean.FALSE.equals(stay.getIsActive())) {
			throw new BadRequestException("해당 사랑방은 예약이 닫혔습니다.");
		}
		if (start.isBefore(today)) {
			throw new BadRequestException("지난 날짜는 예약할 수 없습니다.");
		}
		if (end.isBefore(start)) {
			throw new BadRequestException("종료일은 시작일보다 앞설 수 없습니다.");
		}
		if (start.equals(end)) {
			throw new BadRequestException("당일치기는 불가능합니다.");
		}
		if (cnt == null || cnt < 1) {
			throw new BadRequestException("인원 수가 올바르지 않습니다.");
		}
		if (cnt > stay.getCapacity()) {
			throw new BadRequestException("예약 가능한 인원수를 초과하였습니다.");
		}
	}

	public void assertOwnedBy(Reservation reservation, Long memberId) {
		if (!reservation.getMember().getId().equals(memberId)) {
			throw new ForbiddenException("본인의 예약만 처리할 수 있습니다.");
		}
	}

	public void assertConfirmable(Reservation reservation) {
		if (reservation.getResrvStatus() != ResrvStatus.PENDING) {
			throw new ConflictException("현재 상태에서는 예약을 확정할 수 없습니다.");
		}
	}

	// 만료 스케줄러가 아직 CANCELLED로 수렴시키지 못했더라도, pendingExpiresAt이 지난 PENDING은 확정 권한이 없다.
	// 확정 트랜잭션에서 예약 행 잠금을 얻은 뒤 최신 deadline으로 판정한다(상태 전이의 시간 경계 계약).
	public void assertNotExpired(Reservation reservation) {
		LocalDateTime deadline = reservation.getPendingExpiresAt();
		if (deadline != null && !LocalDateTime.now().isBefore(deadline)) {
			throw new ResourceGoneException("확정 대기 시간이 만료되어 예약을 확정할 수 없습니다.");
		}
	}

	public void assertNotPending(Reservation reservation, String actionLabel) {
		if (reservation.getResrvStatus() == ResrvStatus.PENDING) {
			throw new ConflictException("확정 대기 중인 예약에서는 " + actionLabel + "를 진행할 수 없습니다.");
		}
	}
}
