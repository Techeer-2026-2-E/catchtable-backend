-- 알림 전송 작업(아웃박스). 업무 TX에서 저장하고 전송 스레드가 꺼내 SSE로 보낸다.
-- NOTIFY  : 고객 알림 6종, 수신자 1명당 1행
-- REFRESH : 점주 대기 목록 갱신(WAITING_CHANGED), 매장당 PENDING 1개로 합침
-- QUEUE   : 순번 변경(WAITING_POSITION_CHANGED), 매장·날짜당 PENDING 1개로 합침
-- 영구 기록은 notification(알림함)이 맡고, 이 테이블은 끝난 작업을 7일 뒤 지운다.
CREATE TABLE notification_job (
    id             BIGSERIAL    PRIMARY KEY,
    kind           VARCHAR(16)  NOT NULL,
    job_key        VARCHAR(400),                -- NOTIFY 중복 방지 (업무 이벤트 키:type:memberId)
    member_id      BIGINT       REFERENCES member (id) ON DELETE CASCADE,  -- NOTIFY 수신자
    store_id       BIGINT       REFERENCES store (id) ON DELETE CASCADE,   -- REFRESH·QUEUE 대상 매장
    waiting_date   DATE,                        -- QUEUE 대기열 날짜
    event_type     VARCHAR(40),                 -- NOTIFY 알림 종류
    target_id      BIGINT,                      -- NOTIFY 예약/웨이팅 id
    call_count     INTEGER,                     -- WAITING_CALLED 오래된 호출 거르기
    payload        JSONB,                       -- NOTIFY SSE 본문(eventId 포함, 다시 보내도 같은 값)
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    result         VARCHAR(40),                 -- SENT, NO_CONNECTION, SETTING_DISABLED, STALE_CALL, MERGED ...
    attempt_count  INTEGER      NOT NULL DEFAULT 0,  -- 가져간 횟수, 상한을 넘으면 FAILED
    recipients     INTEGER,                     -- REFRESH·QUEUE 대상 수
    sent_count     INTEGER,                     -- REFRESH·QUEUE 실제 전송 수
    lease_until    TIMESTAMPTZ,                 -- PROCESSING 유효 시각, 지나면 다시 가져갈 수 있음
    created_at     TIMESTAMPTZ  NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,

    CONSTRAINT uq_notification_job_key UNIQUE (job_key),
    CONSTRAINT ck_notification_job_kind CHECK (kind IN ('NOTIFY', 'REFRESH', 'QUEUE')),
    CONSTRAINT ck_notification_job_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    CONSTRAINT ck_notification_job_attempt CHECK (attempt_count >= 0),

    -- 종류별 필수 값
    CONSTRAINT ck_notification_job_notify CHECK (
        kind <> 'NOTIFY' OR (
            job_key IS NOT NULL AND member_id IS NOT NULL AND target_id IS NOT NULL
            AND payload IS NOT NULL
            AND event_type IN ('WAITING_CALLED', 'WAITING_EXPIRED', 'WAITING_CANCELED',
                               'RESERVATION_CONFIRMED', 'RESERVATION_CANCELED', 'RESERVATION_CHANGED')
        )
    ),
    CONSTRAINT ck_notification_job_refresh CHECK (
        kind <> 'REFRESH' OR (store_id IS NOT NULL AND job_key IS NULL)
    ),
    CONSTRAINT ck_notification_job_queue CHECK (
        kind <> 'QUEUE' OR (store_id IS NOT NULL AND waiting_date IS NOT NULL AND job_key IS NULL)
    ),

    -- 상태별 필수 값
    CONSTRAINT ck_notification_job_processing CHECK (
        status <> 'PROCESSING' OR (lease_until IS NOT NULL AND started_at IS NOT NULL)
    ),
    CONSTRAINT ck_notification_job_finished CHECK (
        status NOT IN ('DONE', 'FAILED') OR (finished_at IS NOT NULL AND result IS NOT NULL)
    )
);

-- 합치기: 같은 매장(·날짜)에는 PENDING이 하나만 있다. 저장은 ON CONFLICT DO NOTHING.
CREATE UNIQUE INDEX uq_notification_job_refresh_pending
    ON notification_job (store_id)
    WHERE kind = 'REFRESH' AND status = 'PENDING';

CREATE UNIQUE INDEX uq_notification_job_queue_pending
    ON notification_job (store_id, waiting_date)
    WHERE kind = 'QUEUE' AND status = 'PENDING';

-- 전송 스레드가 가져갈 작업 (스레드 A: NOTIFY / 스레드 B: REFRESH·QUEUE), 오래된 것부터
CREATE INDEX idx_notification_job_pending
    ON notification_job (kind, id)
    WHERE status = 'PENDING';

-- 멈춘 PROCESSING 다시 가져가기
CREATE INDEX idx_notification_job_lease
    ON notification_job (lease_until)
    WHERE status = 'PROCESSING';

-- 끝난 작업 정리(7일)
CREATE INDEX idx_notification_job_finished
    ON notification_job (finished_at)
    WHERE status IN ('DONE', 'FAILED');
