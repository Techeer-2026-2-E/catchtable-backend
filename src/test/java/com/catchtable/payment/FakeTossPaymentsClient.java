package com.catchtable.payment;

import com.catchtable.payment.client.TossPaymentResponse;
import com.catchtable.payment.client.TossPaymentsClient;
import com.catchtable.payment.client.TossPaymentsException;
import com.catchtable.payment.client.TossPaymentsUnknownResultException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// 가짜 Toss (멱등키별 최초 결과 재사용, 실제 호출 없음)
public class FakeTossPaymentsClient implements TossPaymentsClient {

    public static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2030-01-19T12:00:00+09:00");

    private final Map<String, TossPaymentResponse> resultByKey = new ConcurrentHashMap<>();
    private final Map<String, Registered> payments = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<RuntimeException> failures = new ConcurrentLinkedQueue<>();
    private final List<String> confirmKeys = new CopyOnWriteArrayList<>();
    private final List<String> cancelKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger cancelExecutions = new AtomicInteger();
    private volatile long delayMillis;

    public void reset() {
        resultByKey.clear();
        payments.clear();
        failures.clear();
        confirmKeys.clear();
        cancelKeys.clear();
        cancelExecutions.set(0);
        delayMillis = 0;
    }

    // 승인된 결제 등록 (테스트 데이터로 만든 결제)
    public void register(String paymentKey, String orderId, long totalAmount, long canceledAmount) {
        payments.put(paymentKey, new Registered(orderId, totalAmount, new AtomicLong(canceledAmount)));
    }

    // 다음 호출 1회 실패 (처리 안 됨)
    public void failNext(RuntimeException failure) {
        failures.add(failure);
    }

    public static TossPaymentsException rejected() {
        return new TossPaymentsException(400, "REJECT_CARD_PAYMENT", "한도 초과");
    }

    public static TossPaymentsUnknownResultException timeout() {
        return new TossPaymentsUnknownResultException("응답 시간초과", null);
    }

    // 동시 요청 겹침용
    public void delay(long millis) {
        delayMillis = millis;
    }

    public List<String> confirmKeys() {
        return confirmKeys;
    }

    public List<String> cancelKeys() {
        return cancelKeys;
    }

    // 실제 환불 처리 횟수 (같은 키 재호출 제외)
    public int cancelExecutions() {
        return cancelExecutions.get();
    }

    @Override
    public TossPaymentResponse confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        confirmKeys.add(idempotencyKey);
        pause();
        throwIfFailing();
        return resultByKey.computeIfAbsent(idempotencyKey, key -> {
            Registered payment = new Registered(orderId, amount, new AtomicLong());
            payments.put(paymentKey, payment);
            return response(paymentKey, payment);
        });
    }

    @Override
    public TossPaymentResponse getByPaymentKey(String paymentKey) {
        return response(paymentKey, payments.get(paymentKey));
    }

    @Override
    public TossPaymentResponse getByOrderId(String orderId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TossPaymentResponse cancel(String paymentKey, Long cancelAmount, String cancelReason,
                                      String idempotencyKey) {
        cancelKeys.add(idempotencyKey);
        pause();
        throwIfFailing();
        return resultByKey.computeIfAbsent(idempotencyKey, key -> {
            Registered payment = payments.get(paymentKey);
            long amount = cancelAmount == null ? payment.totalAmount() - payment.canceled().get() : cancelAmount;
            payment.canceled().addAndGet(amount);
            cancelExecutions.incrementAndGet();
            return response(paymentKey, payment);
        });
    }

    private void throwIfFailing() {
        RuntimeException failure = failures.poll();
        if (failure != null) {
            throw failure;
        }
    }

    private void pause() {
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static TossPaymentResponse response(String paymentKey, Registered payment) {
        long canceled = payment.canceled().get();
        String status = canceled == 0 ? "DONE" : canceled == payment.totalAmount() ? "CANCELED" : "PARTIAL_CANCELED";
        return new TossPaymentResponse(paymentKey, payment.orderId(), "예약금", status, "카드",
                payment.totalAmount(), payment.totalAmount() - canceled, APPROVED_AT,
                new TossPaymentResponse.Receipt("https://receipt"), null);
    }

    private record Registered(String orderId, long totalAmount, AtomicLong canceled) {
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public FakeTossPaymentsClient fakeTossPaymentsClient() {
            return new FakeTossPaymentsClient();
        }
    }
}
