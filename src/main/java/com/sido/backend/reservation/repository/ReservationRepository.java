package com.sido.backend.reservation.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.entity.VisitStatus;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
	// PendingExpirySweeper용 — Keyspace Notification 유실 시 만료된 PENDING 회수
	List<Reservation> findByResrvStatusAndPendingExpiresAtBefore(ResrvStatus resrvStatus, LocalDateTime now);

	// 수명주기 전이(confirm·cancel·만료 회수) 직렬화용 — 같은 예약 행을 PESSIMISTIC_WRITE로 잠근 뒤
	// 최신 상태·소유자·pendingExpiresAt을 재검증한다. 가용일 행 잠금과 역할이 다르다(이쪽은 상태 전이 보호).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM Reservation r WHERE r.id = :id")
	Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

	@Query("""
		select
		    coalesce(sum(case when r.visitStatus = com.sido.backend.reservation.entity.VisitStatus.UPCOMING then 1 else 0 end), 0) as upcomingCnt,
		    coalesce(sum(case when r.visitStatus = com.sido.backend.reservation.entity.VisitStatus.IN_PROGRESS then 1 else 0 end), 0) as inProgressCnt,
			coalesce(sum(case when r.visitStatus = com.sido.backend.reservation.entity.VisitStatus.COMPLETED then 1 else 0 end), 0) as completedCnt
		from Reservation r
			where r.stay.host.id = :hostId
		""")
	ReservationCounts summarizeByHost(@Param("hostId") Long hostId);

	// 호스트 탈퇴 전 예약 확인용
	boolean existsByStay_Host_IdAndVisitStatusIn(Long hostId, Collection<VisitStatus> statuses);

	@Query("""
		select
			case when count(r) > 0 then true else false end
		from Reservation r
			where r.stay.id = :stayId
				and r.resrvStatus = com.sido.backend.reservation.entity.ResrvStatus.RESERVED
				and r.visitStatus = com.sido.backend.reservation.entity.VisitStatus.UPCOMING
		""")
	boolean existsUpcomingByStay(@Param("stayId") Long stayId);

	interface ReservationCounts {
		long getUpcomingCnt();

		long getInProgressCnt();

		long getCompletedCnt();
	}
}
