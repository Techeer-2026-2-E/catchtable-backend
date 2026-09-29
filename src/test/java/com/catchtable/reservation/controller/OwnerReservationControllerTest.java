package com.catchtable.reservation.controller;

import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.service.OwnerReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerReservationController.class)
class OwnerReservationControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private OwnerReservationService ownerReservationService;

    @Test
    void listRequiresDateAndReturnsFilteredReservations() throws Exception {
        LocalDate date = LocalDate.parse("2030-01-02");
        OffsetDateTime startAt = OffsetDateTime.parse("2030-01-02T10:00:00+09:00");
        OwnerReservationResponse response = new OwnerReservationResponse(
                10L, 3L, 1L, "테스트 고객", "010-0000-0001", 7L,
                startAt, startAt.plusHours(2), 2, ReservationStatus.CONFIRMED,
                null, null, null, null, null, null, null, null);
        given(ownerReservationService.getReservations(2L, 3L, date, ReservationStatus.CONFIRMED))
                .willReturn(List.of(response));

        mockMvc.perform(get("/api/owner/stores/3/reservations")
                        .queryParam("date", date.toString()).queryParam("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reservationId").value(10))
                .andExpect(jsonPath("$[0].customerName").value("테스트 고객"));

        mockMvc.perform(get("/api/owner/stores/3/reservations"))
                .andExpect(status().isBadRequest());
    }
}
