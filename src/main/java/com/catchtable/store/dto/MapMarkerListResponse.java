package com.catchtable.store.dto;

import java.util.List;

// 화면 안 매장 중 별점 상위 몇 개만 담는다. 순서가 곧 순위다
public record MapMarkerListResponse(
        List<MapMarkerResponse> stores
) {
}
