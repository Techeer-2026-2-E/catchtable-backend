package com.catchtable.global.exception;


import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "매장을 찾을 수 없습니다"),
    TABLE_NOT_FOUND(HttpStatus.NOT_FOUND, "테이블을 찾을 수 없습니다"),
    DUPLICATE_TABLE_NUMBER(HttpStatus.CONFLICT, "이미 사용 중인 테이블 번호입니다."),
    DUPLICATE_BUSINESS_DAY(HttpStatus.BAD_REQUEST, "같은 요일의 영업시간이 중복되었습니다."),
    INVALID_BUSINESS_HOUR(HttpStatus.BAD_REQUEST, "영업 시작 시각과 종료 시각이 같을 수 없습니다."),
    INVALID_BREAK_TIME(HttpStatus.BAD_REQUEST, "브레이크타임은 시작·종료를 함께 입력하고 영업시간 안에 있어야 합니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");




    private final HttpStatus status;
    private final String message;
}
