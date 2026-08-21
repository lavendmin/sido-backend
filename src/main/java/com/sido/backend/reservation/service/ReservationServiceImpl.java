package com.sido.backend.reservation.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sido.backend.common.dto.PageResponseDTO;
import com.sido.backend.common.exception.BadRequestException;
import com.sido.backend.common.exception.ConflictException;
import com.sido.backend.member.entity.HostMember;
import com.sido.backend.member.entity.Member;
import com.sido.backend.member.repository.HostMemberRepository;
import com.sido.backend.member.repository.MemberRepository;
import com.sido.backend.reservation.dto.ReservationCommonDTOs.ReservationInfoDTO;
import com.sido.backend.reservation.dto.ReservationCommonDTOs.StaySummaryDTO;
import com.sido.backend.reservation.dto.ReservationConfirmRequestDTO;
import com.sido.backend.reservation.dto.ReservationConfirmResponseDTO;
import com.sido.backend.reservation.dto.ReservationCreateRequestDTO;
import com.sido.backend.reservation.dto.ReservationCreateResponseDTO;
import com.sido.backend.reservation.dto.ReservationDetailResponseDTO;
import com.sido.backend.reservation.dto.ReservationListFilter;
import com.sido.backend.reservation.dto.ReservationListItemDTO;
import com.sido.backend.reservation.dto.ReservationNextDTO;
import com.sido.backend.reservation.dto.ReservationNotificationDTO;
import com.sido.backend.reservation.dto.ReservationOverviewDTO;
import com.sido.backend.reservation.dto.ReservationViewStatus;
import com.sido.backend.reservation.entity.Reservation;
import com.sido.backend.reservation.entity.ReservationDay;
import com.sido.backend.reservation.entity.ResrvStatus;
import com.sido.backend.reservation.entity.VisitStatus;
import com.sido.backend.reservation.repository.ReservationDayRepository;
import com.sido.backend.reservation.repository.ReservationQDslRepository;
import com.sido.backend.reservation.repository.ReservationRepository;
import com.sido.backend.reservation.repository.ReservationRepository.ReservationCounts;
import com.sido.backend.reservation.validation.AvailabilityChecker;
import com.sido.backend.reservation.validation.ReservationValidator;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.repository.StayRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationServiceImpl implements ReservationService {
	private final ReservationRepository reservationRepository;
	private final ReservationDayRepository reservationDayRepository;
	private final ReservationQDslRepository reservationQDslRepository;
	private final StayRepository stayRepository;
	private final MemberRepository memberRepository;
	private final HostMemberRepository hostMemberRepository;
	private final ReservationValidator reservationValidator;
	private final AvailabilityChecker availabilityChecker;
	private final SimpMessagingTemplate messagingTemplate;
	private final DateHoldService dateHoldService;

	@Value("${app.s3.publicBaseUrl}")
	private String publicBaseUrl;

	@Override
	@Transactional
	public ReservationCreateResponseDTO createReservation(Long memberId, Long stayId,
		ReservationCreateRequestDTO createRequest) {
		Stay stay = stayRepository.findById(stayId).orElseThrow(
			() -> new EntityNotFoundException("해당 사랑방을 찾을 수 없습니다.")
		);
		Member member = memberRepository.findById(memberId).orElseThrow(
			() -> new EntityNotFoundException("해당 사용자를 찾을 수 없습니다.")
		);

		availabilityChecker.assertStayIsActive(stay); // 삭제된 사랑방인지 검증

		// 기본값
		LocalDate today = LocalDate.now();
		LocalDate startDate =
			(createRequest.startDate() == null) ? today : createRequest.startDate();
		LocalDate endDate =
			(createRequest.endDate() == null) ? today.plusDays(2) : createRequest.endDate();
		Integer personCnt =
			(createRequest.personCnt() == null) ? 2 : createRequest.personCnt();

		reservationValidator.assertCoreRules(stay, startDate, endDate, personCnt);
		availabilityChecker.assertAllDatesAvailable(stayId, startDate, endDate);

		List<LocalDate> dates = startDate.datesUntil(endDate).collect(Collectors.toList());

		// 생성 요청별 고유 토큰과 단일 만료 deadline을 한 번 정해 hold·DB·alarm이 같은 기준을 쓰게 한다
		String holdToken = UUID.randomUUID().toString();
		LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(DateHoldService.HOLD_MINUTES);

		boolean held = dateHoldService.tryHoldAll(stayId, dates, holdToken, expiresAt);
		if (!held) {
			throw new ConflictException("이미 다른 사용자가 선택 중인 날짜입니다.");
		}

		Reservation reservation = new Reservation();
		reservation.setStay(stay);
		reservation.setMember(member);
		reservation.setResrvStatus(ResrvStatus.PENDING);
		reservation.setStartDate(startDate);
		reservation.setEndDate(endDate);
		reservation.setPersonCnt(personCnt);
		reservation.setPendingExpiresAt(expiresAt);
		reservation.setHoldToken(holdToken);

		try {
			reservationRepository.save(reservation);
			dateHoldService.setExpiryAlarm(reservation.getId(), expiresAt);
		} catch (Exception e) {
			dateHoldService.releaseDateHolds(stayId, dates, holdToken);
			throw e;
		}

		return toCreateResponseDTO(reservation);
	}

	@Override
	@Transactional
	public ReservationConfirmResponseDTO confirmReservation(Long memberId, Long reservationId,
		ReservationConfirmRequestDTO confirmRequest) {
		log.info("예약 확정 프로세스 시작: reservationId={}", reservationId);
		// 예약 행을 PESSIMISTIC_WRITE로 잠가 confirm·cancel·만료 회수의 수명주기 전이를 직렬화한다.
		// 잠금을 얻은 뒤의 최신 상태·소유자·deadline으로만 판정한다.
		Reservation reservation = reservationRepository.findByIdForUpdate(reservationId).orElseThrow(
			() -> new EntityNotFoundException("해당 예약을 찾을 수 없습니다.")
		);

		availabilityChecker.assertStayIsActive(reservation.getStay()); // 삭제된 사랑방인지 검증
		reservationValidator.assertOwnedBy(reservation, memberId); // 본인 예약 검증

		// 멱등성: 이미 예약됐으면 현재 상태 그대로 반환
		if (reservation.getResrvStatus() == ResrvStatus.RESERVED) {
			log.info("이미 확정된 예약입니다: reservationId={}", reservationId);
			return toConfirmResponseDTO(reservation);
		}

		reservationValidator.assertConfirmable(reservation); // PENDING인지 검증
		reservationValidator.assertNotExpired(reservation); // pendingExpiresAt 경과 시 410, 점유 행 만들지 않음

		if (confirmRequest.isFarm() == null) {
			throw new BadRequestException("농장 체험 유무를 선택해야 합니다.");
		}

		// 확정은 저장된 원래 기간으로만 진행한다 (요청에서 날짜를 받지 않음). 인원수·농장 체험만 수정 허용.
		LocalDate startDate = reservation.getStartDate();
		LocalDate endDate = reservation.getEndDate();
		Integer personCnt =
			(confirmRequest.personCnt() != null) ? confirmRequest.personCnt() : reservation.getPersonCnt();

		// 예약 요청 검증 — SELECT FOR UPDATE로 날짜 행 선점 후 가용성 확인
		reservationValidator.assertCoreRules(reservation.getStay(), startDate, endDate, personCnt);
		availabilityChecker.assertAllDatesAvailableWithLock(reservation.getStay().getId(), startDate, endDate);

		// 엔티티에 반영
		reservation.setPersonCnt(personCnt);
		reservation.setIsFarm(confirmRequest.isFarm());

		List<ReservationDay> reservationDays = new ArrayList<>();
		for (LocalDate d = startDate; d.isBefore(endDate); d = d.plusDays(1)) {
			ReservationDay day = ReservationDay.builder()
				.date(d)
				.reservation(reservation)
				.stay(reservation.getStay())
				.build();
			reservationDays.add(day);
		}

		try {
			reservationDayRepository.saveAll(reservationDays);
		} catch (DataIntegrityViolationException e) { // 409 CONFLICT
			throw new ConflictException("다른 사용자가 먼저 예약을 확정했습니다.");
		}

		dateHoldService.releaseAll(
			reservation.getStay().getId(),
			startDate.datesUntil(endDate).collect(Collectors.toList()),
			reservationId,
			reservation.getHoldToken()
		);

		reservation.setResrvStatus(ResrvStatus.RESERVED);
		reservation.setHoldToken(null); // RESERVED 전이 시 소유 토큰 비움 (상태별 불변식)
		reservation.setReservedAt(LocalDateTime.now());
		log.info("예약이 성공적으로 확정되었습니다: reservationId={}", reservationId);

		// 관리자에게 예약 확정 알림 보내기
		log.info("관리자 알림 전송 로직 시작");
		Stay stay = reservation.getStay();
		if (stay == null) {
			log.error("Reservation에 Stay 객체가 없습니다! reservationId={}", reservationId);
			return toConfirmResponseDTO(reservation);
		}
		log.info("Stay 객체 확인: stayId={}", stay.getId());

		HostMember admin = stay.getHost();
		if (admin != null) {
			log.info("HostMember(관리자) 객체 확인: adminId={}", admin.getId());
			String topic = "/topic/reservations/" + admin.getId();
			DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
			String reservationDate =
				reservation.getStartDate().format(formatter) + " ~ " + reservation.getEndDate().format(formatter);
			ReservationNotificationDTO notification = new ReservationNotificationDTO(
				reservation.getId(),
				stay.getTitle(),
				reservationDate,
				stay.getHostName()
			);

			log.info("관리자에게 알림 전송: topic={}, payload={}", topic, notification);
			messagingTemplate.convertAndSend(topic, notification);
			log.info("관리자 알림 전송 완료");
		} else {
			log.warn("HostMember(관리자)가 존재하지 않습니다. stayId={}", stay.getId());
		}

		return toConfirmResponseDTO(reservation);
	}

	@Override
	public ReservationDetailResponseDTO getReservationDetail(Long memberId, Long reservationId) {
		Reservation reservation = reservationRepository.findById(reservationId).orElseThrow(
			() -> new EntityNotFoundException("해당 예약을 찾을 수 없습니다.")
		);

		HostMember hostMember = hostMemberRepository.findById(memberId).orElse(null);

		if (!reservation.getStay().getHost().equals(hostMember)) { // 해당 사랑방 가진 마을 이장님은 허용
			reservationValidator.assertOwnedBy(reservation, memberId); // 본인 예약만 확인 가능
		}

		reservationValidator.assertNotPending(reservation, "예약 상세 조회");

		return toDetailResponseDTO(reservation);
	}

	@Override
	@Transactional
	public void cancelReservation(Long memberId, Long reservationId) {
		Reservation reservation = reservationRepository.findById(reservationId).orElseThrow(
			() -> new EntityNotFoundException("해당 예약을 찾을 수 없습니다.")
		);

		reservationValidator.assertOwnedBy(reservation, memberId); // 본인 예약 검증

		// 멱등성
		if (reservation.getResrvStatus() == ResrvStatus.CANCELLED) {
			return;
		}

		List<LocalDate> dates = reservation.getStartDate()
			.datesUntil(reservation.getEndDate())
			.collect(Collectors.toList());
		dateHoldService.releaseAll(reservation.getStay().getId(), dates, reservationId, reservation.getHoldToken());

		reservation.setResrvStatus(ResrvStatus.CANCELLED); // 예약 취소 상태로
		reservation.setVisitStatus(null); // 방문 상태 null로
		reservation.setHoldToken(null); // CANCELLED 전이 시 소유 토큰 비움 (상태별 불변식)
		reservationRepository.save(reservation);

		reservationDayRepository.deleteByReservationId(reservationId); // ReservationDay 날짜 점유 해제
	}

	@Override
	public ReservationOverviewDTO getReservationOverview(Long memberId) {
		HostMember hostMember = hostMemberRepository.findById(memberId).orElseThrow(
			() -> new EntityNotFoundException("해당 호스트를 찾을 수 없습니다.")
		);

		ReservationCounts resrvCnt = reservationRepository.summarizeByHost(memberId);

		return ReservationOverviewDTO.builder()
			.villageName(hostMember.getVillageName())
			.upcomingCnt(resrvCnt.getUpcomingCnt())
			.inProgressCnt(resrvCnt.getInProgressCnt())
			.completedCnt(resrvCnt.getCompletedCnt())
			.build();
	}

	@Override
	public PageResponseDTO<ReservationListItemDTO, Reservation> getReservationList(Long memberId, int page,
		int listSize, ReservationListFilter filter) {
		memberRepository.findById(memberId).orElseThrow(
			() -> new EntityNotFoundException("해당 사용자를 찾을 수 없습니다.")
		);

		Pageable pageable = PageRequest.of(page - 1, listSize);
		Slice<Reservation> reservationSlice = reservationQDslRepository.findList(memberId, filter, pageable);

		return new PageResponseDTO<>(reservationSlice, this::toListItemDTO);
	}

	@Override
	public ReservationNextDTO getNextReservation(Long memberId) {
		Member member = memberRepository.findById(memberId).orElseThrow(
			() -> new EntityNotFoundException("해당 사용자를 찾을 수 없습니다.")
		);

		Pageable pageable = PageRequest.of(0, 1);

		Slice<Reservation> reservationSlice = reservationQDslRepository.findList(memberId,
			ReservationListFilter.RESERVED, pageable);

		Reservation nextReservation = reservationSlice.getContent().isEmpty()
			? null
			: reservationSlice.getContent().getFirst();

		if (nextReservation == null) {
			return null;
		}

		return new ReservationNextDTO(
			member.getName(),
			toListItemDTO(nextReservation)
		);
	}

	@Override
	public PageResponseDTO<ReservationListItemDTO, Reservation> getAdminReservationList(Long memberId, int page,
		int listSize, ReservationListFilter filter, Long stayId) {
		hostMemberRepository.findById(memberId).orElseThrow(
			() -> new EntityNotFoundException("해당 호스트를 찾을 수 없습니다.")
		);

		Pageable pageable = PageRequest.of(page - 1, listSize);
		Slice<Reservation> reservationSlice = reservationQDslRepository.findAdminList(memberId, filter, stayId,
			pageable);

		return new PageResponseDTO<>(reservationSlice, this::toListItemDTO);
	}

	private ReservationCreateResponseDTO toCreateResponseDTO(Reservation reservation) {
		return new ReservationCreateResponseDTO(
			reservation.getId(),
			StaySummaryDTO.ofBase(reservation.getStay(), publicBaseUrl),
			ReservationInfoDTO.ofDatesGuest(reservation.getStartDate(), reservation.getEndDate(),
				reservation.getPersonCnt()),
			reservation.getResrvStatus()
		);
	}

	private ReservationConfirmResponseDTO toConfirmResponseDTO(Reservation reservation) {
		LocalDate today = LocalDate.now();
		boolean inRange = !today.isBefore(reservation.getStartDate()) && !today.isAfter(reservation.getEndDate());
		long dDay = ChronoUnit.DAYS.between(today, reservation.getStartDate());

		if (inRange) { // [start, end]
			reservation.setVisitStatus(VisitStatus.IN_PROGRESS);
		} else if (dDay > 0) {
			reservation.setVisitStatus(VisitStatus.UPCOMING);
		} else if (dDay < 0) {
			reservation.setVisitStatus(VisitStatus.COMPLETED);
		}
		reservationRepository.save(reservation);

		return new ReservationConfirmResponseDTO(
			reservation.getId(),
			reservation.getStay().getId(),
			reservation.getResrvStatus(),
			reservation.getVisitStatus(),
			dDay,
			reservation.getReservedAt()
		);
	}

	private ReservationDetailResponseDTO toDetailResponseDTO(Reservation reservation) {
		return new ReservationDetailResponseDTO(
			reservation.getId(),
			reservation.getResrvStatus(),
			reservation.getMember().getName(),
			reservation.getMember().getPhone(),
			reservation.getStay().getIsHomestay(),
			reservation.getStay().getHostName(),
			reservation.getStay().getHostPhone(),
			StaySummaryDTO.ofFull(reservation.getStay(), publicBaseUrl),
			ReservationInfoDTO.ofAll(
				reservation.getStartDate(), reservation.getEndDate(), reservation.getPersonCnt(),
				reservation.getIsFarm()
			)
		);
	}

	private ReservationListItemDTO toListItemDTO(Reservation reservation) {
		LocalDate today = LocalDate.now();
		long dDay = ChronoUnit.DAYS.between(today, reservation.getStartDate());

		return new ReservationListItemDTO(
			reservation.getId(),
			publicBaseUrl + "/" + reservation.getStay().getImages().getFirst().getS3Key(),
			reservation.getStay().getTitle(),
			ReservationViewStatus.from(reservation.getResrvStatus(), reservation.getVisitStatus()),
			dDay,
			ReservationInfoDTO.ofDates(reservation.getStartDate(), reservation.getEndDate())
		);
	}

}
