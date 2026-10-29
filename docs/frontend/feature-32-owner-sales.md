# 기능32 — 점주 기간별 매출 조회 연동

- 확인일: 2026-10-30
- 기능 이슈: [#217](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/217), 구현: [#218](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/218)
- 문서 이슈: [#221](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/221)
- 정본: [Notion 기능32](https://app.notion.com/p/3d548d76fa328100a48af9992efb05fd), [매출 조회 API](https://app.notion.com/p/2a048d76fa32838e9cd00154fde3922a)
- 대조한 코드: [결제 패키지](../../src/main/java/com/catchtable/payment)

## 요청과 응답

`GET /api/owner/stores/{storeId}/sales?from=2030-01-19&to=2030-01-19`

점주 회원 ID를 `X-Member-Id` 헤더로 보낸다. 현재 회원 식별 계약이며 JWT 연동을 뜻하지 않는다. 본인 소유 매장만 조회한다. `from`·`to`는 필수 날짜(`yyyy-MM-dd`)이고, 서울 시간 기준 첫날 00:00 이상부터 마지막 날 다음 날 00:00 미만까지 조회한다.

```json
{
  "from": "2030-01-19",
  "to": "2030-01-19",
  "approvedAmount": 10000,
  "refundedAmount": 4000,
  "netAmount": 6000,
  "notice": "실제 매출·세금·PG 수수료·점주 정산액이 아닌 테스트 결제 기반 모의 집계입니다."
}
```

금액 단위는 원이다. 결제가 없으면 세 금액 모두 0이다. `netAmount`는 `approvedAmount - refundedAmount`다.

## 집계 기준과 화면 표시

2026-10-30 사용자 결정에 따라 승인일 기준과 환불 필요 DONE 포함을 유지한다.

- 승인 시각이 조회 기간 안인 `DONE`·`PARTIAL_REFUNDED`·`REFUNDED` 결제액을 승인액에 넣는다. `READY`·`FAILED`·`CANCELED`·`TIMEOUT`은 제외한다.
- 환불액은 같은 결제들의 현재 누적 환불액이다. 나중에 환불되면 과거 승인일 기간의 환불액과 순결제액도 바뀐다. 과거 기간 응답을 고정 장부로 취급하지 않는다.
- 예약이 확정되지 않아 `refund_required_at`이 표시된 `DONE`도 승인액에 포함한다. 이후 확인된 환불은 누적 환불액으로 차감한다. 예약 상태와 승인액을 같은 의미로 표시하지 않는다.
- 결제 재확인으로 `TIMEOUT`이 `DONE`으로 확정되면 승인일에 해당하는 기간에 포함된다.
- 화면에 응답 `notice`를 표시한다. 실제 매출·세금·수수료·정산액으로 안내하지 않는다.

## 오류

| HTTP | 조건 | 프런트 처리 |
| --- | --- | --- |
| 400 | 날짜 형식 오류·기간 누락·from이 to보다 늦음 | 입력 날짜를 확인한다. |
| 401 | 회원 식별 헤더 누락 | 점주 회원 ID 전송 여부를 확인한다. |
| 404 `STORE_NOT_FOUND` | 없는 매장·다른 점주 소유 매장·점주 없는 공공 매장 | 조회할 수 없는 매장으로 처리한다. 세 경우를 구분하지 않는다. |

## 검증과 남은 연결

구현 PR의 최신 dev 통합 상태를 Java 21·전용 PostgreSQL 16에서 `./gradlew clean build --no-daemon --console=plain`로 검증했다. 71개 클래스·778개 테스트가 실패·오류·건너뜀 없이 통과했고 패키징도 성공했다. 매출 API 테스트 10개에는 날짜 경계·상태·권한 및 위 두 확정 기준이 포함된다.

이 문서는 백엔드 계약을 전달한다. 프런트 앱 코드는 수정하지 않았고 실제 호출·화면 표시·배포·실제 Toss 결제·대량 데이터 속도는 확인하지 않았다. Notion API가 정본이며 계약 변경 시 이 메모도 함께 갱신한다.
