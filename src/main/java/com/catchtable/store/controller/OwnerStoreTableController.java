package com.catchtable.store.controller;


import com.catchtable.store.dto.StoreTableCreateRequest;
import com.catchtable.store.dto.StoreTableResponse;
import com.catchtable.store.dto.StoreTableUpdateRequest;
import com.catchtable.store.service.StoreTableService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner/stores/{storeId}/tables")
public class OwnerStoreTableController {

    private final StoreTableService storeTableService;

    @GetMapping
    public ResponseEntity<List<StoreTableResponse>> getTables(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeTableService.getTables(storeId));
    }

    @PostMapping
    public ResponseEntity<StoreTableResponse> createTable(
            @PathVariable Long storeId,
            @Valid @RequestBody StoreTableCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(storeTableService.createTable(storeId, request));
    }

    @PatchMapping("/{tableId}")
    public ResponseEntity<StoreTableResponse> updateTable(
            @PathVariable Long storeId,
            @PathVariable Long tableId,
            @Valid @RequestBody StoreTableUpdateRequest request) {
        return ResponseEntity.ok(storeTableService.updateTable(storeId, tableId, request));
    }
}
