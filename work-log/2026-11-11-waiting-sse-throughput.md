# 웨이팅 SSE root 사이 예약 대기 보완

F의 기존559파일 실행은 장애 matrix32/32를 통과했지만 연결0 부하에서 root backlog가 약1570까지 쌓이고 API 종료 뒤 drain이 188.502~191.646초 걸렸다. 원본 측정과 앞선377회귀를 보존하고 현재 `scheduled()`가 root마다 fixedDelay100ms를 기다리는 경로를 확인했다. 이 작업은 같은 전송 주체의 예약 실행량만 보완하며 정책·Journal/Repository·schema V1~V39·업무/API/결제/알림톡은 변경하지 않았다.

한 poll에서 기본 최대64 root, 실제 경과250ms까지만 순차 처리한다. root 사이 `System.nanoTime()`으로 확인하고 미리 claim하지 않는다. 전체 poll에 같은 세마포어1개를 사용하며 public runOnce는 한 root, Worker 생성자는 그대로다. properties는 두 설정을 추가하고 기존6인자 생성자는 기본64/250에 위임하도록 유지했다. 빈 큐·확장 보류·DB 예외·결과3회 저장 실패는 즉시 poll을 끝낸다. 다음 root 전 비활성/interrupt를 확인하며 이미 막힌 root/send를 시간 예산으로 강제 중단한다는 보장은 없다. [동작·설정](../docs/waiting-sse-durability.md)에 기본값의 잠정 성격을 설명했다.

실제 PostgreSQL의 새 L 스키마에서 관련 **34클래스391개(기존377+신규14), 실패/오류/skip0**을 실행하고 bootJar를 생성했다. 새12개 단위 회귀는 count/time·idle/error·결과 저장 실패·한 permit/runOnce·disable/interruption·설정 검증을 다룬다. DB회귀2개는 한 poll의 여러 commit/전송/결과, 저장 실패 후 이번 poll 보류와 UNKNOWN 무재전송·다음 never-attempted 처리다. 기존 원자성·lease·동시성·작은 풀·권한·migration·payload 검증도 함께 통과했다.

앞선 새 스키마 실행은 43개 중31개가 public pg_trgm 연산자를 찾지 못해 초기화 실패했다. 두 번째는42개가 통과하고 권한 시험1개가 URL currentSchema가 별도 LOGIN 속성을 덮어써 실패했다. 실패 스키마와 XML을 보존하고, 최종 새 스키마는 Hikari `data-source-properties.currentSchema`에 `l_throughput_reg03_20261111,public`을 지정해 URL 우선순위 충돌을 없앴다. 이 두 환경 실패를 제품 통과로 바꾸거나 기존 권한 시험을 완화하지 않았다.

소규모 before→after 비교는 보존559파일을 자기 build/baseline에 복사해 같은 Java21, DB55436, pool2, 고정 업무 Clock, Journal·TX·16-root warmup 및60-root backlog로 순차 실행했다. [별도 도구](../scripts/waiting-sse-throughput/README.md)는 기록용 emitter와 worker만 관찰하며 HTTP/브라우저 수신 성능은 아니다.

| 조건 | 이전→수정 drain ms | poll 수 | worker SQL | worker commit |
| --- | ---: | ---: | ---: | ---: |
| 연결0 | 7069.373→447.965 | 60→2 | 900→901 | 240→241 |
| 기록용 emitter1연결 | 7509.819→781.975 | 60→3 | 1620→1621 | 420→421 |

추가 SQL/commit1은 마지막 빈 큐 확인이다. 모든 의무 root/target/attempt와 호출 수가 일치하고 pending/UNKNOWN/확장 불일치는0이었다. 이전 poll 사이 gap·관찰 시간은 약6.13~6.15초였고 수정본은0.10~0.21초였다. 수정본 root별 실제 실행 시간도 달라졌으므로 전체 차이를 모두100ms 제거량으로 단정하지 않는다. 단일 호스트·조건당1회인 인과 비교이며 새로운 운영 SLO나 최종 독립 F 성능 결과가 아니다.

모든 실행은 Java21·Gradle offline/no-daemon/max-workers2였고 새 원시는 `build/sse-throughput-20261111/`에 있다. 기존559 소스·377회귀와 F 느린 측정 원본을 보존했다. 신규 공유 증거/복구와559 대비 delta·542 대비 누적 delta를 관리자에게 제공한다. 자기 Gradle/JVM/JDBC·임시 fake를 종료하고 기존 DB와 새 스키마·자료를 보존해 L 슬롯을 반환한다. 수정본 F 장애/실제 연결/성능 재검증과 최종 I 전체 통합은 후속이며 commit/push/배포는 없다.
