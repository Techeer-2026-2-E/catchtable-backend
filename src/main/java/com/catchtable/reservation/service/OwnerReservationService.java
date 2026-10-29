package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.dto.OwnerReservationUpdateRequest;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreTable;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerReservationService {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    private final ReservationRepository reservationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MemberRepository memberRepository;
    private final StoreRepository storeRepository;
    private final StoreTableRepository tableRepository;
    private final ReservationAvailabilityService availabilityService;
    private final Clock clock;
    private final ReservationRefundService refundService;

    @Transactional
    public OwnerReservationResponse updateReservation(Long ownerId, Long reservationId,
                                                      OwnerReservationUpdateRequest request) {
        if ((request.reservationStartAt() == null && request.partySize() == null)
                || (request.partySize() != null && request.partySize() <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        var owner = memberRepository.findById(ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (owner.getUserType() != UserType.OWNER) {
            throw new BusinessException(ErrorCode.FORBIDDEN_MEMBER_TYPE);
        }
        Long storeId = reservationRepository.findStoreIdByIdAndOwnerId(reservationId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        // 예약 생성과 같은 매장 → 예약 → 테이블 순서로 잠가 설정 변경과 재배정을 함께 보호한다.
        Store store = storeRepository.findByIdForShare(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        Reservation reservation = findForUpdate(ownerId, reservationId);
        refundService.requireNoInFlight(reservation);
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            reservation.validateModifiable();
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_MODIFIABLE);
        }
        OffsetDateTime startAt = request.reservationStartAt() == null
                ? reservation.getReservationStartAt() : request.reservationStartAt();
        int partySize = request.partySize() == null ? reservation.getPartySize() : request.partySize();
        if (startAt.isEqual(reservation.getReservationStartAt()) && partySize == reservation.getPartySize()) {
            return OwnerReservationResponse.from(reservation, now);
        }
        OffsetDateTime endAt = reservation.getReservationEndAt();
        if (!startAt.isEqual(reservation.getReservationStartAt())) {
            availabilityService.validateBookable(store, startAt, now);
            endAt = startAt.plusMinutes(store.getReservationDurationMinutes());
        }
        reservationRepository.expireHolds(storeId, now);
        StoreTable table = tableRepository.findAvailableForUpdate(storeId, partySize, startAt, endAt, reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "변경 가능한 테이블이 없습니다."));
        reservation.changeSchedule(table.getId(), startAt, endAt, partySize, now);
        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "변경 가능한 테이블이 없습니다.", exception);
        }
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                reservation.getMember().getId(), NotificationType.RESERVATION_CHANGED, reservationId,
                "점주가 예약 일시 또는 인원을 변경했습니다. 예약 상세를 확인해 주세요."));
        return OwnerReservationResponse.from(reservation, now);
    }

    public List<OwnerReservationResponse> getReservations(
            Long ownerId,
            Long storeId,
            LocalDate date,
            ReservationStatus status
    ) {
        OffsetDateTime start = date.atStartOfDay(STORE_ZONE).toOffsetDateTime();
        OffsetDateTime end = date.plusDays(1).atStartOfDay(STORE_ZONE).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(clock);

        return reservationRepository.findAllForOwner(ownerId, storeId, start, end, status)
                .stream()
                .map(reservation -> OwnerReservationResponse.from(reservation, now))
                .filter(reservation -> status == null || reservation.status() == status)
                .toList();
    }

    public OwnerReservationResponse getReservation(Long ownerId, Long reservationId) {
        return OwnerReservationResponse.from(
                reservationRepository.findByIdAndStore_Owner_Id(reservationId, ownerId)
                        .orElseThrow(OwnerReservationService::notFound), OffsetDateTime.now(clock)
        );
    }

    @Transactional
    public OwnerReservationResponse cancelReservation(Long ownerId, Long reservationId, String reason) {
        Reservation reservation = findForUpdate(ownerId, reservationId);
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return OwnerReservationResponse.from(reservation, OffsetDateTime.now(clock));
        }
        refundService.requireNoInFlight(reservation);
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            reservation.cancelByOwner(now, reason);
        } catch (IllegalStateException exception) {
            throw conflict(exception);
        }
        refundService.requireRefundCompleted(reservation, CancellationActor.OWNER, now);
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                reservation.getMember().getId(),
                NotificationType.RESERVATION_CANCELED,
                reservation.getId(),
                "점주가 예약을 취소했습니다. 사유: " + reservation.getCancellationReason()
        ));
        return OwnerReservationResponse.from(reservation, OffsetDateTime.now(clock));
    }

    @Transactional
    public OwnerReservationResponse confirmVisit(Long ownerId, Long reservationId) {
        Reservation reservation = findForUpdate(ownerId, reservationId);
        refundService.requireNoInFlight(reservation);
        try {
            reservation.confirmVisit(OffsetDateTime.now(clock), ownerId);
        } catch (IllegalStateException exception) {
            throw conflict(exception);
        }
        return OwnerReservationResponse.from(reservation, OffsetDateTime.now(clock));
    }

    @Transactional
    public OwnerReservationResponse markNoShow(Long ownerId, Long reservationId, String reason) {
        Reservation reservation = findForUpdate(ownerId, reservationId);
        refundService.requireNoInFlight(reservation);
        try {
            reservation.markNoShow(
                    OffsetDateTime.now(clock),
                    ownerId,
                    reservation.getStore().getArrivalGraceMinutes(),
                    reason
            );
        } catch (IllegalStateException exception) {
            throw conflict(exception);
        }
        return OwnerReservationResponse.from(reservation, OffsetDateTime.now(clock));
    }

    private Reservation findForUpdate(Long ownerId, Long reservationId) {
        return reservationRepository.findByIdAndOwnerIdForUpdate(reservationId, ownerId)
                .orElseThrow(OwnerReservationService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다.");
    }

    private static ResponseStatusException conflict(IllegalStateException exception) {
        return new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
    }
}
