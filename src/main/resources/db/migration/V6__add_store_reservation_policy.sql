-- 매장 예약 정책 (기능 #33)
--   - 이용시간·슬롯 간격·도착 유예는 V2에 이미 있어서 예약 가능 기간·마감 시간만 추가한다
--   - booking_open_days: 오늘부터 며칠 뒤 날짜까지 예약을 받는지 (날짜 단위)
--   - booking_deadline_minutes: 예약 시작 몇 분 전까지 예약을 받는지 (0 = 시작 직전까지)
--   - 허용 인원은 매장이 아니라 테이블별 최소·최대 인원으로 둔다 (V5)
--   - DEFAULT는 기존 row를 채우는 용도다. JPA는 모든 컬럼 값을 넣으므로 새 row 기본값은 엔티티에서 채운다
ALTER TABLE store
    ADD COLUMN booking_open_days        INT NOT NULL DEFAULT 30,
    ADD COLUMN booking_deadline_minutes INT NOT NULL DEFAULT 0;

ALTER TABLE store
    ADD CONSTRAINT chk_store_booking_open_days      CHECK (booking_open_days >= 1),
    ADD CONSTRAINT chk_store_booking_deadline       CHECK (booking_deadline_minutes >= 0),
    ADD CONSTRAINT chk_store_arrival_grace_duration CHECK (arrival_grace_minutes < reservation_duration_minutes);
