package com.catchtable.store.dto;

import java.util.List;

public record MapStoreListResponse(
        List<MapStoreResponse> stores,
        int page,
        int size,
        boolean hasNext
) {
}
