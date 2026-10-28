package com.catchtable.payment;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

    public String paymentKey(Long paymentId) {
        return jdbc.queryForObject("SELECT payment_key FROM payment WHERE id = ?", String.class, paymentId);
    }

    // 트랜잭션 없는 테스트 정리 (매장 단위 결제·예약 + 회원)
    public void deleteStore(Long storeId, List<Long> memberIds) {
        String payments = """
                SELECT p.id FROM payment p JOIN reservation r ON r.id = p.reservation_id WHERE r.store_id = ?
                """;
        jdbc.update("""
                UPDATE reservation SET approval_started_at = NULL, approval_payment_id = NULL,
                    approval_order_id = NULL, approval_payment_key = NULL WHERE store_id = ?
                """, storeId);
        jdbc.update("DELETE FROM payment_refund WHERE payment_id IN (" + payments + ")", storeId);
        jdbc.update("DELETE FROM payment_status_history WHERE payment_id IN (" + payments + ")", storeId);
        jdbc.update("DELETE FROM payment WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)",
                storeId);
        jdbc.update("DELETE FROM reservation WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store_table WHERE store_id = ?", storeId);
        jdbc.update("DELETE FROM store WHERE id = ?", storeId);
        memberIds.forEach(memberId -> jdbc.update("DELETE FROM member WHERE id = ?", memberId));
    }

    // 확정된 환불 기록
    public Long completedRefund(Long paymentId, long refundAmount, long totalRefundedAmount, String reason) {
        return jdbc.queryForObject("""
                INSERT INTO payment_refund (
                    payment_id, idempotency_key, cancel_reason, refund_amount, status,
                    total_refunded_amount, requested_at, completed_at
                )
                VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, ?)
                RETURNING id
                """, Long.class, paymentId, "refund-" + UUID.randomUUID(), reason, refundAmount,
                totalRefundedAmount, START.minusDays(1), START.minusDays(1));
    }

    // 승인 시작 보호 (#145: 선점 기한 안에 시작 기록)
    public void protect(Long reservationId, Long paymentId, String paymentKey, OffsetDateTime startedAt) {
        jdbc.update("""
                UPDATE reservation SET requested_at = ?, hold_expires_at = ?,
                    deposit_amount_snapshot = ?, refund_policy_snapshot = 'FLEXIBLE',
                    approval_started_at = ?, approval_payment_id = ?,
                    approval_order_id = (SELECT order_id FROM payment WHERE id = ?), approval_payment_key = ?
                WHERE id = ?
                """, startedAt.minusMinutes(1), startedAt.plusMinutes(4), AMOUNT, startedAt, paymentId, paymentId,
                paymentKey, reservationId);
    }

    // 처리 중·결과 불명 환불 기록
    public Long inFlightRefund(Long paymentId, String key, long amount, String status, OffsetDateTime requestedAt) {
        return jdbc.queryForObject("""
                INSERT INTO payment_refund (
                    payment_id, idempotency_key, requested_amount, cancel_reason, refund_amount, status, requested_at
                )
                VALUES (?, ?, ?, '고객 요청', ?, ?, ?)
                RETURNING id
                """, Long.class, paymentId, key, amount, amount, status, requestedAt);
    }
}
