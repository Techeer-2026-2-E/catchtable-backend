-- 행 없음 = 기본값 (모두 수신)
CREATE TABLE member_notification_setting
(
    member_id           BIGINT PRIMARY KEY REFERENCES member (id) ON DELETE CASCADE,
    reservation_enabled BOOLEAN                  NOT NULL DEFAULT TRUE,
    waiting_enabled     BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
