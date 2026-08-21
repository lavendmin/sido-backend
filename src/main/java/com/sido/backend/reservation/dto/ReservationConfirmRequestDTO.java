package com.sido.backend.reservation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 예약 확정 요청 계약.
 * <p>
 * 예약 기간(startDate/endDate)은 create에서 확정한 뒤 변경하지 않는다. confirm은 PENDING을 RESERVED로
 * 바꾸는 상태 전이이지 기간 수정 API가 아니므로, 저장된 원래 기간만 사용한다. 날짜 변경이 필요하면 기존 PENDING을
 * 취소하고 create부터 다시 시작한다. 따라서 이 DTO는 인원수·농장 체험 유무만 받는다.
 */
public record ReservationConfirmRequestDTO(
	Integer personCnt,

	@NotNull
	Boolean isFarm
) {
}
