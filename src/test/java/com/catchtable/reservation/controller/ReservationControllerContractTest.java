package com.catchtable.reservation.controller;

import com.catchtable.reservation.dto.ReservationResponse;
import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.service.ReservationService;
import com.catchtable.reservation.service.ReservationAvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ReservationController.class})
class ReservationControllerContractTest {

    private static final OffsetDateTime START_AT = OffsetDateTime.parse("2030-01-02T10:00:00+09:00");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReservationService reservationService;

    @MockBean
    private ReservationAvailabilityService availabilityService;


    @Test
    void customerCanCreateAnAutomaticallyConfirmedReservation() throws Exception {
        given(reservationService.createReservation(eq(1L), any()))
                .willReturn(customerResponse(ReservationStatus.CONFIRMED, null, null));

        mockMvc.perform(post("/api/user/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeId": 3,
                                  "reservationStartAt": "2030-01-02T10:00:00+09:00",
                                  "partySize": 2
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }


    @Test
    void invalidRequestsReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/user/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeId": 3,
                                  "reservationStartAt": "2030-01-02T10:00:00+09:00",
                                  "partySize": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."));


    }

    @Test
    void unsupportedRequestContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/user/reservations")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void serviceErrorsKeepTheirHttpStatus() throws Exception {
        given(reservationService.createReservation(eq(1L), any()))
                .willThrow(new ResponseStatusException(HttpStatus.CONFLICT, "예약 가능한 테이블이 없습니다."));

        mockMvc.perform(post("/api/user/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storeId": 3,
                                  "reservationStartAt": "2030-01-02T10:00:00+09:00",
                                  "partySize": 2
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("예약 가능한 테이블이 없습니다."));
    }


    private static ReservationResponse customerResponse(
            ReservationStatus status,
            CancellationActor cancellationActor,
            String cancellationReason
    ) {
        OffsetDateTime cancelledAt = status == ReservationStatus.CANCELLED ? START_AT.minusDays(1) : null;
        return new ReservationResponse(
                10L,
                3L,
                "테스트 식당",
                7L,
                START_AT,
                START_AT.plusHours(2),
                2,
                status,
                START_AT.minusDays(2),
                START_AT.minusDays(2),
                cancelledAt,
                cancellationActor,
                cancellationReason
        );
    }

}
