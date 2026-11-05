package com.catchtable.store.controller;

import com.catchtable.store.dto.SpecialBusinessDayRequest;
import com.catchtable.store.dto.SpecialBusinessDayResponse;
import com.catchtable.store.service.SpecialBusinessDayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner/stores/{storeId}/special-business-days")
public class OwnerSpecialBusinessDayController {

    private final SpecialBusinessDayService specialBusinessDayService;

    @GetMapping
    public ResponseEntity<List<SpecialBusinessDayResponse>> getSpecialBusinessDays(@PathVariable Long storeId) {
        return ResponseEntity.ok(specialBusinessDayService.getSpecialBusinessDays(storeId));
    }

    @PutMapping("/{businessDate}")
    public ResponseEntity<SpecialBusinessDayResponse> saveSpecialBusinessDay(
            @PathVariable Long storeId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @Valid @RequestBody SpecialBusinessDayRequest request) {
        return ResponseEntity.ok(specialBusinessDayService.saveSpecialBusinessDay(storeId, businessDate, request));
    }

    @DeleteMapping("/{businessDate}")
    public ResponseEntity<Void> deleteSpecialBusinessDay(
            @PathVariable Long storeId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        specialBusinessDayService.deleteSpecialBusinessDay(storeId, businessDate);
        return ResponseEntity.noContent().build();
    }
}
