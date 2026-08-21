package com.sido.backend.stay.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sido.backend.common.dto.PageResponseDTO;
import com.sido.backend.common.exception.ConflictException;
import com.sido.backend.common.exception.ForbiddenException;
import com.sido.backend.member.entity.HostMember;
import com.sido.backend.member.repository.HostMemberRepository;
import com.sido.backend.reservation.repository.ReservationDayRepository;
import com.sido.backend.reservation.repository.ReservationRepository;
import com.sido.backend.stay.dto.AvailDatesDTO;
import com.sido.backend.stay.dto.OpenAndReservedDatesDTO;
import com.sido.backend.stay.dto.StayCreateDTO;
import com.sido.backend.stay.dto.StayDeleteDTO;
import com.sido.backend.stay.dto.StayResponseDTO;
import com.sido.backend.stay.dto.StayResponseDetailDTO;
import com.sido.backend.stay.dto.StayResrvStatus;
import com.sido.backend.stay.dto.StaySpecDTO;
import com.sido.backend.stay.dto.StayUpdateDTO;
import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.entity.StayAvailDate;
import com.sido.backend.stay.entity.StayImage;
import com.sido.backend.stay.event.StayDetailChangedEvent;
import com.sido.backend.stay.repository.StayAvailDateRepository;
import com.sido.backend.stay.repository.StayImageRepository;
import com.sido.backend.stay.repository.StayRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

@Slf4j
@RequiredArgsConstructor
@Service
public class StayServiceImpl implements StayService {
	private final S3Client s3;
	private final StayRepository stayRepository;
	private final StayImageRepository stayImageRepository;
	private final StayAvailDateRepository stayAvailDateRepository;
	private final ReservationDayRepository reservationDayRepository;
	private final ReservationRepository reservationRepository;
	private final HostMemberRepository hostMemberRepository;
	private final StayDetailCacheService stayDetailCacheService;
	private final ApplicationEventPublisher eventPublisher;
	@Value("${app.s3.publicBaseUrl}")
	private String publicBaseUrl;
	@Value("${app.s3.bucket}")
	private String bucket;

	@Override
	public PageResponseDTO<StayResponseDTO, Stay> getStays(int page, int listSize,
		boolean isHomestay, String address, LocalDate startDate, LocalDate endDate, Integer capacity) {

		Slice<Object[]> stays = stayRepository.findStaysDynamically(
			isHomestay, address, startDate, endDate, capacity,
			PageRequest.of(page - 1, listSize, Sort.by(Sort.Order.desc("id")))
		);

		return new PageResponseDTO<>(stays, this::toResponseDTO);
	}

	@Override
	public PageResponseDTO<StayResponseDTO, Stay> getStaysByHost(
		Long memberId,
		Pageable pageable,
		StayResrvStatus statusFilter
	) {
		Slice<Object[]> rawSlice = stayRepository.findByHostWithStatus(memberId, pageable);

		// 예약 상태 필터 (예약 가능, 예약 마감, 예약 닫힘)
		Slice<Object[]> filteredSlice = new SliceImpl<>(
			rawSlice.stream()
				.filter(tuple -> statusFilter == null || tuple[1] == statusFilter)
				.toList(),
			rawSlice.getPageable(),
			rawSlice.hasNext()
		);

		return new PageResponseDTO<>(filteredSlice, this::toResponseDTO);
	}

	@Override
	@Transactional
	public StayResponseDetailDTO addStay(long memberId, StayCreateDTO stayCreateDTO) {
		HostMember host = hostMemberRepository.findById(memberId).orElseThrow(
			() -> new EntityNotFoundException("해당 호스트를 찾을 수 없습니다.")
		);

		if (stayRepository.existsByAddressAndDetailAddress(
			stayCreateDTO.getAddress(),
			stayCreateDTO.getDetailAddress())) {
			throw new IllegalArgumentException("이미 등록된 주소입니다.");
		}

		// 사랑방 개수 증가 (HostMember 업데이트)
		host.incrementStayCount();

		Stay stay = stayCreateDTO.toEntity();
		stay.setHost(host);

		//{마을 이름} + 사랑방 + {사랑방 개수} + 호
		stay.setTitle(host.getVillageName() + " 사랑방 " + host.getStayCount() + "호");

		stayRepository.save(stay);

		//s3 버킷 temp에 있던 파일들 옮기는 과정
		List<String> tempKeys = Optional.ofNullable(stayCreateDTO.getS3Keys()).orElse(List.of());
		List<String> copiedKeys = new ArrayList<>();

		if (!tempKeys.isEmpty()) {
			String ym = YearMonth.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMM"));

			try {
				for (String srcKey : tempKeys) {
					validateTempKey(srcKey);

					String destKey = buildFinalKey(stay.getId(), ym, srcKey);
					copyWithinBucket(srcKey, destKey);
					copiedKeys.add(destKey);

					StayImage img = new StayImage();
					img.setStay(stay);
					img.setS3Key(destKey);
					stayImageRepository.save(img);
				}
			} catch (Exception ex) {
				// 보상(가능한 한 복구)
				for (String k : copiedKeys)
					safeDelete(k);
				throw ex; // 트랜잭션 롤백 → Stay/StayImage 롤백
			}
			deleteMany(tempKeys);
		}
		return stayDetailCacheService.toResponseDetailDTO(stay);
	}

	@Override
	@Transactional
	public StayUpdateDTO editStay(long stayId, long memberId, StayUpdateDTO stayDTO) {
		Stay stay = stayRepository.findById(stayId).orElseThrow(
			() -> new EntityNotFoundException("해당 사랑방을 찾을 수 없습니다.")
		);
		stay.setCapacity(stayDTO.capacity());
		stay.setAreaSize(stayDTO.areaSize());
		stay.setDescription(stayDTO.description());

		StayUpdateDTO result = toEditDTO(stayRepository.save(stay));
		// 커밋 이후 무효화: 이벤트만 발행하고 실제 evict 는 AFTER_COMMIT 리스너가 수행한다.
		// 롤백 시 리스너가 실행되지 않아 정상 캐시가 불필요하게 사라지지 않는다.
		eventPublisher.publishEvent(new StayDetailChangedEvent(stayId));
		return result;
	}

	@Override
	public AvailDatesDTO getAvailableDates(Long stayId) {
		stayRepository.findById(stayId).orElseThrow(
			() -> new EntityNotFoundException("해당 사랑방을 찾을 수 없습니다.")
		);

		List<LocalDate> availableDates = stayAvailDateRepository.findOpenAndUnreservedOnAfter(stayId, LocalDate.now());

		return new AvailDatesDTO(availableDates);
	}

	@Override
	public StayResponseDetailDTO getStayDetail(Long stayId, LocalDate startDate, LocalDate endDate) {
		// 정적 정보는 캐시에서 (미스 시 DB 조회 후 적재). 존재하지 않는 stayId면 여기서 404
		StayResponseDetailDTO stayResponseDetailDTO = stayDetailCacheService.getDetailBase(stayId);

		StayResrvStatus status;

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		boolean isAdmin = authentication.getAuthorities().stream()
			.anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

		// 예약 상태는 가용성 파생 데이터라 캐싱하지 않고 매 요청 DB 기준으로 조회
		if (isAdmin) {
			status = stayRepository.findResrvStatusByStayIdForHost(stayId);
		} else {
			status = stayRepository.findResrvStatusInRangeByStayId(stayId, startDate, endDate);
		}

		// Redis 캐시는 조회마다 역직렬화된 새 복사본을 반환하므로 이 세터가 캐시 원본을 오염시키지 않는다
		// (로컬 캐시였다면 공유 객체 변조가 되므로 이 구조를 쓰면 안 됨)
		stayResponseDetailDTO.setStayResrvStatus(status);

		return stayResponseDetailDTO;
	}

	@Override
	@Transactional
	public StayDeleteDTO deleteStay(Long memberId, Long stayId) {
		Stay stay = stayRepository.findById(stayId).orElseThrow(
			() -> new EntityNotFoundException("해당 사랑방을 찾을 수 없습니다.")
		);
		HostMember hostMember = hostMemberRepository.findById(memberId).orElseThrow(
			() -> new EntityNotFoundException("해당 호스트를 찾을 수 없습니다.")
		);

		if (!stay.getHost().equals(hostMember)) {
			throw new ForbiddenException("사랑방을 등록한 시골 관리자만 사랑방을 삭제할 수 있습니다.");
		}

		if (!Boolean.TRUE.equals(stay.getIsActive())) { // 이미 삭제된 사랑방
			return new StayDeleteDTO(false, false);
		}

		boolean hasUpcoming = reservationRepository.existsUpcomingByStay(stayId);
		if (hasUpcoming) {
			return new StayDeleteDTO(false, true);
		}

		stay.setIsActive(false);
		stayRepository.save(stay);
		int deletedAvailDatesCnt = stayAvailDateRepository.deleteStayAvailDatesOnAfter(stayId, LocalDate.now());
		log.info("deletedAvailDatesCnt = {}", deletedAvailDatesCnt);

		// 커밋 이후 무효화: 커밋 전 evict 후 재조회가 아직 커밋 안 된 isDeleted=false 를 재적재하던 공백을 막는다.
		eventPublisher.publishEvent(new StayDetailChangedEvent(stayId));

		return new StayDeleteDTO(true, false);
	}

	@Override
	public OpenAndReservedDatesDTO getOpenAndReservedDates(Long stayId) {
		stayRepository.findById(stayId).orElseThrow(
			() -> new EntityNotFoundException("해당 사랑방을 찾을 수 없습니다.")
		);

		List<LocalDate> openDates = stayAvailDateRepository.findOpenOnAfter(stayId, LocalDate.now()); // 오픈된 날짜들(오늘 이후만)
		List<LocalDate> reservedDates = reservationDayRepository.findAllReserved(stayId); // 예약된 날짜들 (과거 포함 전체)

		return new OpenAndReservedDatesDTO(openDates, reservedDates);
	}

	@Override
	@Transactional
	public OpenAndReservedDatesDTO updateOpenDates(Long stayId, List<LocalDate> dates) {
		Stay stay = stayRepository.findById(stayId).orElseThrow(
			() -> new EntityNotFoundException("해당 사랑방을 찾을 수 없습니다.")
		);

		// 1) 업데이트할 날짜들 가공
		// 오늘 이전은 변경 불가
		List<LocalDate> openDatesBeforeToday = stayAvailDateRepository.findOpenBefore(stayId, LocalDate.now());
		log.debug("openDatesBeforeToday = {}", openDatesBeforeToday);
		// 오늘 이후만 가져오기
		List<LocalDate> openDatesAfterToday = dates.stream()
			.filter(d -> !d.isBefore(LocalDate.now()))
			.toList();
		log.debug("openDatesAfterToday = {}", openDatesAfterToday);

		List<LocalDate> updateDates = Stream.concat(openDatesBeforeToday.stream(), openDatesAfterToday.stream())
			.toList();
		log.debug("updateDates = {}", updateDates);

		// 2) 예약된 날짜 삭제 불가 검증
		// 현재 오픈일 전체
		List<LocalDate> current = stayAvailDateRepository.findAllDatesByStayId(stayId);

		// 삭제될 날짜 = 현재 - 요청
		List<LocalDate> toDelete = current.stream()
			.filter(d -> !updateDates.contains(d))
			.toList();

		List<LocalDate> reservedWillBeDeleted =
			toDelete.isEmpty() ? List.of()
				: reservationDayRepository.findReservedDatesIn(stayId, toDelete);

		if (!reservedWillBeDeleted.isEmpty()) {
			throw new ConflictException("이미 예약된 날짜는 삭제할 수 없습니다: " + reservedWillBeDeleted);
		}

		// 3) 현재 오픈 날짜 전체 삭제
		int deletedCnt = stayAvailDateRepository.deleteAllByStayId(stayId);
		log.info("updateOpenDates: deletedCnt = {}", deletedCnt);

		// 4) 가공된 업데이트 날짜들 insert
		List<StayAvailDate> stayAvailDates = updateDates.stream()
			.map(d -> StayAvailDate.builder()
				.availableDate(d)
				.stay(stay)
				.build()
			).toList();

		stayAvailDateRepository.saveAll(stayAvailDates);

		List<LocalDate> openDates = stayAvailDateRepository.findOpenOnAfter(stayId,
			LocalDate.now()); // 오픈된 날짜들 (오늘 이후만)
		List<LocalDate> reserveDates = reservationDayRepository.findAllReserved(stayId); // 예약된 날짜들 (과거 포함 전체)

		return new OpenAndReservedDatesDTO(openDates, reserveDates);
	}

	private StayResponseDTO toResponseDTO(Object[] tuple) {
		Stay stay = (Stay)tuple[0];
		StayResrvStatus status = (StayResrvStatus)tuple[1];
		String firstImageURL = publicBaseUrl + "/" + stay.getImages().getFirst().getS3Key();

		return StayResponseDTO.builder()
			.id(stay.getId())
			.title(stay.getTitle())
			.address(stay.getAddress())
			.isHomestay(stay.getIsHomestay())
			.stayResrvStatus(status)
			.imageURL(firstImageURL)
			.hostName(stay.getHostName())
			.build();
	}

	private StayUpdateDTO toEditDTO(Stay stay) {
		StaySpecDTO spec = new StaySpecDTO(
			stay.getCapacity(),
			stay.getAreaSize(),
			stay.getDescription()
		);

		return StayUpdateDTO.builder()
			.staySpec(spec)
			.build();
	}

	//s3 관련 함수들
	private void validateTempKey(String key) {
		if (key == null || !key.startsWith("temp/") || key.contains("..")) {
			throw new IllegalArgumentException("잘못된 이미지 키");
		}
	}

	private void copyWithinBucket(String srcKey, String destKey) {
		s3.copyObject(CopyObjectRequest.builder()
			.sourceBucket(bucket)
			.sourceKey(srcKey)
			.destinationBucket(bucket)
			.destinationKey(destKey)
			.build());
	}

	private String buildFinalKey(Long stayId, String yyyyMM, String srcKey) {
		// srcKey 예: temp/202509/abc-uuid.jpg
		String filename = srcKey.substring(srcKey.lastIndexOf('/') + 1); // abc-uuid.jpg
		return "stays/%d/%s/%s".formatted(stayId, yyyyMM, filename);
	}

	private void safeDelete(String key) {
		try {
			s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
		} catch (Exception ignore) {
		}
	}

	private void deleteMany(List<String> keys) {
		if (keys.isEmpty())
			return;
		s3.deleteObjects(DeleteObjectsRequest.builder()
			.bucket(bucket)
			.delete(Delete.builder()
				.objects(keys.stream()
					.map(k -> ObjectIdentifier.builder().key(k).build())
					.toList())
				.build())
			.build());
	}
}
