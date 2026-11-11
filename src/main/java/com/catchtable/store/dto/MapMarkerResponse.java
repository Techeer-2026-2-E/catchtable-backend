package com.catchtable.store.dto;

// 지도 위의 핀 하나. 위치와 이름만 담는다
public record MapMarkerResponse(
        Long id,
        String name,
        Double latitude,
        Double longitude
) {
}
