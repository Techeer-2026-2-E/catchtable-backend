package com.catchtable.notification.durability;

import com.catchtable.global.exception.ErrorResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice
public class WaitingSseExceptionHandler {
    @ExceptionHandler(WaitingSseCapacityException.class)
    public ResponseEntity<ErrorResponse> capacity() {
        return ResponseEntity.status(503).header("Retry-After", "30")
                .body(new ErrorResponse("WAITING_SSE_CAPACITY_EXCEEDED", "웨이팅 처리 용량이 부족합니다. 상태를 조회한 뒤 다시 시도해 주세요."));
    }
}
