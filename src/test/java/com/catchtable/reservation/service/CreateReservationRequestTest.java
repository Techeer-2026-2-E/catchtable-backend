package com.catchtable.reservation.service;

import com.catchtable.reservation.dto.CreateReservationRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateReservationRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void partySizeMustBePositive() {
        OffsetDateTime startAt = OffsetDateTime.parse("2026-09-30T12:00:00+09:00");

        assertTrue(validator.validate(new CreateReservationRequest(1L, startAt, 1)).isEmpty());
        assertTrue(validator.validate(new CreateReservationRequest(1L, startAt, 0)).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("partySize")));
    }
}
