package com.catchtable.store.dto;

import java.time.LocalDateTime;

// 지도 화면의 매장 목록 한 건. 프런트가 요청한 필드만 담는다
public record MapStoreResponse(
        Long id,
        String name,
        String categoryName,
        String imageUrl,
        Double latitude,
        Double longitude,
        String area,
        Integer distanceMeters,     // 현재 위치를 보낸 조회에서만 값이 있다
        boolean reservationEnabled, // 점주가 있는 입점 매장만 true. 공공데이터 매장은 false
        boolean waitingAvailable,
        OpenStatus openStatus,        // 영업시간 정보가 없는 매장은 null
        LocalDateTime nextChangeAt,   // 다음에 상태가 바뀌는 한국 시각. 상태가 없거나 다음 영업이 없으면 null
        Double ratingAverage,         // 소수 첫째 자리. 리뷰가 없으면 null
        long reviewCount
) {

    // SQL이 읽어 온 기본 필드에 영업 상태와 별점을 합친 새 응답을 만든다
    public MapStoreResponse withOpenStatusAndReview(OpenStatusInfo openStatusInfo, Double ratingAverage,
                                                    long reviewCount) {
        return new MapStoreResponse(id, name, categoryName, imageUrl, latitude, longitude, area, distanceMeters,
                reservationEnabled, waitingAvailable,
                openStatusInfo == null ? null : openStatusInfo.status(),
                openStatusInfo == null ? null : openStatusInfo.nextChangeAt(),
                ratingAverage, reviewCount);
    }

    // "서울특별시 금천구 가산디지털1로 123"에서 두 번째 단어(금천구)를 꺼낸다.
    // 구로 끝나지 않으면 다른 형식의 주소이므로 잘못된 값 대신 null을 준다
    public static String areaOf(String address) {
        if (address == null) {
            return null;
        }
        String[] words = address.trim().split("\\s+");
        if (words.length < 2 || !words[1].endsWith("구")) {
            return null;
        }
        return words[1];
    }
}
