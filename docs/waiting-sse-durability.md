# 웨이팅 SSE 영속 전달 원장

2026-11-11 로컬 후속 구현. V38 이후 생성되는 웨이팅 SSE 작업에 적용한다. 배포, 실제 고객 수신, 다중 서버 전달, 운영 용량 확정은 포함하지 않는다. 기존 HTTP 권한·상태 전이·SSE event/payload 및 V36 업무/알림톡 이력은 유지한다.

## 보장과 전달 경로

업무 상태 변경과 V36 업무 이력, V38 SSE root를 같은 업무 TX에서 저장한다. 동기 `@EventListener`가 root를 기록하며 실패하면 업무 전체가 rollback된다. `AFTER_COMMIT` 리스너는 웨이팅을 보내지 않는다. `NotificationService.send` 직접 호출도 웨이팅이면 원장으로만 들어가며 쓰기 TX가 없으면 거절한다. 순번 이벤트도 같은 규칙이다. 예약 SSE와 알림톡은 기존 전달 경로를 유지한다.

업무가 commit되면 기존 성공 응답을 반환하고 알림은 뒤따라올 수 있다. 알림 실패로 이미 commit한 업무를 rollback하거나 성공 HTTP를 실패로 바꾸지 않는다. HTTP 응답이 유실된 경우 호출이 실패했다고 단정하지 말고 현재 업무 상태와 eventKey를 대조해야 한다. `/call`에는 HTTP 멱등키가 없으므로 무조건 재호출하면 새 호출 차수가 생긴다. `/recall`은 새 전송 사건이지만 기존 기한을 연장하지 않는다.

| SSE 원인 | 업무 사건 | 저장되는 root |
| --- | --- | --- |
| 신규 웨이팅 접수 | REGISTERED | 점주 WAITING_CHANGED 1 |
| 호출 / 다시 호출 | CALLED / CALLED_AGAIN | 고객 WAITING_CALLED, 점주 WAITING_CHANGED, 순번 QUEUE 각 1 |
| 호출 재전송 `/recall` | CALL_RESENT | 고객 WAITING_CALLED 1 |
| 첫 고객 응답 | RESPONDED | 점주 WAITING_CHANGED 1 |
| 순서 연기 | DEFERRED (상대 팀 QUEUE_MOVED 이력도 보존) | 점주 WAITING_CHANGED, QUEUE 각 1 |
| 착석 | SEATED | 점주 WAITING_CHANGED, QUEUE 각 1 |
| 고객 취소 | CUSTOMER_CANCELED | 점주 WAITING_CHANGED, QUEUE 각 1 |
| 점주 취소 | OWNER_CANCELED | 고객 WAITING_CANCELED, 점주 WAITING_CHANGED, QUEUE 각 1 |
| 점주 미응답/미착석 종료 | NO_RESPONSE / NO_SHOW, 이전 상태가 진행 중일 때 | 고객 WAITING_EXPIRED, 점주 WAITING_CHANGED, QUEUE 각 1 |
| 자동 만료 | AUTO_EXPIRED | 고객 WAITING_EXPIRED, 점주 WAITING_CHANGED, QUEUE 각 1 |

같은 상태의 멱등 재요청과 종료 사유 정정만으로 추가 SSE를 만들지 않는 기존 의미를 유지한다. 동일 업무 사건의 여러 listener는 `eventKey + direct:type:member:target` 또는 `eventKey + queue:store` 고유 키로 합쳐진다. 원인 참조가 없는 내부 직접 이벤트에는 생성 시 UUID가 부여되어 같은 이벤트 객체를 다시 저장해도 한 root다. 새 객체/새 업무는 새 사건이다.

## 원장과 짧은 TX 경계

1. `waiting_sse_root`: 원인 waiting/call/recall/store, event_key, 직접 메시지 또는 순번 조회 지시. 초기 PENDING. claim token/lease는 DB 시계로 관리한다.
2. `waiting_sse_target`: 처리 시점 수신자·연결 세대별 snapshot. QUEUE는 해당 매장/날짜의 현재 WAITING/CALLED/CONFIRMED 순서로 확장한다. `expanded_at`, `expected_recipients`, `expected_targets`와 대상 전체를 한 TX에서 저장한다. NULL은 미확장, 기대 0은 확장 완료한 빈 대기열이다. 수신자 한 명의 연결0은 `generation=NONE`, `SKIPPED/NO_CONNECTION` 대상 한 개로 명시한다. 따라서 expected_targets에는 생략 대상도 들어간다.
3. `waiting_sse_attempt`: 대상당 한 개만 허용한다. 새 시도 UUID와 MARKED 상태를 commit한 뒤에만 send로 진입한다. 저장 실패/commit 응답 유실이면 send하지 않는다.
4. `waiting_sse_observation`: ACCEPTED, EXPANDED, MARKED, SKIPPED, UNKNOWN, 결과를 append한다. 저장 재시도는 동일 관찰 키를 사용한다. UNKNOWN이 나중에 해소되어도 기존 관찰은 지우지 않는다.

`WaitingSseJournal`은 수신 정책·payload 생성·TX 조율을 담당하고, `notification/repository/WaitingSseJournalRepository`는 SQL·JDBC 행 매핑만 담당한다. repository는 TX를 새로 만들거나 중단하지 않아 업무 admission/root/ACCEPTED가 같은 연결의 같은 TX에 참여한다. `WaitingSseJournal.claim/expand/ready/mark/authorized/saveResult/finish`는 각각 독립된 짧은 `REQUIRES_NEW` TX로 반환까지 commit한다. worker의 `SseEmitter.send`는 TX·행 잠금·DB 연결 밖이다. `authorized`는 선기록 뒤 현재 token/lease를 다시 확인한다. 네트워크와 DB를 원자화하지 못하므로 마지막 확인 뒤 멈춘 살아 있는 worker가 늦게 send할 가능성까지 fencing으로 없앴다고 주장하지 않는다. 회수자는 MARKED 대상을 다시 보내지 않고 나머지 READY만 처리한다.

전용 `waitingSseScheduler` 한 스레드와 worker의 즉시 획득/거절 세마포어 한 슬롯을 사용한다. 한 poll은 기본 최대64 root 또는 실제 경과250ms까지 **하나씩** 처리하고 반환 뒤 fixedDelay100ms를 기다린다. 매 root 직전에 두 한도를 확인하며 런타임은 고정될 수 있는 업무 Clock 대신 `System.nanoTime()`으로 잰다. 미리 여러 root를 claim하지 않고 각 기존 단계의 짧은 TX·commit 경계를 유지한다. 공개 `runOnce()`는 계속 한 root만 처리한다.

슬롯은 전체 poll 동안 하나만 보유하므로 scheduled/direct 호출이 겹쳐도 병렬 전송을 추가하지 않는다. 빈 큐, 확장 보류, DB 예외, 결과 저장3회 실패에서는 이번 poll을 바로 끝내고 다음 fixedDelay까지 기다린다. root 사이에 delivery/scheduling 비활성 또는 thread interruption을 확인해 다음 claim을 시작하지 않으며 interrupt flag는 지우지 않는다. 설정은 시작 시 바인딩되므로 운영 값 변경은 재시작이 필요하다.

64개/250ms는 빠른 root에서 불필요한 대기를 줄이되 한 번의 연속 실행을 제한하기 위한 잠정값이며 운영 SLO가 아니다. 시간 예산은 **root 사이**에만 적용되어 이미 진행 중인 fanout·막힌 send/DB 호출을 강제로 끝내지 못한다. 느린 send 하나는 웨이팅 전달을 지연시킬 수 있지만 업무용 DB 연결을 보유하지 않는다. 공통 예약/만료 scheduler 및 별도 PG scheduler와 분리되며 무제한 메모리 큐나 추가 send worker는 없다. 네트워크 send 강제 시간 제한은 구현하지 않았고, 이 burst 수정본의 실제 연결/부하·JVM 장애 재검증은 후속 F에서 수행한다.

V36의 best-effort SSE 관찰 writer는 제품에서 제거하고 과거 저장 방식 회귀용 test fixture로만 남겼다. V36 이력·알림톡 trigger는 그대로이며 과거 SSE 사실을 V38에 backfill하지 않는다.

## 결과와 복구 의미

| 상태/사유 | 의미와 다음 동작 |
| --- | --- |
| READY | 시도 선기록이 없는 대상. 유효성 확인 뒤 시도 가능 |
| MARKED | 시도 표식 commit. 실제 네트워크 진입/고객 수신 증거가 아님 |
| SENT / SEND_RETURNED | 서버 `send` 정상 반환을 저장함. 고객 수신·표시·열람은 UNKNOWN |
| FAILED / IO_ERROR, ILLEGAL_STATE | 서버가 해당 예외를 관찰. 연결 제거, 자동 재전송 없음 |
| UNKNOWN / LEASE_EXPIRED_AFTER_MARK | 마지막 확정 단계 ATTEMPT_MARK_COMMITTED. send 전 정지, send 중 정지, 반환 후 결과 저장 유실을 구분할 근거가 없어 추정하지 않음 |
| UNKNOWN / RUNTIME_ERROR | 예상 밖 런타임 예외. 관찰을 보존하고 자동 재전송하지 않음 |
| SKIPPED / NO_CONNECTION, CONNECTION_GONE | 연결0 또는 snapshot 연결 세대 소멸. 재연결로 과거 대상을 재생하지 않음 |
| SKIPPED / SETTING_DISABLED | 처리/시도 시점 선택 알림 수신 설정이 꺼짐. 필수 호출·종료·점주 알림은 기존 설정 정책대로 허용 |
| SKIPPED / CALL_EXPIRED, SUPERSEDED_CALL, WAITING_INACTIVE, WAITING_MISSING | 현재 호출 기한, 새 호출 차수, 현재 상태/존재를 재확인하여 생략. 기한 연장 없음 |
| EXPANSION_BLOCKED / TARGET_LIMIT | 대상 한도 초과. 부분 대상 저장 없이 root 미확장 상태를 유지하고 사유를 한 번 기록. lease 만료 후 재검사; 한도 조정 후 전체 확장 가능 |

결과 저장은 같은 attemptId/outcome/observedAt로 최대 3회 즉시 재시도하며 네트워크를 다시 호출하지 않는다. 모두 실패하면 이 worker 실행을 마치고 남은 READY를 보존한다. 이후 lease 회수에서 기존 MARKED는 UNKNOWN으로 남고 나머지 READY만 진행한다. 메모리 관찰값은 프로세스 종료를 견디지 않으며 그 경우 확정 결과를 합성하지 않는다.

DB 결과 commit은 성공했지만 응답이 유실된 경우 같은 결과 키 재대조/재저장은 idempotent다. 늦은 worker의 확정 결과는 attempt 키로 UNKNOWN을 해소할 수 있고 이전 UNKNOWN 관찰을 보존한다. root는 모든 대상 종결 후 DONE 또는 UNKNOWN이다. UNKNOWN root와 대상은 자동 replay/삭제하지 않는다. 연결 세대 UUID는 재등록마다 다르며 외부 SSE 계약에는 추가되지 않는다.

## 설정과 용량

아래는 로컬 구현용 잠정 기본값이다. 운영 값은 처리량, 메시지 크기, 연결 수, 이력 보존 기간, 디스크 여유와 장애 복구 시간을 측정한 뒤 별도로 확정해야 한다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| WAITING_SSE_DELIVERY_ENABLED | true | false면 worker 전송/claim을 멈추고 PENDING 기록은 계속 수용. legacy 전달로 넘어가지 않음 |
| WAITING_SSE_SCHEDULING_ENABLED | true | 자동 poll 활성화. false에서도 시험이 runOnce를 명시 호출할 수 있음 |
| WAITING_SSE_POLL_DELAY_MS | 100 | 한 번의 bounded poll 반환 뒤 다음 poll까지 간격. idle/error에도 적용 |
| WAITING_SSE_MAX_ROOTS_PER_POLL | 64 | 한 poll에서 처리할 root 수 상한. 양수 |
| WAITING_SSE_MAX_RUN_MILLIS | 250 | 다음 root 시작을 허용하는 실제 monotonic 경과시간 예산(ms). 양수, 이미 시작한 root 중단 보장 없음 |
| WAITING_SSE_LEASE_SECONDS | 30 | DB 시각 기준 claim lease. 유효한 mark마다 갱신 |
| WAITING_SSE_MAX_RETAINED_ROOTS | 100000 | 완료/실패/UNKNOWN을 포함한 전체 보존 root 수 한도 |
| WAITING_SSE_MAX_TARGETS_PER_ROOT | 10000 | root당 연결/생략 대상 수 상한 |
| WAITING_SSE_MAX_MESSAGE_BYTES | 16384 | 저장 JSON 메시지 UTF-8 byte 상한 |

`waiting_sse_capacity(id=1)`를 업무 TX에서 잠그고 중복 키 존재 확인, retained_roots 증가, root 생성을 같이 commit한다. 동시 admission이 한도를 넘지 않으며 두 번째/세 번째 root에서 실패해도 이미 만든 root와 업무/이력을 모두 rollback한다. 카운터는 완료 시 감소하지 않는다. 누적 이력도 무한히 커지지 않게 하기 위해 보존 root 한도를 선택했으며, **유지하면 언젠가 신규 웨이팅 업무를 제한한다**. 자동 삭제/보존기간/아카이브·카운터 차감 기능은 없으므로 운영 전 보존 정책을 확정해야 한다. SQL로 카운터만 낮추면 실제 저장량 보장이 깨진다.

한도 초과 응답은 `503`, `WAITING_SSE_CAPACITY_EXCEEDED`, `Retry-After: 30`이다. 메시지 크기 초과도 동일한 admission 실패다. 30초 뒤 용량이 자동 해제된다는 약속이 아니다. 관리자가 디스크 여유를 확인하고 한도를 늘려 재시작하면 기존 이력 보존 상태로 신규 수용이 재개된다. 낮춘 한도 아래로 기존 이력을 삭제하지 않으며 동일 root의 중복 저장은 한도가 차도 수용한다. 고객은 현재 상태를 조회한 뒤 재시도해야 하며 자동 `/call` 재시도 정책은 추가하지 않는다.

## 전환과 rollback

- V38 적용 후 단일 활성 SSE 인스턴스로 실행한다. 이 버전에는 웨이팅 legacy sender 활성화 옵션이 없다. 기록 검산은 `DELIVERY_ENABLED=false`로 수행할 수 있고 이 동안 알림 전달은 지연된다.
- 검산 후 같은 구현을 delivery=true로 재시작하면 PENDING/never-attempted를 처리한다. 기존 MARKED lease 만료는 UNKNOWN으로 보존한다. 여러 구/신 버전 인스턴스를 동시에 송신자로 운용하지 않는다.
- 긴급 중지는 delivery=false 및 해당 인스턴스의 정상 종료로 한다. 이미 send 진입한 스레드의 종료/완료 여부를 확인한다. 설정 변경만으로 진행 중 send가 철회되었다고 가정하지 않는다.
- 이전 바이너리로 돌아갈 때도 V38 테이블/행을 삭제하거나 MARKED/UNKNOWN을 PENDING으로 바꾸지 않는다. 먼저 신규 웨이팅 업무·송신을 멈추고 in-flight를 확인한 뒤 보존 자료를 대조한다. 기존 바이너리의 legacy 경로와 V38 worker가 함께 전송하지 않도록 운영자가 한 전달 주체를 정해야 한다. 불명 시도는 자동 replay 대상이 아니다. 구 버전으로 받은 새 사건에는 V38 보장이 적용되지 않는다.

## 운영 조회와 검증 범위

[scripts/waiting-sse-journal.sql](../scripts/waiting-sse-journal.sql)은 waiting_id·store_id·최대 500행으로 업무→root→대상→시도→append 결과를 조회한다. root 단계 관찰은 각 대상 행에 반복될 수 있으므로 관찰 건수 계산은 observation_id를 중복 제거한다. [health SQL](../scripts/waiting-sse-health.sql)은 보존 용량/실제 root 수, pending age, UNKNOWN/실패 사유, 기대/실제 대상 수 불일치와 확장 차단을 확인한다.

V39의 `waiting_sse_root_operator`와 `waiting_sse_target_operator`는 신뢰된 migration/table owner 권한으로 읽는 `security_barrier` view다. root는 필요한 열만, target은 `message`에서 추출한 `sse_event_id`와 안전한 대상 열만 노출한다. 두 원본 message 열을 직접 읽을 권한 없이 timeline·health를 실행할 수 있다. V38 원본 테이블·데이터·checksum은 변경하지 않았다.

인증 API나 행 단위 매장 권한 격리는 아니다. 쿼리의 waiting/store 필터를 우회해 view를 직접 조회하면 다른 매장의 안전한 관찰 열도 읽을 수 있으므로, 전체 원장 관찰이 승인된 감사 가능한 운영자에게만 부여한다. 매장별 DB 역할/RLS/JWT는 이 작업에 포함하지 않는다. 출력에서 원문을 제외하는 것과 원문 직접 접근 권한 차단은 별도로 확인한다.

운영자가 준비할 전제는 다음과 같다. Flyway는 역할을 만들거나 기존 역할에 권한을 부여하지 않는다.

- view 소유자는 원본 테이블을 읽는 신뢰된 migration/table owner이며 운영 조회 역할과 분리한다. view를 `security_invoker=true`로 바꾸지 않는다.
- 조회 역할은 비소유자·비슈퍼유저이며 owner 역할 membership, 상속되는 광범위 SELECT/쓰기 권한, schema CREATE, view 소유/변경 권한이 없어야 한다. PUBLIC/상위 역할에서 이미 message SELECT를 받았다면 안전한 view만 추가해도 그 권한이 제거되지 않는다.
- DB CONNECT, 앱 schema USAGE와 아래 권한만 필요하다. `public`은 기본 앱 schema 예시이고 `sse_operator`는 배포 운영자가 별도로 검토·준비한 역할을 뜻한다. 이 예시는 실제 운영 계정에 실행하지 않았다.

```sql
GRANT USAGE ON SCHEMA public TO sse_operator;
GRANT SELECT ON public.waiting_sse_root_operator, public.waiting_sse_target_operator TO sse_operator;
GRANT SELECT(id,store_id) ON public.waiting TO sse_operator;
GRANT SELECT(id,event_key,event_type) ON public.waiting_event TO sse_operator;
GRANT SELECT(id,target_id,status,marked_at,observed_at,customer_receipt)
    ON public.waiting_sse_attempt TO sse_operator;
GRANT SELECT(id,root_id,target_id,phase,reason,last_confirmed_stage,recorded_at)
    ON public.waiting_sse_observation TO sse_operator;
GRANT SELECT(id,retained_roots) ON public.waiting_sse_capacity TO sse_operator;
```

원본 `waiting_sse_root`, `waiting_sse_target`에는 SELECT를 줄 필요가 없고, `waiting_event.details`도 허용하지 않는다. INSERT/UPDATE/DELETE/시퀀스 권한을 주지 않는다. 조회용 트랜잭션의 read-only 설정만으로 쓰기 권한 거절을 대신하지 않는다. 실제 PostgreSQL 회귀는 위 최소 grant의 별도 LOGIN으로 접속해 timeline·health 성공, 두 message 열/업무 details 직접 조회 및 원본·view 쓰기 거절(SQLSTATE 42501)을 확인한다. 시험 역할/schema는 L 합성 DB에서만 생성 후 제거하며 운영 역할은 변경하지 않았다.

이번 제품 회귀는 실제 PostgreSQL에서 TX rollback, 동시 admission/claim/mark, lease/늦은 결과, 선기록/결과 저장 실패, 0/복수 연결, 설정·기한·새 호출·상태, 작은 풀, HTTP/SSE 계약, fresh V38·V39와 V37→V38·V38→V39 업그레이드를 검증한다. 테스트 wrapper가 실제 DB commit 뒤 예외를 주입한 사례는 제품 경계 회귀이며 DB 프로토콜 응답 유실이나 실제 JVM kill을 대신하지 않는다. 후속 최종 I 전체 회귀와 F 실제 프로세스/DB 프로토콜/연결/성능 검증은 별도 단계다.


### 회귀 drain의 종결 검사 — 리뷰 보완

시험 전용 `WaitingSseTestDrain`은 매장 또는 명시한 root 집합만 관찰한다. 기본 기대값은 모든 root DONE이며, 대상 확장 수 일치와 READY/MARKED/UNKNOWN 부재도 단언한다. 예기치 않은 CLAIMED/UNKNOWN이나 실행 횟수 제한 소진은 테스트 실패다. 의도한 장애는 호출부에서 정확한 root UUID와 PENDING/CLAIMED/UNKNOWN 기대 상태를 지정한다. 업무 TX 안에서는 worker를 실행하지 않고 `assertStates`로 선기록·rollback 상태를 검사한다. helper는 src/test에만 있으며 운영 JAR에 포함하지 않는다.
