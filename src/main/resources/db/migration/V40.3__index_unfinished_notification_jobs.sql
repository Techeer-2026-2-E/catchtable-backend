-- 같은 회원 또는 매장·접수일에서 앞선 미완료 작업을 찾는다.
CREATE INDEX idx_notification_job_open_member
    ON notification_job (member_id, id)
    WHERE kind = 'NOTIFY' AND status IN ('PENDING', 'PROCESSING');

CREATE INDEX idx_notification_job_open_fanout
    ON notification_job (store_id, kind, waiting_date, id)
    WHERE kind IN ('QUEUE', 'REFRESH') AND status IN ('PENDING', 'PROCESSING');
