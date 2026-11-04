package com.catchtable.store.controller;

import com.catchtable.store.dto.DepositPolicyRequest;
import com.catchtable.store.dto.DepositPolicyResponse;
import com.catchtable.store.service.DepositPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner/stores/{storeId}/deposit-policy")
public class OwnerDepositPolicyController {

    private final DepositPolicyService depositPolicyService;

    @GetMapping
    public ResponseEntity<DepositPolicyResponse> getPolicy(@PathVariable Long storeId)
    {
        return ResponseEntity.ok(depositPolicyService.getPolicy(storeId));
    }

    @PutMapping
    public ResponseEntity<DepositPolicyResponse> replacePolicy(
            @PathVariable Long storeId,
            @Valid @RequestBody DepositPolicyRequest request) {
        return ResponseEntity.ok(depositPolicyService.replacePolicy(storeId, request));
    }
}
