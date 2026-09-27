package com.catchtable.store.controller;


import com.catchtable.store.dto.StoreDetailResponse;
import com.catchtable.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/stores")
public class UserStoreController {

    private final StoreService service;

    @GetMapping("/{storeId}")
    public ResponseEntity<StoreDetailResponse> getStoreDetail(@PathVariable Long storeId)
    {
        return ResponseEntity.ok(service.getStoreDetail(storeId));
    }
}
