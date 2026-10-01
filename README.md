# catchtable-backend

Techeer 2026-2 E팀 

## 로컬 예약 API 확인

고정 목 계정은 고객 `id=1`, 점주 `id=2`를 사용한다. 운영·CI에는 목 데이터를 자동으로 넣지 않는다.

```bash
docker compose up -d
./gradlew bootRun
```

애플리케이션이 시작되어 Flyway 적용이 끝나면 다른 터미널에서 목 데이터를 넣는다.

```bash
docker compose exec -T postgres psql -U catchtable -d catchtable < scripts/local-seed.sql
```

목 매장은 `id=1`이고 매일 09:00~22:00에 운영한다. 2인·4인·6인 테이블이 하나씩 생성된다. 같은 목 데이터에는 재실행할 수 있다. 기존 계정·매장·테이블·영업시간이 목 데이터와 충돌하면 전체 삽입을 롤백하므로, 기존 데이터를 유지하고 별도의 빈 로컬 DB에서 실행한다.

## 점주 예약 취소 알림

점주가 예약을 취소하면 커밋 후 예약 고객에게 `RESERVATION_CANCELED` SSE 이벤트를 한 번 보낸다. `targetId`는 예약 ID이며 `message`에 취소 사유가 포함된다. 같은 취소를 재요청해도 알림을 다시 보내지 않는다.

클라이언트는 알림 수신 또는 SSE의 `connect` 이벤트 수신 후 `GET /api/user/reservations/{reservationId}`를 호출해 최신 상태와 취소 사유를 갱신한다. 기존 P1 고객 API처럼 `X-Member-Id` 헤더를 사용하며 본인 예약만 조회할 수 있다.
