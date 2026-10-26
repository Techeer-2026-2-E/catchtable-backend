package com.catchtable.store.controller;


import com.catchtable.store.dto.ReservationPolicyRequest;
import com.catchtable.store.dto.ReservationPolicyResponse;
import com.catchtable.store.service.ReservationPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner/stores/{storeId}/reservation-policy")
public class OwnerReservationPolicyController {

    private final ReservationPolicyService reservationPolicyService;

    @GetMapping
    public ResponseEntity<ReservationPolicyResponse> getPolicy(@PathVariable Long storeId)
    {
        return ResponseEntity.ok(reservationPolicyService.getPolicy(storeId));
    }

    @PutMapping
    public ResponseEntity<ReservationPolicyResponse> replacePolicy(
            @PathVariable Long storeId,
            @Valid @RequestBody ReservationPolicyRequest request) {
        return ResponseEntity.ok(reservationPolicyService.replacePolicy(storeId, request));
    }

}
