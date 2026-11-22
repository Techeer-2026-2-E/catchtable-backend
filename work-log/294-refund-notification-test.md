# 환불 복구 뒤 비동기 취소 알림 검증

2026-11-23. [#294](https://github.com/Techeer-2026-2-E/catchtable-backend/issues/294)에서 예약 알림 전송을 별도 워커로 옮긴 뒤, [PR #298의 첫 CI](https://github.com/Techeer-2026-2-E/catchtable-backend/actions/runs/45031850074/job/137236944104)에서 환불 복구 테스트 두 건이 실패했다. 두 테스트 모두 예약의 `CANCELLED` 상태와 결제 대행사 호출 검사를 통과했다. 이어지는 `SseEmitter.send()` 검사에서 예상한 1회보다 적은 0회가 관측됐다.

업무 트랜잭션은 취소 알림 작업을 저장한다. 커밋이 끝나면 다른 스레드가 작업을 가져가 전송한다. 환불 테스트는 커밋 직후에도 바로 전송 횟수를 확인할 수 있다고 가정했다. 수정본은 해당 회원의 알림 작업이 끝난 뒤 정확히 한 번 전송됐는지, 예약을 유지한 경우에는 전송이 없었는지 확인한다. 테스트 종료 시에도 회원의 작업이 끝난 뒤 연결과 데이터를 정리한다. [환불 테스트](../src/test/java/com/catchtable/reservation/service/ReservationRefundExecutionTest.java) · [기존 회원별 대기 도구](../src/test/java/com/catchtable/notification/job/NotificationJobTestDrain.java)

## 재현과 검증

Java 21과 전용 PostgreSQL 16.15를 사용했다. 수정 전 `50c54dd`에서 `ReservationRefundExecutionTest`만 실행하면 42건 모두 통과했다. 실행 순서에 따른 차이를 확인하려고 격리 DB에 Flyway를 적용한 뒤 아래 트리거로 워커의 취소 알림 획득을 350ms 지연시켰다.

```sql
CREATE FUNCTION ct298_delay_worker_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_sleep(0.35);
    RETURN NEW;
END;
$$;
CREATE TRIGGER ct298_delay_cancel_worker BEFORE UPDATE OF status ON notification_job
FOR EACH ROW WHEN (OLD.status = 'PENDING' AND NEW.status = 'PROCESSING'
                   AND NEW.kind = 'NOTIFY' AND NEW.event_type = 'RESERVATION_CANCELED')
EXECUTE FUNCTION ct298_delay_worker_claim();
```

```bash
./gradlew test --rerun-tasks \
  --tests '*.ReservationRefundExecutionTest.commonCompletionBeforeReservationSettlementIsRecoveredWithoutAnotherPgCall' \
  --tests '*.ReservationRefundExecutionTest.commonReconcileRetriesAnUnappliedRefundWithTheSavedAmountAndCancellationIntent'
```

같은 지연에서 수정 전에는 두 건 모두 CI와 같은 `WantedButNotInvoked`로 실패했고, 수정 후에는 두 건 모두 통과했다.

지연 트리거가 없는 새 DB에서 `./gradlew --offline --max-workers=2 --rerun-tasks build`도 실행했다. 108개 테스트 클래스의 1,184건이 모두 통과했다. 실패·오류·건너뜀은 각각 0건이었다.
