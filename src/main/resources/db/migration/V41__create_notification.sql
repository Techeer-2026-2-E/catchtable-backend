-- 회원별 알림함. 알림 설정과 상관없이 저장한다.
-- 보관 기간이 없고 자동으로 지우지 않는다. 사용자가 삭제하면 deleted_at만 채운다(soft delete).
-- 지운 알림도 행은 남겨서, 같은 event_key로 다시 저장되지 않게 한다.
CREATE TABLE notification (
    id          BIGSERIAL    PRIMARY KEY,
    member_id   BIGINT       NOT NULL REFERENCES member (id) ON DELETE CASCADE,
    target_id   BIGINT       NOT NULL,
    type        VARCHAR(40)  NOT NULL,
    message     TEXT         NOT NULL,
    event_key   VARCHAR(200) NOT NULL,
    read_at     TIMESTAMPTZ,
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_notification_member_event UNIQUE (member_id, event_key),
    CONSTRAINT ck_notification_event_key CHECK (length(trim(event_key)) > 0),
    -- 알림함 저장 대상 6종. 점주 화면 갱신(WAITING_CHANGED)·순번 변경(WAITING_POSITION_CHANGED)은 저장하지 않는다.
    CONSTRAINT ck_notification_type CHECK (
        type IN ('WAITING_CALLED', 'WAITING_EXPIRED', 'WAITING_CANCELED',
                 'RESERVATION_CONFIRMED', 'RESERVATION_CANCELED', 'RESERVATION_CHANGED')
    )
);

-- 알림함 목록(최신순, id 커서)
CREATE INDEX idx_notification_inbox
    ON notification (member_id, id DESC)
    WHERE deleted_at IS NULL;

-- 안 읽은 알림 확인(빨간 점), lastId까지 읽음 처리
CREATE INDEX idx_notification_unread
    ON notification (member_id, id)
    WHERE deleted_at IS NULL AND read_at IS NULL;
