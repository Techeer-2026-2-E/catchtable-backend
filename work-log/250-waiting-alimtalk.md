# 호출·재호출 알림톡과 발송 결과 복구

- 작업일: 2026-11-05
- 이슈: [#250](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/250)
- 정본: [기능17](https://app.notion.com/p/3d548d76fa3281dba92edea0ce84ce29), [기능25](https://app.notion.com/p/3d548d76fa328174ac06d1133438dbbb)
- 기반: `dev@11a645a4771d0a512731e58fe702f6255f4f2b5d`의 별도 브랜치. 기존 미커밋 원본은 보존한다.

## 범위와 결정

학습·포트폴리오용으로 실제 SOLAPI 연동 코드와 모의 검증을 제공한다. 대상은 웨이팅 호출·재호출이며 기존 수신 설정에서 필수 알림인 호출 정책을 재사용한다. 예약 상태 알림과 순번·점주 목록 갱신은 기존 SSE를 유지한다.

최초 포함 최대3회, 일시적 실패가 확정된 경우만 30초·2분 간격으로 재시도한다. 응답 유실은 `UNKNOWN`이며 새 발송 대신 알려진 메시지ID 조회나 인증된 웹훅으로 확인한다. 만료·종료되거나 새 호출로 대체된 건은 새 발송을 시작하지 않는다. 이미 접수된 메시지의 결과는 호출 종료 뒤에도 확인한다.

실발송은 기본 꺼짐이다. `simulation`은 HTTP 요청 없이 처리하는 학습 모드이며, `solapi`는 실제 HTTP 어댑터다. 이번 검증은 모의 HTTP 서버·가짜 키만 사용했다. SMS 대체 발송은 추가하지 않았고 요청에 `disableSms=true`를 명시한다.

## 변경과 경계

- `V32`에 발송 요청·수신자/내용 스냅샷·호출 차수·처리 모드·접수ID·시도 횟수·다음 시각을 저장한다. 기존 V1–V31과 V23 공백은 유지한다.
- 기존 호출 이벤트의 동기 리스너가 같은 업무 트랜잭션에서 요청을 저장한다. 롤백하면 업무 변경과 요청이 함께 사라진다. 기존 커밋 후 SSE 리스너는 수정하지 않았다.
- `(waiting_id, call_count, recall_count)` 유일 제약으로 동일 호출 이벤트를 중복 저장하지 않는다. 기존 `/call`과 `/recall`이 수락한 별도 동작은 별도 발송 요청이다. HTTP 요청용 멱등키 계약을 새로 도입하지 않았다.
- 조건부 UPDATE로 한 작업자만 발송 권한을 얻고, 발송과 조회는 트랜잭션·행 잠금 밖에서 실행한다. 작업 중 프로세스가 종료되면60초 후 `UNKNOWN`으로 복구한다.
- 발송할 때마다 현재 시각과 호출 상태를 확인한다. 재전송은 원래 기한을 유지하며, 처리 모드가 달라진 뒤의 기존 모의 요청은 SOLAPI 모드에서 발송하지 않는다.
- API 접수(`ACCEPTED`)와 최종 수신 확인(`SENT`)을 분리한다. 응답·웹훅의 요청ID/시도 차수·수신자·메시지ID를 대조하고, 이전 시도나 중복/늦은 실패가 최신 성공을 덮어쓰지 않는다.
- `POST /api/integrations/alimtalk/solapi`는 공급사 규격의 `X-Solapi-Secret`(설정 secret의 SHA1 해시)을 상수 시간 비교한다. 다른 서비스 메시지와 불일치 보고는 무시한다.
- 상태코드는 공식 문서 기준이다. `2000`/`3000`은 접수, `4000`은 성공이며, 확정된 서버/큐 오류만 제한적으로 재시도한다. HTTP5xx·408·응답 해석 실패는 결과 불명이다.

## 검증

새 전용 PostgreSQL16 컨테이너 `phase2-alimtalk-250-20261105-pg16`, loopback포트59834의 합성 테스트 DB만 사용했다. 기존 DB는 변경하지 않았다.

- 관련3클래스54개: 성공·확정 실패·수신 설정·롤백·중복 이벤트·동시 작업자·기한 경계·지연 배치·재시도 상한·응답 유실·프로세스 중단·초기 웹훅·수신자/차수 불일치가 실패/오류/건너뜀0으로 통과했다.
- 실제 `RestClient`가 loopback 모의 SOLAPI HTTP 서버로 발송하고, 공개 호출/재호출 API → DB 요청 → HTTP 접수 → 인증 웹훅 → 최종 기록까지 확인했다. 재전송 후 원래 기한도 보존했다.
- `DB_HOST=127.0.0.1 DB_PORT=59834 DB_NAME=alimtalk250_verified_test DB_USERNAME=catchtable DB_PASSWORD=catchtable TOSS_SECRET_KEY= ALIMTALK_ENABLED=false ./gradlew cleanTest build --no-daemon --console=plain`: 전체84클래스915개, 실패/오류/건너뜀0, bootJar 성공. 관련54개는 전체915개에 포함되므로 합산하지 않는다.
- 별도 문서 PR에서 기능17 로컬 검증·연동사항을 제공한다. 원격 source CI는 해당 PR의 실제 결과로 별도 확인한다.

## 확인하지 않은 부분

실제 SOLAPI 계정·승인 템플릿·문자/알림톡 수신 및 배포는 이번 학습 범위에 포함하지 않았다. 메시지ID를 받지 못한 결과 불명은 웹훅이 오지 않으면 미확인 상태로 남는다. 외부 접수 이후의 실제 수신 시각이나 정확히 한 번의 고객 수신을 보장하지 않는다.

## 공급사 근거

- [메시지 발송](https://solapi.com/developers/api/messages), [HMAC 인증](https://solapi.com/developers/api/authentication-api-key)
- [메시지 조회](https://solapi.com/developers/api/msg-getList), [상태 코드](https://solapi.com/developers/api/msgstatus), [웹훅](https://solapi.com/developers/api/webhook)
