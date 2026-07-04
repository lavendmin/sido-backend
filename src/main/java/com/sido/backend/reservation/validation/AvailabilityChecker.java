package com.sido.backend.reservation.validation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sido.backend.common.exception.ConflictException;
import com.sido.backend.common.exception.ResourceGoneException;
import com.sido.backend.reservation.entity.ReservationDay;
import com.sido.backend.reservation.repository.ReservationDayRepository;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.entity.StayAvailDate;
import com.sido.backend.stay.repository.StayAvailDateRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AvailabilityChecker {
	private final StayAvailDateRepository stayAvailDateRepository;
	private final ReservationDayRepository reservationDayRepository;

	public void assertAllDatesAvailable(Long stayId, LocalDate start, LocalDate end) {
		long requestDays = ChronoUnit.DAYS.between(start, end);
		long availableDays = stayAvailDateRepository.countOpenAndUnreservedInRange(stayId, start, end);
		System.out.println("requestDays = " + requestDays);
		System.out.println("availableDays = " + availableDays);
		if (requestDays != availableDays) {
			throw new ConflictException("선택한 기간에 예약 불가 날짜가 포함되어 있습니다.");
		}
	}

	// 비관적 락으로 race window 원천 차단
	// 두 단계로 분리:
	// 1) StayAvailDate FOR UPDATE → 다른 트랜잭션 직렬화 (대기 강제)
	// 2) ReservationDay FOR UPDATE → 최신 커밋 데이터로 실제 점유 확인
	// NOT EXISTS 서브쿼리는 MVCC 스냅샷을 읽어 VU1 커밋 내용을 못 보므로 이 방식 사용
	public void assertAllDatesAvailableWithLock(Long stayId, LocalDate start, LocalDate end) {
		List<StayAvailDate> openDates = stayAvailDateRepository.findWithLockInRange(stayId, start, end);
		List<ReservationDay> reservedDays = reservationDayRepository.findWithLockByDateRange(stayId, start, end);

		long requestDays = ChronoUnit.DAYS.between(start, end);
		long availableDays = openDates.size() - reservedDays.size();
		if (requestDays != availableDays) {
			throw new ConflictException("선택한 기간에 예약 불가 날짜가 포함되어 있습니다.");
		}
	}

	public void assertStayIsActive(Stay stay) {
		if (!Boolean.TRUE.equals(stay.getIsActive())) {
			throw new ResourceGoneException("해당 사랑방은 삭제되어 더 이상 예약할 수 없습니다.");
		}
	}
}
