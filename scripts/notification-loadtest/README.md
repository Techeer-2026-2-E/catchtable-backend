# 실시간 알림 현황 측정 (k6)

고도화 전 현재 SSE 알림 구조의 문제를 숫자로 확인하는 스크립트. 앱 코드는 바꾸지 않고, 전용 DB와 별도 포트에서 돌린다.

| 실험 | 확인하는 문제 | 핵심 지표 |
| --- | --- | --- |
| `exp1-offline-loss.js` | 접속 안 한 동안 온 알림은 사라지고, 나중에 볼 API도 없다 | 발생 N건 / 수신 0건 / 목록 API 404 |
| `exp2-no-history.js` | 접속 중 받은 알림도 재접속하면 다시 못 본다 | 1차 수신 N건 / 재접속 후 0건 |
| `exp3-reconnect-gap.js` | 연결이 끊겼다 붙는 사이(서버 30분 타임아웃 → EventSource 3초 뒤 재연결)의 알림이 유실된다 | 기대 vs 실제 수신, 유실률 |
| `exp5-call-latency.js` | 점주 `/call` 응답 시간이 접속 인원에 비례해 늘어난다 (전송이 요청 스레드에서 동기) | N별 p50·p95 |
| `exp6-position-storm.js` | 대기열 변경마다 순번이 바뀌었는지와 상관없이 전원에게 순번 알림이 나가 전송량이 폭증한다 | 총 전송 건수, 1인당 수신 건수 |

실험 4(서버 재시작 시 전부 유실)는 스크립트 없이 exp3를 돌리는 중에 앱을 껐다 켜면 된다. 재시작 후 재연결해도 그 사이 알림은 돌아오지 않는다.

## 준비 (한 번)

```bash
docker compose up -d postgres
docker compose exec -T postgres createdb -U catchtable catchtable_loadtest
```

앱을 전용 DB·별도 포트로 띄운다 (Flyway가 빈 DB에 스키마를 만든다). 알림톡은 기본값이 꺼져 있어 외부 발송이 없다.

```bash
DB_NAME=catchtable_loadtest ./gradlew bootRun --args='--server.port=8089'
```

앱이 뜬 뒤 시드를 넣는다. `N`은 고객 수(실험의 `N` 이상이어야 한다).

```bash
docker compose exec -T postgres psql -U catchtable -d catchtable_loadtest -v N=500 -f - < scripts/notification-loadtest/seed.sql
```

k6는 Homebrew(`brew install k6`) 기준. SSE 확장(`k6/x/sse`)은 첫 실행 때 자동으로 받는다.

## 실행

실험 사이에는 반드시 웨이팅을 비운다. 호출은 웨이팅 1건당 1번만 되고, 순번 알림은 매장의 ACTIVE 웨이팅 전원에게 나가므로 남은 웨이팅이 다음 실험의 수치를 바꾼다.

```bash
docker compose exec -T postgres psql -U catchtable -d catchtable_loadtest -f - < scripts/notification-loadtest/reset.sql
```

```bash
k6 run -e N=100 scripts/notification-loadtest/exp1-offline-loss.js
```

```bash
k6 run -e N=100 scripts/notification-loadtest/exp2-no-history.js
```

```bash
k6 run -e N=100 -e CALLS=50 scripts/notification-loadtest/exp3-reconnect-gap.js
```

```bash
k6 run -e N=10 scripts/notification-loadtest/exp5-call-latency.js
```

exp5는 `N=10`, `100`, `500`으로 반복해 응답 시간 추세를 비교한다.

```bash
k6 run -e N=200 -e CALLS=50 -e CALL_RATE=5 scripts/notification-loadtest/exp6-position-storm.js
```

옵션(`-e`): `BASE_URL`(기본 `http://localhost:8089`), `N` 구독자 수, `CALLS` 호출 수(`N` 이하), `CALL_RATE` 초당 호출, `RECONNECT_EVERY`·`RECONNECT_DELAY`(exp3, 초).

## 결과 읽기

- 터미널 끝의 `==== 결과 ====` 블록이 발표용 요약이다. 같은 내용이 `results/<실험>-<시각>.json`에 남는다 (git 제외).
- 알림 이름은 서버 `NotificationType` 그대로다. `WAITING_CALLED`는 호출 당사자 1명, `WAITING_POSITION_CHANGED`는 호출마다 ACTIVE 전원에게 간다. 그래서 기대 수신 = 호출 수 × N.
- 호출된 웨이팅은 `CALLED`가 되어도 ACTIVE로 남아 순번이 바뀌지 않는다. 이 실험들의 순번 알림은 전부 같은 순번을 다시 보낸 것이다. 순번이 실제로 바뀌는 입장·취소·노쇼는 실험에 쓰지 않았다.
- exp5는 호출이 웨이팅 1건당 1번이라 `CALLS`가 `N`을 넘을 수 없다. 표본이 적으니 p95는 참고값으로 본다.
- 로컬에서는 클라이언트 N개와 서버가 CPU를 나눠 쓴다. exp5의 절대값(ms)은 환경에 따라 달라지므로 N에 따른 증가 추세만 근거로 쓴다. 나머지 실험의 건수는 환경과 무관하다.
- 실험 3의 끊김 주기는 실제(서버 30분 타임아웃, EventSource 3초 재연결)를 짧게 줄인 것이다. 유실률 이론값은 `RECONNECT_DELAY / (RECONNECT_EVERY + RECONNECT_DELAY)`.

## 정리

```bash
docker compose exec -T postgres dropdb -U catchtable catchtable_loadtest
```

## 참고: k6 SSE 확장 동작

`sse.open`은 연결이 닫힐 때까지 VU를 막는다. 서버가 보내는 하트비트가 없어 조용한 연결은 스스로 끝나지 않으므로, `timeout`으로 끊고 `error` 핸들러에서 `client.close()`를 호출해야 돌아온다 (`lib.js` `subscribe`). 이 타임아웃이 서버 타임아웃·네트워크 끊김의 대역이다.
