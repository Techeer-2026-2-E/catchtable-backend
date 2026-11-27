# 백엔드 시스템 지표 모니터링

로컬에서 JVM·커넥션 풀의 상태와 HTTP 지연을 함께 확인하고 같은 대시보드를 EC2에서 재사용하도록 구성했다. 기존 배포에 모니터링용 계정이나 포트 변경을 강제하지 않도록 선택형 [Compose 설정](../deploy/compose.monitoring.yml)을 추가했다. 활성화하면 관리 엔드포인트는 컨테이너 내부 8081로 이동한다. 설정과 접속 절차는 [배포 안내](../deploy/README.md#grafana-모니터링)에 있다.

## 로컬 검증 (2026-11-27)

- Docker Desktop에서 별도 Compose 프로젝트, PostgreSQL 16, Redis 7, 빈 Grafana·Prometheus 볼륨으로 시작했다. 앱 이미지 빌드와 `./gradlew bootJar`가 성공했다.
- `docker compose config --quiet`와 `promtool check config /etc/prometheus/prometheus.yml`이 통과했다. 내부 health는 UP, API 포트에서 `/actuator/prometheus`는 404였다. 격리된 Redis를 정지하자 내부 health가 HTTP 200과 DEGRADED를 반환했고, 다시 시작한 뒤 UP으로 복구했다.
- 격리된 DB에 기존 로컬 목 데이터를 넣고 카테고리·예약 가능 시간 조회 52건의 HTTP 200 응답을 확인했다. Grafana에 등록된 데이터 소스를 통해 대시보드의 실제 PromQL 10개를 조회했다. 지표 수집 상태는 1이었고 HTTP 요청량과 p95가 양수였다. GC가 발생하기 전 구간에는 GC 정지 시간 시계열이 비어 있었다.
- Prometheus와 Grafana 컨테이너를 재생성한 뒤 같은 시각의 10개 쿼리를 다시 조회해 각 측정값이 유지됐음을 확인했다.
- `./gradlew test --tests 'com.catchtable.global.redis.RedisHealthIndicatorTest' --tests 'com.catchtable.waiting.service.WaitingIntakeBatcherTest'`에서 5개 테스트가 통과했다. 검증 범위는 로컬 패키징·선택 테스트·지표 수집과 조회다. 전체 테스트와 CI·EC2 배포는 별도다.
