package com.catchtable.payment;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// 커밋된 예약 확정 알림 횟수 (스레드 무관)
public class ConfirmedEventCounter {

    private final Map<Long, AtomicInteger> counts = new ConcurrentHashMap<>();

    @TransactionalEventListener
    public void on(CustomerNotificationEvent event) {
        if (event.type() == NotificationType.RESERVATION_CONFIRMED) {
            counts.computeIfAbsent(event.targetId(), id -> new AtomicInteger()).incrementAndGet();
        }
    }

    public int count(Long reservationId) {
        AtomicInteger count = counts.get(reservationId);
        return count == null ? 0 : count.get();
    }

    @TestConfiguration
    public static class Config {

        @Bean
        public ConfirmedEventCounter confirmedEventCounter() {
            return new ConfirmedEventCounter();
        }
    }
}
