package com.catchtable.payment.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.payment.dto.SalesResponse;
import com.catchtable.payment.service.OwnerSalesService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner/stores/{storeId}")
public class OwnerSalesController {

    private final OwnerSalesService ownerSalesService;

    /** from·to는 서울 기준 날짜이고 to 날짜까지 포함한다. 회원은 X-Member-Id로 식별한다. */
    @GetMapping("/sales")
    public ResponseEntity<SalesResponse> getSales(
            @LoginMember Long ownerId,
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(ownerSalesService.getSales(ownerId, storeId, from, to));
    }
}
