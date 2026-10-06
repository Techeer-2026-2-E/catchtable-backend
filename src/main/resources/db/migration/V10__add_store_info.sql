-- 매장 기본 정보 (기능 #70)
--   - 점주가 PATCH /api/owner/stores/{storeId}로 수정한다. 매장명·카테고리·주소는 V2에 이미 있다
--   - description: 매장 소개
--   - phone: 매장 대표 연락처. member.phone과 같은 길이
--   - image_url: 대표 이미지 URL. 업로드 인프라 없이 URL만 저장한다
--   - 전부 선택값(NULL 허용): 기존 매장은 값 없이 유지되고, PATCH에서 빈 문자열은 NULL로 저장한다
--   - 좌표는 주소에서 파생되는 값이라 지오코딩과 함께 지도(#4)에서 추가한다
ALTER TABLE store
    ADD COLUMN description VARCHAR(1000),
    ADD COLUMN phone       VARCHAR(20),
    ADD COLUMN image_url   VARCHAR(2048);