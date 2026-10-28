package com.catchtable.store.dto;

import java.util.List;

public record NearbyStoreListResponse(
        List<NearbyStoreResponse> stores,
        int page,
        int size,
        boolean hasNext
) {
}
