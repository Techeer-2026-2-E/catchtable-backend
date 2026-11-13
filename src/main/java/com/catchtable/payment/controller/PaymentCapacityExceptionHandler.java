package com.catchtable.payment.controller;

import com.catchtable.global.exception.ErrorResponse;
import com.catchtable.payment.client.PaymentCapacityExceededException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice
public class PaymentCapacityExceptionHandler {
    @ExceptionHandler(PaymentCapacityExceededException.class)
    public ResponseEntity<ErrorResponse> capacity(PaymentCapacityExceededException exception) {
        return ResponseEntity.status(503).header("Retry-After", "1")
                .body(new ErrorResponse("PAYMENT_CAPACITY_EXCEEDED", exception.getMessage()));
    }
}
