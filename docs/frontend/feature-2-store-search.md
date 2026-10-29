# 기능2 — 조건별 매장 검색과 페이지 연동

2026-10-29. [기능2 명세](https://app.notion.com/p/3d548d76fa328167bcfadfb92c124e48), 이슈 #207, [소스 PR #213](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/213). 소스는 dev 대상 일반 PR·Ready for review·미머지다. 아래 객체 응답과 기본 페이지는 이미 머지된 [PR200](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/200)의 계약이며, 새 후보는 필터의 대량 처리만 개선한다.

## 요청과 응답

`GET /api/user/stores`

| 쿼리 | 현재 계약 |
| --- | --- |
| category | 기존 카테고리, 생략 시 전체 |
| partySize | 양수, ACTIVE 테이블의 최소 인원·정원을 만족하는 매장 |
| date / time | 함께 입력, 예약 창/마감과 이용시간 전체의 영업·브레이크 검사 |
| page | 0부터, 기본0 |
| size | 기본10, 1~50 |

200 응답은 `{"stores":[],"page":0,"size":10,"hasNext":false}` 같은 객체다. 기존 StoreSummaryResponse 항목을 `stores`에서 읽는다. 목록은 id 오름차순이고 `hasNext`로 다음 페이지를 판단한다. 검색 조건이 바뀌면 page를0으로 되돌린다. 배열 응답·X-Has-Next 헤더·기본20/최대100은 이전 로컬 초안이며 현재 계약이 아니다. 잘못된 입력은 기존400 INVALID_INPUT을 유지한다.

무필터/카테고리 목록의 기존 DB 페이지 조회를 유지한다. 인원/날짜 필터 후보는 DB에서 조건을 먼저 적용해 size+1개까지만 읽으므로 전체 매장 ID를 IN 파라미터로 보내지 않는다. 긴 필터 페이지의 offset도 long으로 전달한다. 여러 테이블이 맞더라도 한 매장은 한 번 반환한다.

공공 매장은 무필터 목록·상세에 포함하지만 인원/예약 시각 필터는 점주가 있는 매장만 대상으로 한다. 자정 넘는 영업·브레이크·삭제된 영업시간·마감 시각과 마지막 예약일 경계는 기존 의미를 유지한다. 검색은 실제 잔여 테이블 선점을 보장하지 않으며 예약 생성이 재고를 최종 확인한다.

## 검증과 남은 점

실제 PostgreSQL16의 합성66,000건에서 수정 전 필터500과 잘못 남은 공공 매장 결과 포함을 재현했다. 수정 후 관련5개에서 인원/날짜 단독·복합 조건, 페이징/중복/정렬/hasNext·시간 경계를 확인했다. 새 빈 DB 전체 clean build는 **484개·실패/오류/건너뜀0**, bootJar 성공이다. [작업 기록](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/ed05d2f79de1d7be9ccd7b85cfc9c836696ba3f3/work-log/207-store-filter-search.md)과 정확한 source head CI를 구분한다.

offset 페이징의 깊은 페이지 비용과 동시 데이터 변경에 따른 페이지 이동은 남는다. 운영 데이터 성능·실제 프런트 연동·배포를 검증한 결과가 아니다. 문서 머지는 소스 머지나 API 개선 배포 완료를 뜻하지 않는다.
