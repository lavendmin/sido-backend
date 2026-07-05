package com.sido.backend.reservation.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sido.backend.reservation.entity.ReservationDay;

import jakarta.persistence.LockModeType;

public interface ReservationDayRepository extends JpaRepository<ReservationDay, Long> {
	void deleteByReservationId(Long reservationId);

	@Query("""
			SELECT rd.date FROM ReservationDay rd
				WHERE rd.stay.id = :stayId
					AND rd.date IN :dates
		""")
	List<LocalDate> findReservedDatesIn(@Param("stayId") Long stayId, @Param("dates") List<LocalDate> dates);

	@Query("""
			SELECT rd.date FROM ReservationDay rd
				WHERE rd.stay.id = :stayId
				ORDER BY rd.date ASC
		""")
	List<LocalDate> findAllReserved(Long stayId);

	// 비관적 락 — FOR UPDATE로 최신 커밋 데이터 강제 읽기
	// MVCC 스냅샷 문제 우회: 서브쿼리 대신 별도 잠금 읽기로 분리
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT rd FROM ReservationDay rd
				WHERE rd.stay.id = :stayId
					AND rd.date >= :start
					AND rd.date < :endExclusive
		""")
	List<ReservationDay> findWithLockInRange(
		@Param("stayId") Long stayId,
		@Param("start") LocalDate start,
		@Param("endExclusive") LocalDate endExclusive
	);
}
