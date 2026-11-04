package com.catchtable.notification.client;

import java.util.Set;

import static com.catchtable.notification.client.AlimtalkGateway.*;

public final class SolapiStatus {
    private static final Set<String> TRANSIENT = Set.of("1024", "2024", "3012", "3024", "3053");
    private SolapiStatus() { }

    public static Result result(String code, String messageId) {
        if (code == null) return Result.unknown(messageId);
        if ("4000".equals(code)) return new Result(Outcome.DELIVERED, messageId, null);
        if ("2000".equals(code) || "3000".equals(code)) return new Result(Outcome.ACCEPTED, messageId, null);
        if (TRANSIENT.contains(code)) return new Result(Outcome.RETRYABLE_FAILURE, messageId, "SOLAPI_" + code);
        if (code != null && code.matches("[123][0-9]{3}") && !Set.of("3014", "3048").contains(code)) {
            return new Result(Outcome.PERMANENT_FAILURE, messageId, "SOLAPI_" + code);
        }
        return Result.unknown(messageId);
    }
}
