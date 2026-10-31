# 기능1 — 공공 매장 읽기와 운영·예약 경계

2026-10-29. [기능1 명세](https://app.notion.com/p/3d548d76fa3281f48332fa1379c975c8), 이슈 #206, [소스 PR #212](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/212). 소스는 dev 대상 일반 PR·Ready for review·미머지다. 문서 머지는 코드/API 제공·배포 완료를 뜻하지 않는다.

## 기존 정책과 허용 동작

[기존 V22](../../src/main/resources/db/migration/V22__add_store_public_data.sql)는 `owner_id NULL` 공공 매장이 검색·지도 전용이고 예약·웨이팅 대상이 아니라고 명시한다. 이 수정은 누락된 진입점 검사를 적용하며 새 입점·예약금 설정·인증 정책을 만들지 않는다.

| 요청 | 공공 매장 응답 |
| --- | --- |
| 고객 목록·주변·상세 조회 | 기존 읽기 유지 |
| 점주 테이블·영업시간·예약 정책 조회/변경, 기본 정보·메뉴 변경 | 404 STORE_NOT_FOUND |
| 예약 가능 시간·가용 수량, 즉시 예약·선점 생성 | 404 STORE_NOT_FOUND |
| 점주 웨이팅 목록·SSE·접수 열기/닫기·설정 | 404 STORE_NOT_FOUND; 잘못된 회원 유형은 기존403 유지 |
| 고객 웨이팅 신청 | 기존409 WAITING_CLOSED 유지 |

잘못 남은 테이블·영업시간이나 `waiting_available=true`로 예약·웨이팅 가능 상태를 추정하지 않는다. 가용성/예약 요청이404면 예약이나 선점이 생성된 것으로 표시하지 않는다. 점주 웨이팅의 owner null 참조500은404로 처리한다. 일반 점주 매장과 기존 회원 식별·DTO·알림 계약은 유지한다.

## 검증과 남은 점

실제 PostgreSQL16·MockMvc의 경계5개 테스트는 변경 전3개 실패, 수정 후 모두 통과했다. 고객 읽기, 설정/예약 행 생성 없음·기존 데이터 불변, 점주 웨이팅404·고객409 및 정상 매장의 설정·선점/즉시확정 회귀를 확인했다. 새 빈 DB 전체 clean build는 **484개·실패/오류/건너뜀0**, bootJar 성공이다. [작업 기록](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/37fe71da21e1bcd00d7e476ae4697b0113a92df7/work-log/206-public-store-boundaries.md)과 source head CI는 별도 근거다.

실제 운영 데이터·프런트 런타임·배포는 확인하지 않았다. 기존 V22와 다른 적용 이력은 변경하지 않았고 공유 V20 적용 결정은 별도다. 점주 로그인/JWT·기능33 예약금 설정 구현이 완료된 것으로 표시하지 않는다.

## 상세 응답의 예약 지원 여부 — 2026-10-31

`GET /api/user/stores/{storeId}`에 `reservationEnabled: boolean`을 추가했다. 점주가 연결된 매장은 `true`, `owner_id NULL` 공공 매장은 `false`다. 당일 휴무나 재고 소진을 이 값으로 판단하지 않는다. 실제 예약 가능 시간과 잔여 테이블은 기존 가용성 API를 사용한다.

프런트는 `false`인 매장에서 예약 버튼을 비활성화하고 `예약 미지원` 사유를 표시한다. 가용성 조회와 예약 시트를 열지 않으며 직접 예약 URL 진입도 막는다. 공공 매장의 읽기 계약과 기존 예약·운영 API 제한은 유지한다.

새 전용 PostgreSQL 16 DB의 전체 빌드 786개 테스트와 MSW를 끈 실제 프런트 화면을 확인했다. 이전 절의 미검증 표시는 당시 범위다. 현재도 CI·배포·공유 DB 적용은 확인하지 않았다. [변경·검증 기록](../../work-log/2026-10-31-store-reservation-support.md)을 참조한다.
