package com.catchtable.reservation.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.dto.RefundPreviewResponse;
import com.catchtable.reservation.dto.ReservationHoldResponse;
import com.catchtable.reservation.dto.ReservationResponse;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreTable;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
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
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final ReservationRefundService refundService;

    public ReservationResponse getReservation(Long memberId, Long reservationId) {
        return ReservationResponse.from(reservationRepository.findByIdAndMember_Id(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND)), OffsetDateTime.now(clock));
    }

    // 예약 변경과 누적 환불액을 같은 DB 스냅샷에서 읽는다. 조회는 어떤 상태도 바꾸지 않는다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RefundPreviewResponse getRefundPreview(Long memberId, Long reservationId) {
        Reservation reservation = reservationRepository.findByIdAndMember_Id(reservationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (reservation.getStatus() != ReservationStatus.CONFIRMED || reservation.getCheckedInAt() != null
                || !now.isBefore(reservation.getReservationStartAt())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }

        return refundService.preview(reservation, now);
    }

    @Transactional
    public ReservationResponse createReservation(Long memberId, CreateReservationRequest request) {
        return ReservationResponse.from(allocate(memberId, request, false), OffsetDateTime.now(clock));
    }

    @Transactional
    public ReservationHoldResponse createHold(Long memberId, CreateReservationRequest request) {
        return ReservationHoldResponse.from(allocate(memberId, request, true), OffsetDateTime.now(clock));
    }

    @Transactional
    public ReservationHoldResponse getHold(Long memberId, Long holdId) {
        Reservation reservation = findHoldForUpdate(memberId, holdId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        reservation.expireHold(now);
        return ReservationHoldResponse.from(reservation, now);
    }

    @Transactional
    public void releaseHold(Long memberId, Long holdId) {
        Reservation reservation = findHoldForUpdate(memberId, holdId);
        try {
            reservation.releaseHold(OffsetDateTime.now(clock));
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }
    }

    private Reservation findHoldForUpdate(Long memberId, Long holdId) {
        return reservationRepository.findByIdAndMemberIdForUpdate(holdId, memberId)
                .filter(found -> found.getHoldExpiresAt() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
    }

    private Reservation allocate(Long memberId, CreateReservationRequest request, boolean hold) {
        if (request.partySize() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "예약 인원은 1명 이상이어야 합니다.");
        }

        Member member = entityManager.find(Member.class, memberId);
        if (member == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");
        }
        Store store = storeRepository.findByIdForShare(request.storeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "매장을 찾을 수 없습니다."));

        // 설정 변경을 기다렸다면, 잠금을 얻은 시각으로 예약 마감 여부를 다시 판단한다.
        OffsetDateTime now = OffsetDateTime.now(clock);
        availabilityService.validateBookable(store, request.reservationStartAt(), now);
        OffsetDateTime reservationEndAt = request.reservationStartAt()
                .plusMinutes(store.getReservationDurationMinutes());

        // 주기 정리 전에도 만료 선점을 반환하고 재배정한다.
        reservationRepository.expireHolds(store.getId(), now);

        // shortcut: 잠긴 마지막 테이블은 409를 반환한다. 충돌이 잦아지면 짧은 제한 재시도를 추가한다.
        StoreTable table = storeTableRepository.findAvailableForUpdate(
                        store.getId(),
                        request.partySize(),
                        request.reservationStartAt(),
                        reservationEndAt,
                        null
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "예약 가능한 테이블이 없습니다."));

        // 조회 직후 발생한 동시 예약도 DB의 시간 중복 제약에서 최종 차단한다.
        try {
            Reservation reservation = hold
                    ? Reservation.held(member, store, table.getId(), request.reservationStartAt(),
                            reservationEndAt, request.partySize(), now)
                    : Reservation.confirmed(member, store, table.getId(), request.reservationStartAt(),
                            reservationEndAt, request.partySize(), now);
            return reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "예약 가능한 테이블이 없습니다.", exception);
        }
    }

    @Transactional
    public ReservationResponse cancelReservation(Long memberId, Long reservationId)
    {
        Reservation reservation=reservationRepository.findByIdAndMemberIdForUpdate(reservationId, memberId)
                .orElseThrow(()->new BusinessException(ErrorCode.RESERVATION_NOT_FOUND));
        if(reservation.getStatus()== ReservationStatus.CANCELLED)
        {
            return ReservationResponse.from(reservation, OffsetDateTime.now(clock));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        try{
            reservation.cancelByCustomer(now);
        }
        catch(IllegalStateException exception)
        {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }
        refundService.requireRefundCompleted(reservation, CancellationActor.CUSTOMER, now);
        eventPublisher.publishEvent(new CustomerNotificationEvent(
                memberId,
                NotificationType.RESERVATION_CANCELED,
                reservation.getId(),
                "예약이 취소되었습니다"
        ));
        return ReservationResponse.from(reservation, OffsetDateTime.now(clock));
    }


    @Scheduled(fixedDelayString = "${reservation.completion-interval-ms:60000}")
    @Transactional
    public int completeFinishedReservations() {
        return reservationRepository.completeVisitedReservations(OffsetDateTime.now(clock));
    }

    @Scheduled(fixedDelayString = "${reservation.hold-expiry-interval-ms:60000}")
    @Transactional
    public int expireHolds() {
        return reservationRepository.expireHolds(null, OffsetDateTime.now(clock));
    }

}
