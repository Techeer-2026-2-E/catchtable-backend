package com.catchtable.store.controller;

import com.catchtable.store.dto.BusinessHourResponse;
import com.catchtable.store.dto.BusinessHourUpdateRequest;
import com.catchtable.store.service.BusinessHourService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner/stores/{storeId}/business-hours")
public class OwnerBusinessHourController {

    private final BusinessHourService businessHourService;

    @GetMapping
    public ResponseEntity<List<BusinessHourResponse>> getBusinessHours(@PathVariable Long storeId) {
        return ResponseEntity.ok(businessHourService.getBusinessHours(storeId));
    }

    @PutMapping
    public ResponseEntity<List<BusinessHourResponse>> replaceBusinessHours(
            @PathVariable Long storeId,
            @Valid @RequestBody BusinessHourUpdateRequest request) {
        return ResponseEntity.ok(businessHourService.replaceBusinessHours(storeId, request));
    }
}
