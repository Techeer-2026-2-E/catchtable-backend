package com.catchtable.payment;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

// 결제 테스트 데이터 (JdbcTemplate INSERT ... RETURNING id)
public record PaymentTestData(JdbcTemplate jdbc) {

    public static final long AMOUNT = 10_000;
    private static final OffsetDateTime START = OffsetDateTime.of(2030, 1, 20, 18, 0, 0, 0, ZoneOffset.ofHours(9));

    public Long member(String type) {
        return jdbc.queryForObject("""
                INSERT INTO member (email, password, name, phone, user_type)
                VALUES (?, 'password', 'test', '010-0000-0000', ?)
                RETURNING id
                """, Long.class, "payment-" + UUID.randomUUID() + "@example.com", type);
    }

    public Long store(Long ownerId) {
        Long storeId = jdbc.queryForObject("""
                INSERT INTO store (
                    owner_id, name, category, address, waiting_available,
                    reservation_duration_minutes, reservation_slot_minutes, arrival_grace_minutes
                )
                VALUES (?, 'payment store', 'KOREAN', 'Seoul', false, 60, 30, 10)
                RETURNING id
                """, Long.class, ownerId);
        jdbc.update("INSERT INTO store_table (store_id, table_number, capacity, status) VALUES (?, 1, 4, 'ACTIVE')",
                storeId);
        return storeId;
    }

    // 결제 대기 선점 (예약별 시간 분리)
    public Long hold(Long memberId, Long storeId, int slot) {
        Long tableId = jdbc.queryForObject("SELECT id FROM store_table WHERE store_id = ?", Long.class, storeId);
        OffsetDateTime startAt = START.plusHours(slot);
        return jdbc.queryForObject("""
                INSERT INTO reservation (
                    member_id, store_id, table_id, reservation_start_at, reservation_end_at, party_size,
                    status, requested_at, hold_expires_at
                )
                VALUES (?, ?, ?, ?, ?, 2, 'PENDING_PAYMENT', ?, ?)
                RETURNING id
                """, Long.class, memberId, storeId, tableId, startAt, startAt.plusHours(1),
                START.minusDays(1), START.minusDays(1).plusMinutes(5));
    }

    public Long payment(Long memberId, Long reservationId, String status, long refundedAmount) {
        boolean approved = status.equals("DONE") || status.equals("PARTIAL_REFUNDED") || status.equals("REFUNDED");
        return jdbc.queryForObject("""
                INSERT INTO payment (
                    member_id, reservation_id, order_id, order_name, amount, payment_key, status,
                    method, approved_at, refunded_amount, receipt_url
                )
                VALUES (?, ?, ?, '예약금', ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, memberId, reservationId, "order-" + UUID.randomUUID(), AMOUNT,
                approved ? "pk-" + UUID.randomUUID() : null, status,
                approved ? "카드" : null, approved ? START.minusDays(1) : null, refundedAmount,
                approved ? "https://receipt" : null);
    }

    public String orderId(Long paymentId) {
        return jdbc.queryForObject("SELECT order_id FROM payment WHERE id = ?", String.class, paymentId);
    }
}
