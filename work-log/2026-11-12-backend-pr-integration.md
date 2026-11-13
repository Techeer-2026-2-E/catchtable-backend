# 최신 dev와 백엔드 개선 합본 로컬 검증

2026-11-12. 지도 조회가 포함된 `dev`의 `4ac98d2034dfe17d80836667480c7d38459479ab`에
기존 A/B/C/D·영속 SSE 개선과 [문서·도구 보완](2026-11-12-backend-review-docs-tools.md)을 결합했다.
`SpecialBusinessDayRepository`에는 영업시간 검증용 단일매장 조회와 지도용 다중매장 조회,
`Collection` import를 함께 보존했다. 지도 변경과 기존 개선을 각각 통과한 결과를 합본 검증으로 대신하지 않고 새 환경에서 실행했다.

Java21, cached PostgreSQL16의 새 전용 DB `127.0.0.1:55439/ct_pr_ready`를 사용했다.
앱18089/fake PG19089와 JUnit 전용19084를 독점 사용하고, 실제 Toss/SOLAPI·공용 DB에는 접속하지 않았다.
전체 build 명령은 다음과 같다.

```sh
./gradlew build --offline --no-daemon --max-workers=2 --no-build-cache --rerun-tasks \
  -Dorg.gradle.java.installations.auto-download=false --console=plain
```

새 schema에서 **107클래스·1,204건, 실패/오류/skip0**으로 통과했다. 8개 Gradle 작업 모두 실제 실행됐다.
지도 반경24·범위36·영업상태 응답6·상태 계산12건, B snapshot9·별도 실제 DB 연결 경합12건,
A 가용성 batch19건이 XML에 포함됐다. fresh/단계별 마이그레이션과 별도 비소유자 LOGIN의 최소 권한 조회 및
8개 원문 접근·쓰기 거부(SQLSTATE42501)도 통과했다. test context cache4를 유지했고,
Hikari `data-source-properties.currentSchema=<새 schema>,public`을 사용해 확장 탐색과 제한 LOGIN의 schema 선택을 보존했다.

실제 V37→V39 upgrade는 기존20테이블·16시퀀스·Flyway36행의 자료/이력을 보존하고 최종38행으로 통과했다.
새 root/target/attempt/observation backfill0과 TIMEOUT 결제·UNKNOWN 환불 보존을 확인했다.
PG/SSE/common scheduler 검증에서는 동일 GET 요청이 응답을 기다리는 동안 예약·웨이팅 만료와 실제 SSE를 관찰했다.
전송 경계의 transaction=false/DB connection lease0 및 별도 연결 NOWAIT 검사를 확인했고,
실제 WAITING_CALLED 전송 후 결과 저장 전 JVM을 중단한 시도는 새 JVM에서 UNKNOWN으로 복구돼 재연결에도 재전송되지 않았다.
승인·환불의 fake 원장 효과는 각1회, 원래 PAYMENT_ONLY 환불 의도/키/3,000원과 잔액7,000원,
PARTIAL_REFUNDED·CONFIRMED·job DONE을 확인했다. wire 전체 payload와 eventId·업무 사건·root/target/attempt·연결 세대를 대조했다.

bootJar의 application.yml과 migration38개는 소스와 바이트가 같고,
test/probe·옛 관찰 recorder·test-only 설정은 포함되지 않았다.
588파일 실행 입력은 build/upgrade/restart 전후 동일했고, 실행 뒤 추가한 파일은 이 기록뿐이다.
기존 I 원본·과거 증거와 R 원본은 보존했다. 생성한 앱/fake/Gradle/JDBC는 종료됐고 전용 DB/volume/schema와 원시는 보존했다.

lease는 SQL로 가속했고 probe의 고정 Clock은 JVM 재시작 때 초기화된다. 실제30/120초 대기나 고객 ACK를 증명하지 않는다.
250ms는 시작한 send의 강제 timeout 보장이 아니다. A/B/C/D/F 장시간 부하는 반복하지 않았고 과거 수치는 당시 소스·조건의 결과로만 남긴다.
독립 최종 근거 리뷰와 관리자 판단, 새 CI·프런트 연동·실제 공급사·운영 적용/배포는 별도다. 커밋·게시하지 않았다.

기존 판단과 상세 범위는 [가용성](2026-11-11-availability-batch.md),
[영업시간 검증](2026-11-11-business-hour-validation.md), [웨이팅 이력](2026-11-11-waiting-event-history.md),
[결제 격리](2026-11-11-payment-isolation.md), [영속 SSE](2026-11-11-waiting-sse-durability.md),
[SSE 실행량 보완](2026-11-11-waiting-sse-throughput.md), [기존 합본 검증](2026-11-11-backend-improvement-integration.md)을 따른다.
