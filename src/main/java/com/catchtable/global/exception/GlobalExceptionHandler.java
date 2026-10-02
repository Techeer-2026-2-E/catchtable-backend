package com.catchtable.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 서비스에서 직접 던지는 예외
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        // 사전 중복 검사 이후의 동시 INSERT와 트랜잭션 커밋 시점의 UPDATE 충돌도 처리한다.
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                ErrorCode code = switch (String.valueOf(violation.getConstraintName())) {
                    case "uk_store_table_store_number" -> ErrorCode.DUPLICATE_TABLE_NUMBER;
                    case "uk_review_reservation" -> ErrorCode.REVIEW_ALREADY_EXISTS;
                    default -> null;
                };
                if (code != null) {
                    return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
                }
            }
        }
        return handleUnexpected(e);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(new ErrorResponse("HTTP_" + e.getStatusCode().value(), e.getReason()));
    }

    // Bean Validation 기본 문구는 로케일에 따라 영어로 나올 수 있어 공통 한글 문구로 응답한다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }

    // JSON 문법 오류·타입 불일치, 경로 변수 타입 불일치 (예: /tables/abc)
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }

    // 없는 URL
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // URL은 맞지만 메서드가 다름 (예: DELETE 없는 API에 DELETE)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ErrorResponse.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    // 예상 못 한 예외 → 500, 로그만 남기고 내부 메시지는 숨김
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리하지 못한 예외가 발생했습니다.", e);
        return ResponseEntity.internalServerError().body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
