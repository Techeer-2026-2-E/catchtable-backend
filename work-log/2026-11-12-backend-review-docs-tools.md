# 백엔드 검토 후 문서·역사 도구 보완

2026-11-12. 최종 I567 고정본을 별도 로컬 작업트리에 대조·복사한 뒤 문서와 도구만 보완했다. 제품·테스트·migration·빌드 의존성·현재 `verify_sse.py`는 수정하지 않았다.

## 문제와 변경

- [C 비교 도구](../scripts/benchmarks/waiting-history/README.md)는 현재 소스를 복사하면서 제품에서 제거된 `WaitingSseObservationRecorder`를 요구했다. 이제 명시한 옛 baseline316/modified322 snapshot만 받으며, 고정 canonical map과 실제 선택 파일의 전체 SHA·추가/누락·안전한 경로·일반 파일 여부를 검사한다. 현재 durable SSE는 출력·런타임·DB 접근 전에 거절한다. `--check-only`는 파일만 읽는다. 옛 Java 단언과 분석기 기대행·설정 동등성은 그대로다.
- 당시 C의294개 회귀, SSE31,200행/전체35,400행 및 warmup 포함38,940행·누락0, p95 9.326→10.478ms(+12.35%)는 **V36 best-effort writer의 보존 결과**다. 현재 영속 SSE의 측정이 아니다. 과거 손실 결과도 그대로 남겼다. 현재 계약은 [영속 전달 원장](../docs/waiting-sse-durability.md), 현행 검증·측정 경로는 [통합 도구](../scripts/backend-improvement-integration/README.md)와 [SSE throughput 도구](../scripts/waiting-sse-throughput/README.md)를 따른다.
- [알림톡 호출·재전송](../docs/frontend/feature-17-alimtalk.md), [수동 노쇼](../docs/frontend/feature-49-waiting-no-show.md), [점주 취소](../docs/frontend/feature-50-owner-waiting-cancel.md)에 503 `WAITING_SSE_CAPACITY_EXCEEDED`, `Retry-After: 30`(자동 해제 보장 아님), 관련 TX rollback과 상태 재조회 후 안전 재시도, commit 후 비동기 SSE를 추가했다. 기존 권한·성공 응답·호출/재전송 차수·기한·no-op·사유 정정은 유지한다. 기능44의 기존 `/call` 안내도 연결했다.
- 통합 README는 현행 `verify_sse.py`를 먼저 안내한다. `verify.py`는 고정 V37의 V35→V37·restart 전용으로 분리하고, 출력 생성·probe import·build·DB 전 `src/main`325 지문 guard를 추가했다. 과거36행 단언을38행으로 바꾸지 않았다. 현행 도구가 AST로 읽는 합성 seed SQL도 동일하다.

## 파일 기반 검증과 경계

- I567을 가져오기 전 archive/리뷰 manifest/원본을 대조했고, 가져온 뒤567개 SHA·bytes가 모두 일치했다.
- Python AST9개와 파일 기반 guard30건이 통과했다. 옛 C 양쪽의 정상 인식·파일만 복사, 변조·누락·추가 Java·symlink·경로 탈출·위조 map·잘못된 variant·현행 durable 입력 거절, runner의 변경 입력 조기 거절을 확인했다. 고정 V37의 upgrade/restart 사전검사 성공과 현재 V39의 두 모드 거절도 확인했다.
- 검산 실행에서 Java/Gradle·Docker/하위 프로세스·socket 진입을 trap했다. 정상 `--check-only`와 거절 입력은 작업트리 build의 파일/디렉터리 지문을 바꾸지 않았고, V37 도구의 거절도 지정 출력과 기존 sentinel을 그대로 두었다. 파일 검사가 측정·앱·DB 성공을 뜻하지 않는다.
- 상대 링크·명령/설정·controller/handler/Journal/업무 호출과 no-op 경로, `git diff --check`를 대조했다. 현재 제품/테스트·기존 Java harness/분석기/`verify_sse.py`의 원본 동일성을 확인했다. 현행 통합 명령은 I 소유 label·DB55435/앱18085/fake19085를 코드에서 강제하는 고정 환경 전용임을 명시했다. 다른 격리 환경은 관리자 소유·포트·경로 조정 외부 사본의 치환 지문과 guard 검증 및 별도 슬롯이 필요하며 환경변수만으로 전환되지 않는다. 보존한 C 본 결과는 엄격 분석을 다시 통과했고 기존 손실 결과는 계속 종료1·FAILED로 거절됐다. 이 두 건은 기존 결과 파일 검산이며 새 측정이 아니다.

앱·Gradle·DB·부하·실제 Toss/SOLAPI·공유 DB·다운로드·외부 접속·게시를 수행하지 않았다. 당시294개나 I의 보존1,126개를 이번 재실행으로 세지 않는다. 원본 I와 이전 증거는 읽기만 했다. 최신 dev 결합·런타임 검증 및 독립 재리뷰는 관리자 후속이며, 이 문서 보완으로 PR 게시·CI·프런트 연동·배포 완료를 선언하지 않는다.
