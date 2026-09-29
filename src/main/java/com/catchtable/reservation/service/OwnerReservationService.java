package com.catchtable.reservation.service;

import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
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







}
