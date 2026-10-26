package com.catchtable.store.controller;


import com.catchtable.store.dto.MenuCreateRequest;
import com.catchtable.store.dto.MenuResponse;
import com.catchtable.store.dto.MenuUpdateRequest;
import com.catchtable.store.service.MenuService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/owner/stores/{storeId}/menus")
public class OwnerMenuController {

    private final MenuService menuService;

    @PostMapping
    public ResponseEntity<MenuResponse> createMenu(
            @PathVariable Long storeId,
            @Valid @RequestBody MenuCreateRequest request
    )
    {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(menuService.createMenu(storeId, request));
    }

    @PatchMapping("/{menuId}")
    public ResponseEntity<MenuResponse> updateMenu
            (
                    @PathVariable Long storeId,
                    @PathVariable Long menuId,
                    @Valid @RequestBody MenuUpdateRequest request
            )
    {
        return ResponseEntity.ok(menuService.updateMenu(storeId, menuId, request));
    }
}
