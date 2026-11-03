# Toss 응답을 읽을 수 없을 때 결과 불명으로 보존

- 작업일: 2026-11-03
- 관련: [예약금 결제 #65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), [환불 #68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68)
- 기준: `origin/dev@595be2530952fab3b7ee57fee81348351454336d`, 별도 `fix/65-68-toss-unknown-response` 작업트리.

## 문제와 변경

운영 HTTP 클라이언트는 네트워크 오류·Toss 5xx·처리 중 응답을 결과 불명으로 변환하지만, HTTP 성공의 빈 본문은 null로 반환하고 깨진 JSON·HTML 본문은 Spring 예외를 그대로 전파했다. 승인/환불의 기존 결과 불명 저장·재확인 경로에 들어가지 못할 수 있었다.

`RestTossPaymentsClient.execute`에서 빈 응답과 `RestClientException` 계열의 응답 해석 실패를 기존 `TossPaymentsUnknownResultException`으로 변환한다. 기존 네트워크 오류와 HTTP 오류 처리는 먼저 유지한다. 4xx 본문 자체가 깨졌을 때도 기존 HTTP 실패 분류를 바꾸지 않는다.

호출부인 `PaymentApprovalService`, `PaymentRefundService`, `PaymentReconcileService`를 대조했다. 같은 예외를 받으면 승인은 TIMEOUT과 재확인 오류, 환불은 UNKNOWN과 재확인 오류, 조회는 상태 보존 경로를 사용한다. 예약 측 승인·환불 보호와 같은 키 재시도 계약을 그대로 사용하며 새 상태·시간 만료·재시도 작업을 추가하지 않는다.

## 검증

Java21·새 전용 PostgreSQL16의 빈 `ct_toss_response_test` DB를 loopback 임시 포트57353에 사용했다. `TOSS_SECRET_KEY`는 비웠고 `TOSS_BASE_URL=http://127.0.0.1:9`로 실행했다. 클라이언트 단위 검사는 `MockRestServiceServer`를 사용하므로 실제 Toss 호출은 없다. 테스트와 빌드의 전체 로그·실행별 XML을 별도 보관했다.

| 확인 | 명령·대상 | 결과 |
| --- | --- | --- |
| 수정 전 재현 | `./gradlew test --tests com.catchtable.payment.client.RestTossPaymentsClientTest --no-daemon` | 18개 중 빈 본문·깨진 JSON·HTML 3개 실패. 기존 4xx와 추가한 깨진 4xx 본문 검사는 통과 |
| 수정 후 클라이언트 | 같은 명령 | 18개, 실패·오류·건너뜀0 |
| 전체 회귀·패키징 | 아래 전용 DB 환경에서 `./gradlew clean build --no-daemon` | 72클래스 800개, 실패·오류·건너뜀0 및 bootJar 성공 |
| 마이그레이션 | 기존27개 파일 전후 SHA-256 | 모두 동일. 스키마 변경 없음 |

```bash
DB_HOST=127.0.0.1 DB_PORT=57353 DB_NAME=ct_toss_response_test \
DB_USERNAME=catchtable DB_PASSWORD=catchtable \
TOSS_SECRET_KEY= TOSS_BASE_URL=http://127.0.0.1:9 \
./gradlew clean build --no-daemon
```

57353은 이번 실행의 전용 임시 포트다. 재실행 시 새 테스트 전용 DB의 실제 포트를 사용한다. 새 테스트4개는 클라이언트의 응답/예외 경계를 검증하며, 서비스의 기존 결과 불명·동시성·예약 보호 검사는 전체 회귀에 포함됐다. 실제 PG 응답으로 공개 유료 API 전체를 실행한 검증은 아니다.

## 남은 범위

실제 Toss 승인·환불 검증 보류와 기능33 구현 대기 결정을 유지한다. #65·#68은 전체 유료 원본·외부 검증이 남아 이 수정만으로 닫지 않는다. 기능33의 매장 설정, 다른 담당자의 #229·#230, 프런트 레포, 공유 DB·배포는 변경하지 않았다. 소스 PR CI·리뷰·머지는 로컬 검증과 별도로 확인한다.
