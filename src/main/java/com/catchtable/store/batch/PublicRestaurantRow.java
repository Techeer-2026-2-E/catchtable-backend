package com.catchtable.store.batch;

// 값들이 공백이나 빈 칸이 섞여 있어서
public record PublicRestaurantRow(
        String mgtNo,  // 1  관리번호
        String statusName,     // 4  영업상태명
        String phone,          // 8  전화번호
        String lotAddress,     // 11 지번주소
        String roadAddress,    // 12 도로명주소
        String bizName,        // 14 사업장명
        String lastModifiedAt, // 15 최종수정일자
        String bizType,        // 18 업태구분명
        String x,              // 39 X좌표 (EPSG:5179)
        String y               // 40 Y좌표 (EPSG:5179)

) {

    private static final String OPEN_STATUS = "영업/정상";

    public PublicRestaurantRow {
        mgtNo = clean(mgtNo);
        statusName = clean(statusName);
        phone = clean(phone);
        lotAddress = clean(lotAddress);
        roadAddress = clean(roadAddress);
        bizName = clean(bizName);
        lastModifiedAt = clean(lastModifiedAt);
        bizType = clean(bizType);
        x = clean(x);
        y = clean(y);
    }

    public boolean isOpen() {
        return OPEN_STATUS.equals(statusName);
    }

    public boolean hasCoordinate() {
        return x != null && y != null;
    }

    private static String clean(String value)
    {
        if(value==null)
        {
            return null;
        }
        String trimmed=value.trim();
        return trimmed.isEmpty()?null:trimmed;
    }
}
