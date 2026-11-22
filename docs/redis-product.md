# 예약 가능 시간 캐시와 웨이팅 접수 장부

날짜별 예약 가능 시간은 앱들이 같은 Redis에서 읽는다. 웨이팅은 PostgreSQL이 발급한 접수 순서대로 처리하고, 별도의 Redis가 처리할 매장·접수일을 워커에 알린다. 신규 스키마만 적용한 상태에서는 예약 조회와 웨이팅이 기존 경로를 사용한다. 고객 요청과 반환 필드는 [웨이팅 API 인계](frontend/waiting-receipt-lifecycle.md)에 있다.

## 켜는 단위와 장애 중 동작

| 설정 | 동작 |
| --- | --- |
| `availability.cache.enabled`, `availability.cache.uri` | 기본 꺼짐. 날짜별 `GET /api/user/stores/{storeId}/availability`만 적용한다. 모든 앱의 캐시 URI와 prefix를 맞춘다. |
| `availability.cache.db-permits` | Redis 장애 때 동시에 DB 계산을 시작할 수 있는 앱별 대표 요청 수. 초기값 1. 초과하면 503과 재시도 간격을 반환한다. |
| `waiting.dispatch.enabled`, `waiting.dispatch.uri`, `waiting.dispatch.sentinel-password` | 워커 기본 꺼짐. 전환 전 모든 앱에 같은 대기열 연결 정보와 워커 설정을 적용한다. 앱 기본값과 제공 구성의 Sentinel 그룹 이름은 `ct-waiting`이다. 데이터 노드와 Sentinel 인증값은 런타임 비밀로 전달한다. |
| `waiting_intake_policy`, `waiting_intake_day` | 매장에서 새 방식을 한 번 켜면 그날부터 이어진다. 서울 날짜가 바뀐 뒤 첫 신청이 그날의 경로와 미완료 한도를 고정한다. Redis 대기열이 중단돼도 PG 여유 안에서 신규 수락하고, 가득 차면 429를 준다. 기존 표의 조회·재시도·취소는 계속 처리한다. |
| `waiting_registration_permit` | 번호 발급 트랜잭션이 커밋할 때까지 허가 행을 잠근다. V48의 초기값은 DB 전체 2개다. 장부를 사용하는 기간에는 기존 경로의 번호 발급도 같은 허가를 사용한다. |
| `waiting.intake.batch.enabled`, `waiting.intake.async.enabled` | 기본 꺼짐. 짧은 묶음 커밋은 매장별 최대 32건·최대 2ms가 초기값이다. 비동기 HTTP는 PG 커밋을 기다리는 동안 서블릿 스레드를 반환한다. 동시에 보관하는 요청은 앱의 `server.tomcat.threads.max` 이하로 제한한다. `waiting.intake.async.max-in-flight`로 더 줄일 수 있다. 자리가 차면 기존 동기 경로를 사용한다. |
| `waiting.dispatch.immediate`, `waiting.dispatch.poll-hint-seconds`, `waiting.dispatch.max-poll-seconds` | 기본 즉시 발급 시도를 켠다. 미완료 표의 다음 조회 간격은 기본 2~10초다. 순번·미완료 수에서 추정한 앞선 신청 수를 사용한다. |

장부 한도, 허가 수, 응답·회복 지연은 실험 후보값을 사용했다. 운영 확대는 별도 결정이다. 배치와 비동기 HTTP는 각각 끌 수 있다. 로컬 측정 때 앱 3대 합계 CPU 3개, heap 768MiB, Hikari 12개를 사용했다.

### 접수와 복구

1. 같은 매장·날짜의 장부 행에서 수용량과 다음 순번을 갱신한다. 표 ID, 회원·요청 키 및 최초 순번이 같은 PG 트랜잭션에서 커밋된 뒤 응답한다. 커밋 전 실패는 자리를 차지하지 않는다. 커밋 뒤 응답이 끊기면 같은 키로 원래 표를 조회한다.
2. Redis sorted set의 항목은 매장·접수일이다. Redis에 게시만 남거나 게시가 유실된 경우에도 DB에서 실제 미완료 선두·회원·순번을 확인한다. 장부에 없는 고객을 Redis에서 발급할 경로는 없다.
3. 워커는 매장별 PG 잠금, 만료 가능한 처리 권한, 트랜잭션 전체에 걸친 공통 등록 허가를 사용한다. 같은 매장의 선두가 막혀 있으면 뒤 신청이 앞서갈 수 없다. 실제 웨이팅·기존 알림 작업·표와 식당 번호의 연결은 한 트랜잭션에서 기록한다. 취소자는 PG 장부에 의사를 저장할 수 있고, 중단된 처리자와 기존 취소 결과를 대조한다.
4. 새 대기열 연결에서 미완료 매장·일자를 PG에서 다시 읽은 뒤 일을 가져온다. 이후에도 주기적으로 누락을 채운다. AOF에 마지막 게시가 없거나 저장된 힌트가 오래됐어도 최초 순서는 장부에 남아 있다.
5. 매장 전환 함수 `enable_waiting_intake(store_id, effective_date, capacity)`는 이미 등록을 시작한 기존 경로를 보호한다. 이후 날짜는 같은 정책과 한도를 상속하고, 여러 앱이 동시에 첫 신청을 받아도 매장 잠금 안에서 당일 장부를 한 번 만든다. 다음 날짜부터 다른 한도를 예약할 수 있다. V41~V48에서 직접 만든 일자 행은 해당 날짜의 선택으로 보존한다. 기존 시범 매장을 이후 날짜에도 계속 운영하려면 위 함수로 시작일을 지정한다.
6. 상시 설정을 원복하는 `retire_waiting_intake(store_id, received_date)`는 그날까지의 미처리 표·취소·실제 번호와 순서를 대조한다. 미완료 상태가 있으면 false를 반환하고, 성공하면 해당 일자부터 기존 경로로 받는다. 이미 발급한 표의 URL과 키는 유지한다. 일회성 날짜 설정만 있던 기존 매장의 원복은 원래 날짜 범위를 사용한다. 로컬 `rollout.py expand`는 검증 판정이 부족하면 코드 2로 종료하며 매장을 추가하지 않는다.

### 예약 가능 시간

Redis 값 하나가 매장·서울 조회일·인원 수에 해당하는 하루 슬롯 JSON이다. PG를 읽기 전에 확보한 버전을 끝까지 사용한다. 버전 표식이 만료·축출되면 새 UUID를 사용한다. 계산이 늦게 끝나도 새 버전의 값을 덮어쓰지 않는다. 같은 버전·키의 동시 미스는 앱마다 하나만 계산한다. 앱 내부에는 진행 중인 계산만 공유한다.

예약·선점·취소·이동에 해당하는 이전/새 이용 구간과 전날 시작 슬롯을 포함해 무효화한다. 테이블·영업시간·예약 정책 변경은 매장 전체 인원에 적용한다. PG 업무 트랜잭션이 무효화 기록도 함께 저장한다. 커밋을 관측한 무효화 작업이 Redis 버전을 교체하며, 기본 확인 주기는 100ms다. 이 사이와 Redis 장애 중 신선도 경계는 계산 시작부터 최대 2초다. 서울 자정, 슬롯 예약 마감, 보호되지 않은 선점 만료가 더 빠르면 일찍 만료된다. 읽기로 TTL을 연장하지 않는다. 선점의 성공 여부는 기존 PostgreSQL 잠금과 시간 중복 배제 제약으로 결정한다.

## 호스트 구성

설계 구성은 A에 비영속 캐시와 Sentinel 1, B에 대기열 주 노드와 Sentinel 2, C에 복제 노드와 Sentinel 3이다. B와 C는 AOF everysec, Sentinel 정족수는 2다. 세 호스트는 서로의 사설 주소로 접근할 수 있어야 한다. 각 호스트에서 `CT_DATA_ROLE`을 `cache`, `primary`, `replica`로 각각 정하고 `CT_PRIVATE_BIND`, `CT_PRIVATE_ANNOUNCE`, `CT_INITIAL_QUEUE_HOST`, `CT_REDIS_SECRET_FILE`을 지정한다. 데이터 포트는 6379, Sentinel 포트는 26379다. 승격 뒤에도 마지막 역할 및 설정을 읽도록 데이터 노드와 Sentinel 설정은 쓰기 가능한 볼륨에 둔다. [호스트별 Compose](../infra/redis/compose.yaml)

장비 두 대에서 앱·PG·발생기는 Mac mini, Redis는 MacBook의 Linux VM 세 대에 배치해 검증했다. 각 VM은 별도 커널과 디스크를 사용했다. 대기열 주 노드가 들어 있는 VM 전체를 강제 종료하고 두 Sentinel의 승격 및 기존 주 노드의 복제 재가입을 확인했다. 캐시와 Sentinel 하나가 있는 VM 전체를 종료한 동안에도 웨이팅 번호를 발급했다. 이 배치는 MacBook 한 대의 전원 상실을 견디는 물리적 격리까지 증명하지 않는다. [제품 작업로그](../work-log/2026-11-22-redis-product.md)

## 로컬 재현

전용 작업트리의 저장소 루트에서 실행한다. Java 21, Go, Python 3.10 이상과 로컬 Docker Desktop `desktop-linux` 컨텍스트, 로컬 이미지 `postgres:16`, `eclipse-temurin:21-jre`, `redis:7-alpine`을 사용한다. PG는 루프백 55486, 임시 앱은 18681~18683을 쓴다. 도구는 소유 라벨·컨테이너 ID·포트를 대조하고 난수 네트워크에 합성 fixture·회차별 DB를 만든다. 보관된 원시 자료와 기존 실험 DB가 있는 트리는 새로 초기화하지 않는다.

```sh
./gradlew bootJar
python3 tools/redis-product/harness/lab.py init
python3 tools/redis-product/redis_lab.py init
python3 tools/redis-product/default_group.py
python3 tools/redis-product/storage_recovery.py
python3 tools/redis-product/registration_slots.py
python3 tools/redis-product/check.py
python3 tools/redis-product/cache_edges.py
python3 tools/redis-product/rollout.py scenario
python3 tools/redis-product/redis_lab.py stop
python3 tools/redis-product/harness/lab.py stop
```

위 명령은 기능 경계만 확인한다. 소규모 HTTP 표본이 필요하면 중지 전에 `python3 tools/redis-product/campaign.py probe --apps 3 --arm both`를 한 번 실행한다. 완료 표본, 접근 로그, PG 장부·번호, 풀·락을 `build/intake-lab/raw/`의 같은 회차 폴더에서 대조한다. 전체 반복 캠페인은 별도 명령이다.

원시 자료 경로는 `CT_PRODUCT_RAW`로 지정할 수 있다. 테스트용 Docker 네트워크는 기존 네트워크와 겹치지 않는 서브넷을 사용하고 Redis와 Sentinel에 고정 IP를 부여한다. VM을 쓸 때는 권한을 제한한 로컬 JSON 파일(`cache_uri`, `queue_uri`, `sentinel_password`)을 `CT_PRODUCT_REDIS_ENDPOINTS`로 전달한다. 접속값을 로그나 Git에 넣지 않는다.

앱 3대의 1천·5천·1만 명/10초와 앱 1대·3대의 대표 오픈 1만 명/1초를 각각 세 번 확인하려면 `python3 tools/redis-product/suite.py run --core`를 사용한다. 같은 환경에서 `report --core`는 회차·응답별 표본과 분위수를, `gate --core`는 후보 기준 통과 여부를 출력한다. 검증한 JAR과 수집한 표본 수는 회차 원본에 남는다.

### 가상 호스트 장애 재현

각각 별도 디스크를 가진 전용 Linux VM 세 대에 위 호스트 구성의 A·B·C 역할을 배치한다. 세 노드는 서로의 고정 사설 주소를 직접 사용할 수 있어야 한다. 이 시험은 각 게스트를 강제 종료한다. 측정기는 Lima 실험 인스턴스 이름(`ctr49-<임의의 16진수 6자>-a/b/c`)과 사전에 기록한 커널 부팅 ID를 대조한다.

1. 가상망에서 각 게스트에 `<인스턴스 이름>.test` 이름을 붙인다. 테스트 앱도 같은 이름으로 Redis에 접속한다. 서로 다른 장비 사이에서 터널을 사용할 때는 데이터 6379와 Sentinel 26379를 각각 전달한다. 주 노드는 B, 복제는 C, 캐시는 A에서 시작한다.
2. 빈 당일 장부를 가진 전용 fixture DB에 앱 세 대를 띄우고, 매장 1을 `SELECT enable_waiting_intake(1,current_date,16)`으로 켠다. 로컬 포트 세 개를 이 앱들에 연결한다. 합성 회원 600~603을 사용한다.
3. JSON manifest에 A·B·C 순서의 `names`, 제어 장비에서 Redis로 접속할 사설 IP 배열 `vz`, 각 게스트의 `/proc/sys/kernel/random/boot_id` 배열 `boot_ids`를 기록한다. Redis 암호는 별도 파일로 전달한다.
4. `python3 tools/redis-product/vm_failover.py --manifest <manifest.json> --password-file <별도 파일> --date <서울 내일 날짜> --ports 18791 18792 18793 --record <새 결과.json>`을 실행한다. 승격과 실제 번호 관측 시간은 강제 종료 명령을 시작한 때부터 측정한다. B의 복제 복귀와 A의 비영속 캐시 재시작을 확인하고, 마지막으로 장부·식당 번호·취소 행을 대조한다.
