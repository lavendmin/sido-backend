package com.sido.backend.reservation.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.entity.VisitStatus;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
	// PendingExpirySweeper용 — Keyspace Notification 유실 시 만료된 PENDING 회수
	List<Reservation> findByResrvStatusAndPendingExpiresAtBefore(ResrvStatus resrvStatus, LocalDateTime now);

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
