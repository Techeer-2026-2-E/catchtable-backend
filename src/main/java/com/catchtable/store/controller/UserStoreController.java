package com.catchtable.store.controller;


import com.catchtable.store.dto.CategoryResponse;
import com.catchtable.store.dto.StoreDetailResponse;
import com.catchtable.store.dto.StoreSearchCondition;
import com.catchtable.store.dto.StoreSummaryResponse;
import com.catchtable.store.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserStoreController {

    private final StoreService service;

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> getCategories()
    {
        return ResponseEntity.ok(service.getCategories());
    }

    @GetMapping("/stores")
    public ResponseEntity<List<StoreSummaryResponse>> searchStores(@Valid @ModelAttribute StoreSearchCondition condition)
    {
        return ResponseEntity.ok(service.searchStores(condition));
    }

    @GetMapping("/stores/{storeId}")
    public ResponseEntity<StoreDetailResponse> getStoreDetail(@PathVariable Long storeId)
    {
        return ResponseEntity.ok(service.getStoreDetail(storeId));
    }
}
