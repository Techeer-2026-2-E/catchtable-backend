package com.catchtable.reservation.service;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    public List<OwnerReservationResponse> getReservations(
            Long ownerId,
            Long storeId,
            LocalDate date,
            ReservationStatus status
    ) {
        OffsetDateTime start = date.atStartOfDay(STORE_ZONE).toOffsetDateTime();
        OffsetDateTime end = date.plusDays(1).atStartOfDay(STORE_ZONE).toOffsetDateTime();

        return reservationRepository.findAllForOwner(ownerId, storeId, start, end, status)
                .stream()
                .map(OwnerReservationResponse::from)
                .toList();
    }

    public OwnerReservationResponse getReservation(Long ownerId, Long reservationId) {
        return OwnerReservationResponse.from(
                reservationRepository.findByIdAndStore_Owner_Id(reservationId, ownerId)
                        .orElseThrow(OwnerReservationService::notFound)
        );
    }

    @Transactional
    public OwnerReservationResponse cancelReservation(Long ownerId, Long reservationId, String reason) {
        Reservation reservation = findForUpdate(ownerId, reservationId);
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return OwnerReservationResponse.from(reservation);
        }
        try {
            reservation.cancelByOwner(OffsetDateTime.now(), reason);
        } catch (IllegalStateException exception) {
            throw conflict(exception);
        }
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                reservation.getMember().getId(),
                NotificationType.RESERVATION_CANCELED,
                reservation.getId(),
                "점주가 예약을 취소했습니다. 사유: " + reservation.getCancellationReason()
        ));
        return OwnerReservationResponse.from(reservation);
    }

    @Transactional
    public OwnerReservationResponse confirmVisit(Long ownerId, Long reservationId) {
        Reservation reservation = findForUpdate(ownerId, reservationId);
        try {
            reservation.confirmVisit(OffsetDateTime.now(), ownerId);
        } catch (IllegalStateException exception) {
            throw conflict(exception);
        }
        return OwnerReservationResponse.from(reservation);
    }

    @Transactional
    public OwnerReservationResponse markNoShow(Long ownerId, Long reservationId, String reason) {
        Reservation reservation = findForUpdate(ownerId, reservationId);
        try {
            reservation.markNoShow(
                    OffsetDateTime.now(),
                    ownerId,
                    reservation.getStore().getArrivalGraceMinutes(),
                    reason
            );
        } catch (IllegalStateException exception) {
            throw conflict(exception);
        }
        return OwnerReservationResponse.from(reservation);
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
