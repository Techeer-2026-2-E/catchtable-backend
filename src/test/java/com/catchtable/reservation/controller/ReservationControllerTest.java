package com.catchtable.reservation.controller;

import com.catchtable.reservation.dto.CreateReservationRequest;
import com.catchtable.reservation.dto.ReservationResponse;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReservationService reservationService;

    @Test
    void freeReservationIsImmediatelyConfirmed() throws Exception {
        OffsetDateTime startAt = OffsetDateTime.parse("2030-01-02T12:00:00+09:00");
        when(reservationService.createReservation(eq(1L), any(CreateReservationRequest.class)))
                .thenReturn(new ReservationResponse(1L, 1L, "목 식당", 2L, startAt,
                        startAt.plusHours(2), 2, ReservationStatus.CONFIRMED,
                        startAt.minusDays(1), startAt.minusDays(1), null, null, null));

        mockMvc.perform(post("/api/user/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId":1,"reservationStartAt":"2030-01-02T12:00:00+09:00","partySize":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void rejectsInvalidPartySize() throws Exception {
        mockMvc.perform(post("/api/user/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId":1,"reservationStartAt":"2030-01-02T12:00:00+09:00","partySize":0}
                                """))
                .andExpect(status().isBadRequest());
    }
}
