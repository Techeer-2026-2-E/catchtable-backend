package com.catchtable.reservation.service;

import com.catchtable.member.entity.Member;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.dto.ReservationResponse;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreTable;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final StoreRepository storeRepository;
    private final StoreTableRepository storeTableRepository;
    private final ReservationAvailabilityService availabilityService;
    private final EntityManager entityManager;

    @Transactional
    public ReservationResponse createReservation(Long memberId, CreateReservationRequest request) {
        if (request.partySize() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "예약 인원은 1명 이상이어야 합니다.");
        }

        Member member = entityManager.find(Member.class, memberId);
        if (member == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");
        }
        Store store = storeRepository.findById(request.storeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "매장을 찾을 수 없습니다."));

        OffsetDateTime now = OffsetDateTime.now();
        availabilityService.validateBookable(store, request.reservationStartAt(), now);
        OffsetDateTime reservationEndAt = request.reservationStartAt()
                .plusMinutes(store.getReservationDurationMinutes());

        // ponytail: 충돌 중인 마지막 테이블은 즉시 409를 반환한다. 필요해지면 짧은 제한 재시도를 추가한다.
        StoreTable table = storeTableRepository.findAvailableForUpdate(
                        store.getId(),
                        request.partySize(),
                        request.reservationStartAt(),
                        reservationEndAt
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "예약 가능한 테이블이 없습니다."));

        // 조회 직후 발생한 동시 예약도 DB의 시간 중복 제약에서 최종 차단한다.
        try {
            return ReservationResponse.from(reservationRepository.saveAndFlush(Reservation.confirmed(
                    member,
                    store,
                    table.getId(),
                    request.reservationStartAt(),
                    reservationEndAt,
                    request.partySize(),
                    now
            )));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "예약 가능한 테이블이 없습니다.", exception);
        }
    }


    @Scheduled(fixedDelayString = "${reservation.completion-interval-ms:60000}")
    @Transactional
    public int completeFinishedReservations() {
        return reservationRepository.completeVisitedReservations(OffsetDateTime.now());
    }

}
