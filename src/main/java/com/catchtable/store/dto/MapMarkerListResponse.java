package com.catchtable.store.dto;

import java.util.List;

// truncated가 true면 화면 안 매장이 조회 상한보다 많아 일부만 담겼다는 뜻이다
public record MapMarkerListResponse(
        List<MapMarkerResponse> stores,
        boolean truncated
) {
}
