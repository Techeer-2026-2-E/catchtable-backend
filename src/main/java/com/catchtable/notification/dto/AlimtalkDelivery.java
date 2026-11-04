package com.catchtable.notification.dto;

import com.catchtable.notification.client.AlimtalkGateway;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AlimtalkDelivery(UUID id, long waitingId, long memberId, int callCount, int recallCount,
                              String transportMode, String recipient, String storeName, int waitNumber, OffsetDateTime expiresAt,
                              String status, int attemptCount, String providerMessageId, String lastErrorCode,
                              OffsetDateTime nextAttemptAt) {
    public AlimtalkGateway.Request request() {
        return new AlimtalkGateway.Request(id, attemptCount, recipient, storeName, waitNumber, expiresAt);
    }

    public boolean terminal() {
        return status.equals("SENT") || status.equals("FAILED") || status.equals("SKIPPED");
    }
}
