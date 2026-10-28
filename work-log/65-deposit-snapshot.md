# 기능10 — 예약금 조건 스냅샷 저장 경계

- 작업일: 2026-10-28
- 이슈: [#65](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/65)
- 정본: [기능10](https://app.notion.com/p/3d548d76fa32819386a3e858c26b2c1b), [기능33](https://app.notion.com/p/3d548d76fa3281d48af9ea164f74c563)
- 기반: `dev@10b434ca2dc979cdfd90b40cd87ee7092e9ce290`와 기능58 `cd3093eb0af884b57fada9bb45d08a780d85d652`를 전용 브랜치에서 통합했다. 공통 Payment·Toss 클라이언트·V19는 이윤서의 변경이며 이 작업의 구현으로 계산하지 않는다.

## 문제와 변경

예약에 최초 예약금 조건을 보관할 필드가 없어 이후 예약 변경·환불 예상 조회가 사용할 원본이 없다. `depositAmountSnapshot`과 `refundPolicySnapshot`을 추가하고, JPA에서 생성 이후 갱신하지 않는다. `V20`은 금액 음수·정책 누락·미지원 정책을 DB에서도 차단한다. 기존 행의 금액·정책은 역산하거나 채우지 않고 null로 유지한다.

- 기존 무료 `Reservation.confirmed`는 0원·정책 없음으로 저장한다. 무료 예약 API·확정 동작은 유지한다.
- 금액·정책을 받는 `Reservation.held` 생성 경계는 양수 금액과 정책을 함께 요구한다. 인원수와 곱하지 않으며 선점 기한은 동일하게 5분이다.
- 기존 선점 생성 경계는 원본 조건을 모르는 상태를 유지한다. 설정이 없는데 기본 정책이나 임시 금액을 부여하지 않는다.
- 상태 전이·행 잠금·시간 중복 제약·기존 응답 DTO는 변경하지 않는다. 스냅샷은 예약 조건이며 실제 결제 성공의 증거는 공통 Payment의 승인 정보다.

## 결정과 경계

2026-10-28 사용자는 매장 예약금 설정 컬럼·점주 설정 API를 기능33 담당자의 구현·계약에 맡기도록 결정했다. 따라서 금액·정책 입력을 받는 생성 경계는 준비했지만 `ReservationService.createHold`의 실제 호출 연결은 하지 않았다. 이 작업은 기능10 전체 완료가 아니다.

승인 요청 전 READY는 원래 5분 기한을 유지하고, 승인 진행·결과 불명은 확인 전 재고를 반환하지 않기로 결정했다. 공통 진행 표시·멱등성·결과 재확인 계약이 아직 없으므로 prepare/confirm·선점 만료/해제 연동은 구현하지 않았다. 기능58의 READY 만료 제외 문구는 승인 전 단순 이탈과 구분해 후속 조정해야 한다.

## 검증

새 전용 PostgreSQL16 컨테이너 `catchtable-phase2-astra-20261028`, `127.0.0.1:49498`, DB `catchtable_phase2_test`를 사용했다.

```sh
DB_HOST=127.0.0.1 DB_PORT=49498 DB_NAME=catchtable_phase2_test \
DB_USERNAME=catchtable DB_PASSWORD=catchtable ./gradlew test \
  --tests 'com.catchtable.reservation.entity.ReservationDepositSnapshotTest' --no-daemon
```

11개 테스트가 통과했다. 유료 조건의 DB 저장·재조회·해제 후 보존, 무료 0원, 기존 선점의 미확인 값, 양수 금액·정책 필수, DB 제약을 검증했다. 같은 환경에서 `./gradlew build --no-daemon`도 통과했다(46클래스 408개, 실패·오류·건너뜀 0, bootJar 생성). `git diff --check`와 문서 상대 링크를 확인했다.

## 남은 점

기능33 설정 원본과 생성 연결, prepare/confirm, 승인/만료 경합, 기능62 요청 멱등성 및 기능63 재확인은 남아 있다. 기능37의 실제 예약 변경 경로 보존과 기능61 HTTP 조회는 각각 후속 작업에서 검증한다. 실제 Toss 호출·프런트 화면·CI·배포는 이 로컬 검증에 포함하지 않는다. #65는 열린 상태로 유지한다.
