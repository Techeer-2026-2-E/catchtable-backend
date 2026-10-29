package com.catchtable.store.dto;

import java.util.List;

public record StoreListResponse(
        List<StoreSummaryResponse> stores,
        int page,
        int size,
        boolean hasNext
) {
}
