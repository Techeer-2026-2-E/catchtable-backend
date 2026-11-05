package com.catchtable.notification.client;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public interface AlimtalkGateway {
    Result send(Request request);

    Result lookup(Request request, String messageId);

    record Request(UUID id, int attempt, String recipient, String storeName,
                   int waitNumber, OffsetDateTime expiresAt) {
        public Request {
            recipient = Objects.requireNonNull(recipient).replaceAll("[\\s-]", "");
        }
    }

    enum Outcome { ACCEPTED, DELIVERED, RETRYABLE_FAILURE, PERMANENT_FAILURE, UNKNOWN }

    record Result(Outcome outcome, String messageId, String errorCode) {
        public Result {
            Objects.requireNonNull(outcome);
            if (messageId != null && messageId.isBlank()) messageId = null;
            if (messageId != null && messageId.length() > 64) {
                throw new IllegalArgumentException("Invalid provider message id");
            }
        }

        public static Result unknown(String messageId) {
            return new Result(Outcome.UNKNOWN, messageId, "UNKNOWN_RESULT");
        }
    }
}
