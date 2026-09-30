package com.catchtable.reservation.controller;

import com.catchtable.reservation.dto.OwnerReservationResponse;
import com.catchtable.reservation.entity.CancellationActor;
import com.catchtable.reservation.entity.ReservationStatus;
import com.catchtable.reservation.service.OwnerReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void detailReturnsOneOwnedReservation() throws Exception {
        OffsetDateTime startAt = OffsetDateTime.parse("2030-01-02T10:00:00+09:00");
        given(ownerReservationService.getReservation(2L, 10L)).willReturn(new OwnerReservationResponse(
                10L, 3L, 1L, "테스트 고객", "010-0000-0001", 7L,
                startAt, startAt.plusHours(2), 2, ReservationStatus.CONFIRMED,
                null, null, null, null, null, null, null, null));

        mockMvc.perform(get("/api/owner/reservations/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(10))
                .andExpect(jsonPath("$.customerPhone").value("010-0000-0001"));
    }

    @Test
    void cancellationRequiresReasonAndReturnsOwnerActor() throws Exception {
        OffsetDateTime startAt = OffsetDateTime.parse("2030-01-02T10:00:00+09:00");
        given(ownerReservationService.cancelReservation(2L, 10L, "매장 사정"))
                .willReturn(new OwnerReservationResponse(
                        10L, 3L, 1L, "테스트 고객", "010-0000-0001", 7L,
                        startAt, startAt.plusHours(2), 2, ReservationStatus.CANCELLED,
                        null, null, startAt.minusDays(1), CancellationActor.OWNER, "매장 사정",
                        null, null, null));

        mockMvc.perform(post("/api/owner/reservations/10/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"매장 사정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationActor").value("OWNER"));

        mockMvc.perform(post("/api/owner/reservations/10/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void visitConfirmationReturnsCheckInActor() throws Exception {
        OffsetDateTime startAt = OffsetDateTime.parse("2030-01-02T10:00:00+09:00");
        given(ownerReservationService.confirmVisit(2L, 10L)).willReturn(new OwnerReservationResponse(
                10L, 3L, 1L, "테스트 고객", "010-0000-0001", 7L,
                startAt, startAt.plusHours(2), 2, ReservationStatus.CONFIRMED,
                startAt, 2L, null, null, null, null, null, null));

        mockMvc.perform(post("/api/owner/reservations/10/enter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.checkedInByMemberId").value(2));
    }

    @Test
    void noShowRequiresReasonAndReturnsRecordedActor() throws Exception {
        OffsetDateTime startAt = OffsetDateTime.parse("2030-01-02T10:00:00+09:00");
        given(ownerReservationService.markNoShow(2L, 10L, "미방문"))
                .willReturn(new OwnerReservationResponse(
                        10L, 3L, 1L, "테스트 고객", "010-0000-0001", 7L,
                        startAt, startAt.plusHours(2), 2, ReservationStatus.NO_SHOW,
                        null, null, null, null, null, startAt.plusMinutes(10), 2L, "미방문"));

        mockMvc.perform(post("/api/owner/reservations/10/no-show")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"미방문\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_SHOW"))
                .andExpect(jsonPath("$.noShowByMemberId").value(2));

        mockMvc.perform(post("/api/owner/reservations/10/no-show")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
    }
}
