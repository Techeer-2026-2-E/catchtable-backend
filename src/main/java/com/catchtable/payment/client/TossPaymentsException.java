package com.catchtable.payment.client;

import lombok.Getter;

// Toss가 거절한 요청 (실패 확정)
@Getter
public class TossPaymentsException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public TossPaymentsException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }
}
