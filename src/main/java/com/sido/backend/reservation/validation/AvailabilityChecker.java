package com.sido.backend.reservation.validation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sido.backend.common.exception.ConflictException;
import com.sido.backend.common.exception.ResourceGoneException;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.entity.StayAvailDate;
import com.sido.backend.stay.repository.StayAvailDateRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AvailabilityChecker {
	private final StayAvailDateRepository stayAvailDateRepository;

	public void assertAllDatesAvailable(Long stayId, LocalDate start, LocalDate end) {
		long requestDays = ChronoUnit.DAYS.between(start, end);
		long availableDays = stayAvailDateRepository.countOpenAndUnreservedInRange(stayId, start, end);
		System.out.println("requestDays = " + requestDays);
		System.out.println("availableDays = " + availableDays);
		if (requestDays != availableDays) {
			throw new ConflictException("선택한 기간에 예약 불가 날짜가 포함되어 있습니다.");
		}
	}

	// SELECT FOR UPDATE로 날짜 행을 잠근 뒤 가용성 확인 — race window 원천 차단
	// FOR UPDATE는 최신 커밋 데이터를 읽으므로 REPEATABLE_READ 스냅샷 문제 없음
	public void assertAllDatesAvailableWithLock(Long stayId, LocalDate start, LocalDate end) {
		List<StayAvailDate> available = stayAvailDateRepository.findWithLockInRange(stayId, start, end);
		long requestDays = ChronoUnit.DAYS.between(start, end);
		if (available.size() != requestDays) {
			throw new ConflictException("선택한 기간에 예약 불가 날짜가 포함되어 있습니다.");
		}
	}

	public void assertStayIsActive(Stay stay) {
		if (!Boolean.TRUE.equals(stay.getIsActive())) {
			throw new ResourceGoneException("해당 사랑방은 삭제되어 더 이상 예약할 수 없습니다.");
		}
	}
}
