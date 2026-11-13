# 웨이팅 SSE scheduler 소규모 인과 비교

전용 L 실행 슬롯에서만 실행한다. 고정 559파일을 자기 `build/` 아래에 복사한 baseline과 현재 코드를 순서대로 실행한다. 기존 원본과 F 도구/느린 측정값을 변경하지 않는다. Java21·캐시 Gradle offline을 사용하며 `ct_sse_l` DB55436의 **새** `l_tput_*` 스키마만 만든다. 기존 스키마/결과 경로가 있으면 실패하며 삭제·재사용하지 않는다.

```sh
python3 scripts/waiting-sse-throughput/run-probes.py \
  --baseline build/sse-throughput-20261111/baseline \
  --output build/sse-throughput-20261111/comparison-01 --tag r01
```

각 조건은 16 root를 직접 한 개씩 warmup하고 동일한 60 PENDING root를 만든 뒤, 한 스레드에서 `scheduled()`를 fixedDelay100ms로 실행한다. 0연결과 기록용 emitter1연결을 각각 before→after 순서로 한 번 비교한다. 같은 Java/DB/풀2/고정 업무 Clock/Journal/Repository/TX/fixture이며 별도 source set은 운영 JAR에 들어가지 않는다. 실제 send 호출은 TX·DB 연결 밖인지 확인한다.

측정값은 전체 drain, poll별 처리 root 수/실제 monotonic 실행 시간, scheduler 호출 사이 gap, worker JDBC 실행 SQL·성공 commit/rollback 수다. JDBC 카운터는 worker 호출 범위만 세며 fixture·마이그레이션·관찰 SELECT·trigger 내부 SQL은 제외한다. 완료 후 root/대상/시도/UNKNOWN/확장 수·호출 수를 실제 DB와 대조한다. warmup 이력과 결과 스키마를 보존한다.

이 fixture는 HTTP/API 요청 부하나 실제 브라우저 SSE 수신을 측정하지 않는다. 기록용 emitter의 메서드 호출을 고객 수신으로 표현하지 않는다. 작은 기존 backlog에서 root당 fixedDelay의 인과를 확인하는 도구이며, 최종 독립 F 실제 장애·연결·성능 비교와 운영 SLO 판단은 별도다. burst 시간 예산은 root 사이에만 적용되어 이미 막힌 root/send를 강제로 중단하지 못한다.
