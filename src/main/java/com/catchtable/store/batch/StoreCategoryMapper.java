package com.catchtable.store.batch;

import com.catchtable.store.entity.StoreCategory;

import java.util.Map;

import static com.catchtable.store.entity.StoreCategory.*;

//업종이 문자열이라서 우리 방식이랑 다름 우리는 ENUM
public final class StoreCategoryMapper {

    private static final Map<String, StoreCategory> BY_BIZ_TYPE = Map.ofEntries(
            Map.entry("한식", KOREAN), Map.entry("분식", KOREAN), Map.entry("김밥(도시락)", KOREAN),
            Map.entry("통닭(치킨)", KOREAN), Map.entry("식육(숯불구이)", KOREAN), Map.entry("냉면집", KOREAN),
            Map.entry("탕류(보신용)", KOREAN), Map.entry("횟집", KOREAN), Map.entry("복어취급", KOREAN),
            Map.entry("일식", JAPANESE),
            Map.entry("중국식", CHINESE),
            Map.entry("경양식", WESTERN), Map.entry("패밀리레스트랑", WESTERN), Map.entry("패스트푸드", WESTERN),
            Map.entry("까페", CAFE), Map.entry("커피숍", CAFE), Map.entry("전통찻집", CAFE), Map.entry("라이브카페", CAFE),
            Map.entry("호프/통닭", BAR), Map.entry("정종/대포집/소주방", BAR), Map.entry("감성주점", BAR)
    );

    private StoreCategoryMapper() {
    }

    // 목록에 없는 업태(외국음식전문점, 뷔페식, 빈 값 등)는 ETC
    public static StoreCategory map(String bizType) {
        if (bizType == null) {
            return ETC;
        }
        return BY_BIZ_TYPE.getOrDefault(bizType, ETC);
    }
}
