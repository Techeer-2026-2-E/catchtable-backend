# P2 실제 앱의 선점·반환 검증과 Toss 재인증 준비

- 확인일: 2026-11-05, Asia/Seoul
- 기준: `dev@355a582281f399a72904167a184e9bac44ed851d`
- 관련: [기능10 #65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65), [기능60 #68](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/68), [기능53](https://app.notion.com/p/3d548d76fa3281f7834ee32be4c17f60), [기능56](https://app.notion.com/p/3d548d76fa32813a94fbdec00b9f54e9)

## 배경과 범위

사용자 담당 백엔드의 남은 검증을 진행하면서 Notion과 사용자가 지정한 「P2 마감 회의 자료」 HTML을 대조했다. HTML은 11월 3일의 백엔드 `d3c893b`·프런트 `015f333` 스냅샷이다. 원본은 수정하지 않았다.

현재 기준에는 [실제 예약금 정책 #242](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/242), [운영 공급자 통합 검증 #243](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/243), [독립 Toss 검증 #238](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/238), [응답 해석 실패 보호 #235](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/235), [승인 요청 키 복구 #245](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/245)가 모두 머지돼 있다. 따라서 기능33 구현 대기·위 PR의 미머지 표시는 현재 병목이 아니다. [테스트 Clock 수정 #246](https://github.com/Techeer-2026-2-E/catchtable-backend/pull/246)의 dev 머지 후 [CI](https://github.com/Techeer-2026-2-E/catchtable-backend/actions/runs/41800335263)도 성공했다. 이 CI와 이전 테스트 수는 이번에 다시 실행한 앱 검증과 구분한다.

이번에는 운영 코드를 변경하지 않았다. 별도 로컬 PostgreSQL16과 실제 Spring 앱에서 정책 설정·선점·prepare·만료·반환을 HTTP로 확인하고, PG 요청은 loopback 중계기가 `https://api.tosspayments.com`으로 전달했다. 중계기는 가짜 PG 응답을 만들지 않았으며, 계획한 승인/환불 성공 응답의 502 치환은 아직 실행되지 않았다. 서비스 프런트 구현과 공용 DB는 범위 밖이다.

## 환경과 실행

Java 21.0.12.1에서 `./gradlew bootJar --no-daemon`이 성공했다. 새 전용 컨테이너 `ct-p2-e2e-b26998f7`는 PostgreSQL16·tmpfs·종료 시 자동 삭제이며, loopback `127.0.0.1:53242`의 빈 DB `ct_p2_e2e`를 사용했다. 기존 개발 데이터나 공유 DB를 사용하지 않았다.

저장소 밖 임시 검증기가 JAR를 실행하고 HTTP 요청을 보냈다. 아래는 개별 테스트 키 확인 후의 실행 환경이며, 앞선 5분 만료 검증의 앱/중계기 포트는 53621/53622다. 두 실행은 같은 전용 DB를 사용했다. 키는 로컬 비밀 설정에서 프로세스 환경으로만 전달했다.

```bash
DB_HOST=127.0.0.1 DB_PORT=53242 DB_NAME=ct_p2_e2e \
DB_USERNAME=catchtable DB_PASSWORD=catchtable \
SERVER_PORT=56932 TOSS_BASE_URL=http://127.0.0.1:56933 \
java -jar build/libs/catchtable-0.0.1-SNAPSHOT.jar \
  --server.address=127.0.0.1 --payment.reconcile-interval-ms=3600000
```

`TOSS_SECRET_KEY`를 채팅·명령문·Git에 넣지 않았다. 자동 재확인이 수동 복구 실험과 경합하지 않도록 주기만 1시간으로 설정했다. 선점의 실제 Clock·5분 기한·만료 정리는 바꾸지 않았다. 자동 재확인 스케줄러의 기본 주기를 검증한 실행이 아니다. Flyway 30개 적용, JPA validate, health `UP`을 확인한 뒤 이 DB에만 가상 회원3명·점주 매장1개·테이블1개를 넣었다.

## 실제 HTTP 관측

| 검사 | 결과 |
| --- | --- |
| 실제 점주 정책 API → 유료 선점 | 10,000원·STANDARD로 설정 후 선점하자 해당 시간의 예약 가능 테이블 수가 1에서 0으로 바뀜 |
| 설정 변경 후 prepare·동일 주문 재사용 | 선점 후 매장을 30,000원·STRICT로 바꿔도 prepare 신규201·반복200은 최초 10,000원·주문·기한을 유지 |
| 실제 5분 만료 | Clock 대체·SQL 기한 변경 없이 기다림. 선점 조회는 `EXPIRED`·남은0초이며 예약 가능 수가 1로 복구 |
| 같은 시간 재선점·반복 해제 | 새 holdId로 같은 테이블·시간을 선점하고 두 번 DELETE해도 자리 수는 최종1 |
| 실제 Toss의 명확한 입력 거절 | 중계기의 본문 처리 수정 후 별도 새 선점·prepare에서 존재하지 않는 테스트 paymentKey를 confirm에 전달. 실제 Toss의 `404 NOT_FOUND_PAYMENT_SESSION`을 받아 결제 `FAILED`·예약 `CANCELLED`로 정산 |
| 거절 후 재요청·자리 반환 | 반복 confirm은 `409 INVALID_PAYMENT_STATUS`. 같은 시간에 새 선점 성공 |

마지막 두 행은 실제 카드사의 승인 거절을 재현한 결과가 아니다. 잘못된 테스트 입력에 대한 Toss의 확정 거절과 앱 정산을 확인한 것이다. 이 실행에서 정상 승인·양수 환불된 결제는 없다.

최초 검증기는 선점 생성의 `+09:00`과 DB 재조회 응답의 `Z`를 문자열로 비교해 중단됐다. 실제 시각 차이는0이었다. 임시 검증기를 같은 시점 비교로 고치고 실제 5분 만료까지 확인했다. 이 실패는 백엔드 결함이나 성공한 검사로 기록하지 않았으며 최초 로그를 보존했다.

첫 중계기는 Content-Length만 읽어 Spring의 chunked 본문을 비운 채 전달했다. 최초 입력 거절 기록과 첫 사용자 인증의 `400 INVALID_REQUEST`는 이 검증기 오류의 영향이 있으므로 올바른 confirm 본문을 검증한 증거로 쓰지 않는다. 첫 인증 주문은 별도 orderId 조회에서 `IN_PROGRESS`·주문/금액 일치를 확인했으며 정상 승인은 되지 않았다. 중계기에 크기 제한을 둔 chunked 처리를 보완하고 정상3·잘못된5개 framing 자기검사를 통과했다. 새 실패 경로 검증에서는 실제 앱의 `chunked`·본문105바이트·필드 amount/orderId/paymentKey 전달과 위 `404 NOT_FOUND_PAYMENT_SESSION` 정산·반복 승인409·이력 수2 유지를 확인했다. 수정 전 로그와 잘못된 400 예상 assertion도 보존했다. 운영 클라이언트를 바꾼 수정은 아니다.

이어 기본 Python3.9가 유효한 소수점4자리 ISO 시각을 거절해 준비 검사에 실패했다. 설치된 Python3.14의 표준 파서로 실행하고 1~9자리·KST/UTC 비교를 자기검사했다. 검사 실패로 남은 미승인 선점은 공개 DELETE로 해제했으며 DB 기한이나 결제 상태를 직접 고치지 않았다. 새 선점의 prepare·최초 금액/기한 검사와 SDK 카드사 선택 화면까지 검증기 자체가 확인한 뒤 재인증을 요청했다.

## 키 확인과 다음 완료 조건

보관된 `test_gck`·`test_gsk`는 공개 샘플 키 세트다. secret으로 존재하지 않는 결제를 조회하면 `404 NOT_FOUND_PAYMENT`여서 인증 자체는 거절되지 않았다. 반면 SDK 결제수단 초기화에 필요한 `GET /v1/payment-widget/widget-groups/keys?variantKey=DEFAULT`는 반복해서 **HTTP500, `5000`, `처리 중 오류가 발생했습니다`**를 반환했다. 이전에 성공한 SDK 구성으로도 같은 결과였으며, 결제 인증 화면을 정상 제공하지 못했다. 별도 공개 `test_ck`·`test_sk` 샘플 한 쌍도 읽기 전용 조회에서 `401 UNAUTHORIZED_KEY`였으므로 사용하지 않았다. 이 관측만으로 사용자 계정·사업자 여부나 백엔드 결함을 원인으로 단정하지 않는다.

이후 사용자가 로그인한 개발자센터의 API 개별 연동 `test_ck`·`test_sk`를 별도 비밀 설정으로 확인했다. 두 조회 API의 읽기 전용 사전 확인은 모두 `404 NOT_FOUND_PAYMENT`로 인증에 성공했고, 해당 키에 맞는 [카드/간편결제 통합결제창](https://docs.tosspayments.com/guides/v2/payment-window/integration)도 열렸다. 기존 공개 키 파일은 변경하지 않았다. 중계기·시각 처리 보완 후 새 선점의 준비 검사와 테스트 카드사 선택 화면까지 확인했다. **사용자가 인증을 나중에 진행하도록 선택해 여기서 보류했다.** 현재 남은 것은 새로운 주문의 사용자 인증부터다. 만료된 주문의 기한을 늘리거나 11월 3일의 취소 완료 결제를 재사용하지 않는다.

남은 실제 PG 완료 조건은 다음과 같다.

- 실제 앱의 최초 승인 → 예약 확정 → 부분 환불 → 예약 취소 시 남은 환불·자리 반환.
- 성공 응답을 중계기에서 의도적으로 502로 치환한 뒤 결과 불명 보호 → 앱 재시작 → 저장된 paymentKey로 sync 복구. 실제 통신 장애 자체의 재현과 구분한다.
- 0원 환불의 PG 미호출, 같은 멱등키 재요청의 결과 유지·추가 PG 미호출, 승인·만료 경합.

위 정상 승인·환불·복구는 이번 실행에서 아직 수행하지 않았다. [11월 3일 독립 PG 검증](2026-11-03-toss-independent-validation.md)과 [11월 4일 fake 통합 검증](2026-11-04-store-deposit-payment-flow.md)을 합쳐 전체 E2E 성공으로 표시하지 않는다. 웹훅 실수신·제품 프런트·공유 DB upgrade/V20 이력·배포 역시 미확인이다. 프런트 dev는 확인 시점에도 `015f333aa2b2d4230560d69013136190b5ea2c87`이며 유료 흐름 화면을 새로 검증하지 않았다.

검증용 소스·최초 실패 로그·수정 후 만료 결과·거절 결과·PG 사전 확인의 비밀값 제거 기록은 로컬 산출물 `p2-app-toss-20261105-56av1hkx`로 보존한다. 이 PR의 변경은 현재 안내와 검증 기록뿐이며, 앱 전체 테스트를 새로 실행한 결과가 아니다.

인증 보류 후 이번 앱 DB에 승인 완료·결과 불명 결제가0건임을 확인했다. 미승인 선점을 공개 해제 API로 종료하고, 검증기·Spring 앱·중계기·인증 서버와 전용 PostgreSQL 컨테이너를 종료·제거했다. 세 HTTP 포트 종료와 비밀 설정의0600 권한도 확인했다. 후속 인증 작업트리 한 개는 대기 상태로 남기며 기존 작업트리는 보존한다. 다음 실행은 새 일회용 DB·새 선점으로 시작한다.
