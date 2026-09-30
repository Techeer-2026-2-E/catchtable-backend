package com.catchtable.reservation.controller;

import com.catchtable.reservation.dto.AvailabilityResponse;
import com.catchtable.reservation.dto.AvailableTableCountResponse;
import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.dto.ReservationResponse;
import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.service.OwnerReservationService;
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

@WebMvcTest({ReservationController.class, OwnerReservationController.class, StoreAvailabilityController.class})
class ReservationControllerContractTest {

    private static final OffsetDateTime START_AT = OffsetDateTime.parse("2030-01-02T10:00:00+09:00");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReservationService reservationService;

    @MockBean
    private OwnerReservationService ownerReservationService;

    @MockBean
    private ReservationAvailabilityService availabilityService;

    @Test
    void availabilityRoutesReturnCountsWithoutExposingTableChoice() throws Exception {
        LocalDate date = LocalDate.parse("2030-01-02");
        given(availabilityService.getAvailability(3L, date, 2))
                .willReturn(new AvailabilityResponse(3L, date, 2,
                        List.of(new AvailabilityResponse.Slot(START_AT, 1, true))));
        given(availabilityService.getAvailableTableCount(3L, START_AT, 2))
                .willReturn(new AvailableTableCountResponse(3L, START_AT, 2, 1));

        mockMvc.perform(get("/api/user/stores/3/availability")
                        .queryParam("date", date.toString()).queryParam("partySize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots[0].availableTableCount").value(1))
                .andExpect(jsonPath("$.slots[0].available").value(true));

        mockMvc.perform(get("/api/user/stores/3/tables")
                        .queryParam("reservationStartAt", START_AT.toString()).queryParam("partySize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTableCount").value(1));

        mockMvc.perform(get("/api/user/stores/3/availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."));
    }

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

    private static OwnerReservationResponse ownerResponse(ReservationStatus status, String reason) {
        OffsetDateTime cancelledAt = status == ReservationStatus.CANCELLED ? START_AT.minusDays(1) : null;
        OffsetDateTime noShowAt = status == ReservationStatus.NO_SHOW ? START_AT.plusMinutes(10) : null;
        return new OwnerReservationResponse(
                10L,
                3L,
                1L,
                "테스트 고객",
                "010-0000-0001",
                7L,
                START_AT,
                START_AT.plusHours(2),
                2,
                status,
                null,
                null,
                cancelledAt,
                status == ReservationStatus.CANCELLED ? CancellationActor.OWNER : null,
                status == ReservationStatus.CANCELLED ? reason : null,
                noShowAt,
                status == ReservationStatus.NO_SHOW ? 2L : null,
                status == ReservationStatus.NO_SHOW ? reason : null
        );
    }
}
