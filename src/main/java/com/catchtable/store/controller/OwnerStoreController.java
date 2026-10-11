package com.catchtable.store.controller;


import com.catchtable.store.dto.StoreInfoResponse;
import com.catchtable.store.dto.StoreInfoUpdateRequest;
import com.catchtable.store.service.StoreInfoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner/stores/{storeId}")
public class OwnerStoreController {

    private final StoreInfoService storeInfoService;

    @PatchMapping
    public ResponseEntity<StoreInfoResponse> updateInfo(
            @PathVariable Long storeId,
            @Valid @RequestBody StoreInfoUpdateRequest request
            )
    {
        return ResponseEntity.ok(storeInfoService.updateInfo(storeId,request));
    }
}
