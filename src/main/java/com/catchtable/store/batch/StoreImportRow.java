package com.catchtable.store.batch;

import com.catchtable.store.entity.StoreCategory;

public record StoreImportRow(String externalId,       // 관리번호. upsert 키
                             String name,             // 사업장명
                             StoreCategory category,  // 업태구분명을 매핑한 값
                             String address,          // 도로명주소, 없으면 지번주소
                             String phone,            // 선택값. 없으면 null
                             double latitude,
                             double longitude
) {
}
