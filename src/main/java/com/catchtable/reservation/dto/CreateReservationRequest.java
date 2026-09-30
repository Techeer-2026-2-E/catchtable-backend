package com.catchtable.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

/** 시작 시각은 오프셋을 포함해 보내고, 물리 테이블 ID는 보내지 않는다. 서버가 적합한 테이블을 배정한다. */
public record CreateReservationRequest(
        @NotNull @Positive Long storeId,
        @NotNull OffsetDateTime reservationStartAt,
        @Positive int partySize
) {
}
