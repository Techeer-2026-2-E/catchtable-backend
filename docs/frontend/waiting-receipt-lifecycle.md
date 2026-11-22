# 고객 웨이팅 신청과 대기표 API

고객이 신청할 때 사용할 엔드포인트는 모든 매장에서 `POST /api/user/waitings`다. 서버가 매장 설정을 이어받아 서울 접수일별로 처리 경로를 결정한다. 앱은 동일한 엔드포인트에서 아래 응답에 따라 진행한다. [기존 웨이팅 신청 명세](https://app.notion.com/p/fbf48d76fa3282e1a946818c8cba1f3d)

## 신청과 응답 복구

1. 첫 전송 전에 임의의 `Idempotency-Key`를 생성해 회원·매장·요청 인원과 함께 저장한다. UUID 문자열을 사용할 수 있다. 허용값은 공백을 제외한 출력 가능한 ASCII 1~128자다. 현재 회원 식별 헤더는 `X-Member-Id`다.
2. 요청 본문은 `{"storeId":1,"partyCount":2}`다. 네트워크가 끊기거나 503을 받으면 같은 본문과 키로 재전송한다. 새로고침 뒤에도 이 키나 응답에서 받은 `Location`으로 이어간다.
3. `receiptId`가 있으면 대기표 계약을 사용한다. 기존 등록 응답은 `waitingId`, `waitNumber`, `status`, `teamsAhead`를 바로 돌려준다. 기존 응답의 `status`는 식당 웨이팅 상태다.

| 신청 응답 | 고객이 확인할 수 있는 결과 |
| --- | --- |
| 201 | 식당 번호 발급 완료. 대기표 경로는 실제 번호와 대기표를 함께 반환한다. |
| 202 | 장부에 접수됨. `intakeSequence`는 해당 매장·접수일의 접수 순번이다. `waitNumber`는 식당 번호를 발급한 뒤 생긴다. |
| 200 | 기존 신청의 현재 결과. 같은 키의 재전송 또는 같은 회원·매장·당일 활성 신청 중복에서 돌아온다. |
| 400 / 409 | 대기표 경로의 누락·잘못된 키, 잘못된 인원은 400. 기존 키의 매장·인원 변경, 활성 신청의 인원 충돌, 접수 불가 상태는 사유별 409. 응답의 `code`와 `message`를 사용한다. |
| 429 `WAITING_INTAKE_FULL` | 새 신청 시점에 장부의 미처리 한도가 찼다. `Retry-After` 이후 같은 키로 다시 신청할 수 있다. |
| 503 `WAITING_RECEIPT_UNCONFIRMED` | 저장 결과를 확인할 수 없다. `Retry-After` 이후 같은 키로 결과를 확인한다. |

대기표 응답은 `Cache-Control: no-store`와 본인만 조회할 수 있는 `Location: /api/user/waitings/receipts/{receiptId}`를 준다. 미완료 응답은 `Retry-After`에 초 단위의 다음 조회 간격을 준다. 간격이 지난 뒤 양의 무작위 지연을 조금 더해 조회하면 동시 폴링을 분산할 수 있다. 화면을 닫아도 서버에서 처리는 계속된다.

## 같은 표의 조회 예시

`GET /api/user/waitings/receipts/{receiptId}`에 같은 회원 헤더를 보낸다. 아래는 합성 예시다.

```json
{"receiptId":"e10f8d55-1ba5-44a4-9a9f-1277f1f1688e","storeId":1,"receivedDate":"2026-11-22","intakeSequence":17,"partyCount":2,"status":"PENDING"}
```

```json
{"receiptId":"e10f8d55-1ba5-44a4-9a9f-1277f1f1688e","storeId":1,"receivedDate":"2026-11-22","intakeSequence":17,"partyCount":2,"status":"REGISTERED","waitingId":901,"waitNumber":15,"teamsAhead":8,"waitingStatus":"WAITING"}
```

| 대기표 `status` | 표시 기준 |
| --- | --- |
| `PENDING` / `PROCESSING` | 신청 대기 중 / 번호 발급 처리 중. 서버가 알려 준 간격으로 이 표를 조회한다. |
| `REGISTERED` | 실제 `waitNumber`를 표시한다. 앞 팀 수는 서버가 내려준 `teamsAhead`를 사용한다. `waitingStatus`가 실제 웨이팅의 현재 상태다. 이후 호출·도착 관련 API에는 `waitingId`를 사용한다. |
| `CANCEL_REQUESTED` | 취소 요청을 보관했다. 확정 결과가 나올 때까지 같은 표를 조회한다. |
| `WITHDRAWN` / `CANCELED` | 번호 발급 전 철회 / 번호 발급 후 실제 취소 완료. |

현재 공개하지 않는 값은 JSON에서 생략한다. `teamsAhead`는 실제 웨이팅이 활성 상태일 때 조회 시점의 앞 팀 수다. 서울 자정 이후에도 같은 키와 예전 표 URL은 최초 접수일의 결과를 돌려준다. 새 신청은 새 키로 시작한다.

## 취소

`DELETE /api/user/waitings/receipts/{receiptId}` 한 번으로 취소를 요청한다. 번호 발급 전 철회 또는 실제 취소 완료는 200, 발급 중 취소 의사를 저장한 경우는 202다. 중간 상태에서 화면을 다시 열면 동일 표를 GET한다. `cancelOutcome`은 확정 시 `WITHDRAWN`, `CANCELED`, `REJECTED` 중 하나다. 착석·노쇼 등 기존 규칙상 취소할 수 없는 실제 웨이팅은 DELETE 409와 `cancelOutcome: "REJECTED"`를 돌려준다. 재시도는 같은 표에서 처리하며 타인의 표는 404다. 전환 이전 응답에는 기존 웨이팅 API를 사용한다.
