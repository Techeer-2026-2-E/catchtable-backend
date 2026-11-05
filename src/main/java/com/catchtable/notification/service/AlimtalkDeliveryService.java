package com.catchtable.notification.service;

import com.catchtable.notification.client.AlimtalkGateway;
import com.catchtable.notification.client.AlimtalkGateway.Outcome;
import com.catchtable.notification.client.AlimtalkGateway.Result;
import com.catchtable.notification.dto.AlimtalkDelivery;
import com.catchtable.notification.repository.AlimtalkOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "alimtalk.enabled", havingValue = "true")
public class AlimtalkDeliveryService {
    private final AlimtalkOutboxRepository outbox;
    private final AlimtalkGateway gateway;
    private final Clock clock;
    @Value("${alimtalk.mode:simulation}")
    private String mode = "simulation";

    public void poll() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Alimtalk gateway calls require no outer transaction");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        outbox.recoverInterrupted(now);
        outbox.skipInactive(now);
        for (var id : outbox.dueSends(now, mode)) {
            outbox.claimSend(id, OffsetDateTime.now(clock)).ifPresent(this::send);
        }
        for (var id : outbox.dueChecks(now, mode)) {
            outbox.claimCheck(id, OffsetDateTime.now(clock)).ifPresent(this::lookup);
        }
    }

    private void send(AlimtalkDelivery delivery) {
        Result result;
        String recipient = delivery.request().recipient();
        if (!recipient.matches("[0-9]{8,15}")) {
            result = new Result(Outcome.PERMANENT_FAILURE, null, "INVALID_RECIPIENT");
        } else {
            try {
                result = gateway.send(delivery.request());
                if (result == null) result = Result.unknown(null);
            } catch (RuntimeException e) {
                // 요청이 접수됐을 수 있어 예외를 확정 실패로 간주하지 않는다.
                result = Result.unknown(null);
            }
        }
        outbox.recordResult(delivery.id(), delivery.attemptCount(), result, OffsetDateTime.now(clock));
    }

    private void lookup(AlimtalkDelivery delivery) {
        Result result;
        try {
            result = gateway.lookup(delivery.request(), delivery.providerMessageId());
            if (result == null) result = Result.unknown(delivery.providerMessageId());
        } catch (RuntimeException e) {
            result = Result.unknown(delivery.providerMessageId());
        }
        outbox.recordResult(delivery.id(), delivery.attemptCount(), result, OffsetDateTime.now(clock));
    }
}
