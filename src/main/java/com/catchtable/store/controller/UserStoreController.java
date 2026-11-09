package com.catchtable.store.controller;


import com.catchtable.store.dto.CategoryResponse;
import com.catchtable.store.dto.MapBoundsCondition;
import com.catchtable.store.dto.MapBoundsListCondition;
import com.catchtable.store.dto.MapMarkerListResponse;
import com.catchtable.store.dto.MapStoreListResponse;
import com.catchtable.store.dto.NearbyStoreCondition;
import com.catchtable.store.dto.NearbyStoreListResponse;
import com.catchtable.store.dto.StoreDetailResponse;
import com.catchtable.store.dto.StoreListResponse;
import com.catchtable.store.dto.StoreSearchCondition;
import com.catchtable.store.service.MapBoundsService;
import com.catchtable.store.service.NearbyStoreService;
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
    private final NearbyStoreService nearbyStoreService;
    private final MapBoundsService mapBoundsService;

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> getCategories()
    {
        return ResponseEntity.ok(service.getCategories());
    }

    @GetMapping("/stores")
    public ResponseEntity<StoreListResponse> searchStores(@Valid @ModelAttribute StoreSearchCondition condition)
    {
        return ResponseEntity.ok(service.searchStores(condition));
    }

    @GetMapping("/stores/home")
    public ResponseEntity<NearbyStoreListResponse> getNearbyStores(@Valid @ModelAttribute NearbyStoreCondition condition)
    {
        return ResponseEntity.ok(nearbyStoreService.getNearbyStores(condition));
    }

    @GetMapping("/stores/map/radius")
    public ResponseEntity<MapStoreListResponse> getMapStoresByRadius(@Valid @ModelAttribute NearbyStoreCondition condition)
    {
        return ResponseEntity.ok(nearbyStoreService.getMapStores(condition));
    }

    @GetMapping("/stores/map/bounds")
    public ResponseEntity<MapStoreListResponse> getMapStoresByBounds(@Valid @ModelAttribute MapBoundsListCondition condition)
    {
        return ResponseEntity.ok(mapBoundsService.getStores(condition));
    }

    @GetMapping("/stores/map/bounds/markers")
    public ResponseEntity<MapMarkerListResponse> getMapMarkers(@Valid @ModelAttribute MapBoundsCondition condition)
    {
        return ResponseEntity.ok(mapBoundsService.getMarkers(condition));
    }

    @GetMapping("/stores/{storeId}")
    public ResponseEntity<StoreDetailResponse> getStoreDetail(@PathVariable Long storeId)
    {
        return ResponseEntity.ok(service.getStoreDetail(storeId));
    }
}
