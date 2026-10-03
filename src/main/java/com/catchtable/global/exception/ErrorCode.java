package com.catchtable.global.exception;


import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 본문 형식입니다."),
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "매장을 찾을 수 없습니다"),
    TABLE_NOT_FOUND(HttpStatus.NOT_FOUND, "테이블을 찾을 수 없습니다"),
    DUPLICATE_TABLE_NUMBER(HttpStatus.CONFLICT, "이미 사용 중인 테이블 번호입니다."),
    TABLE_HAS_RESERVATIONS(HttpStatus.CONFLICT, "변경한 테이블 조건과 충돌하는 예약이 있습니다."),
    BUSINESS_HOUR_HAS_RESERVATIONS(HttpStatus.CONFLICT, "변경한 영업시간과 충돌하는 예약이 있습니다."),
    DUPLICATE_BUSINESS_DAY(HttpStatus.BAD_REQUEST, "같은 요일의 영업시간이 중복되었습니다."),
    INVALID_BUSINESS_HOUR(HttpStatus.BAD_REQUEST, "영업 시작 시각과 종료 시각이 같을 수 없습니다."),
    INVALID_BUSINESS_HOUR_PRECISION(HttpStatus.BAD_REQUEST, "영업시간은 분 단위로 입력해야 합니다."),
    INVALID_BREAK_TIME(HttpStatus.BAD_REQUEST, "브레이크타임은 시작·종료를 함께 입력하고 영업시간 안에 있어야 합니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    INVALID_TABLE_CAPACITY(HttpStatus.BAD_REQUEST, "최소 인원은 1명 이상이고 최대 인원 이하여야 합니다."),
    INVALID_RESERVATION_POLICY(HttpStatus.BAD_REQUEST, "도착 유예시간은 이용시간보다 짧아야 합니다."),
    BOOKING_NOT_OPEN_YET(HttpStatus.BAD_REQUEST, "아직 예약을 받지 않는 날짜입니다."),
    BOOKING_DEADLINE_PASSED(HttpStatus.BAD_REQUEST, "예약 마감 시간이 지났습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "회원 식별 정보가 없습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    RESERVATION_NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 예약입니다."),
    FORBIDDEN_MEMBER_TYPE(HttpStatus.FORBIDDEN, "이 기능을 사용할 수 없는 회원 유형입니다."),
    WAITING_CLOSED(HttpStatus.CONFLICT, "현재 웨이팅 접수를 받지 않는 매장입니다."),
    DUPLICATE_ACTIVE_WAITING(HttpStatus.CONFLICT, "이미 이 매장에 진행 중인 웨이팅이 있습니다."),
    WAITING_CONFLICT(HttpStatus.CONFLICT, "웨이팅 신청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요.");




    private final HttpStatus status;
    private final String message;
}
