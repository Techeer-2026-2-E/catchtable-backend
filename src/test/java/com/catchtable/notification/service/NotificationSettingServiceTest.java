package com.catchtable.notification.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationSettingRequest;
import com.catchtable.notification.dto.NotificationSettingResponse;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.repository.SseEmitterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

// 각 테스트는 롤백된다
@SpringBootTest
@Transactional
class NotificationSettingServiceTest {

    @Autowired NotificationSettingService settingService;
    @Autowired NotificationService notificationService;
    @Autowired SseEmitterRepository emitterRepository;
    @Autowired JdbcTemplate jdbc;

    private Long customerId;
    private Long ownerId;

    @BeforeEach
    void setUp() {
        customerId = insertMember("고객", "CUSTOMER");
        ownerId = insertMember("점주", "OWNER");
    }

    @Test
    @DisplayName("설정 전이면 예약·웨이팅 알림 모두 켜져 있다")
    void defaultsWhenNoRow() {
        assertThat(settingService.getSettings(customerId)).isEqualTo(new NotificationSettingResponse(true, true));
        assertThat(rowCount(customerId)).isZero();
    }

    @Test
    @DisplayName("변경 결과를 응답하고 이후 조회도 같다")
    void updateThenGet() {
        NotificationSettingResponse updated = settingService.updateSettings(customerId, request(false, true));

        assertThat(updated).isEqualTo(new NotificationSettingResponse(false, true));
        assertThat(settingService.getSettings(customerId)).isEqualTo(updated);
    }

    @Test
    @DisplayName("같은 값으로 다시 변경해도 성공하고 행은 하나다")
    void sameValueAgain() {
        settingService.updateSettings(customerId, request(false, false));

        assertThat(settingService.updateSettings(customerId, request(false, false)))
                .isEqualTo(new NotificationSettingResponse(false, false));
        assertThat(rowCount(customerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("고객이 아니면 403, 회원이 없으면 404 오류다")
    void requiresCustomer() {
        assertFailure(() -> settingService.getSettings(ownerId), ErrorCode.FORBIDDEN_MEMBER_TYPE);
        assertFailure(() -> settingService.updateSettings(ownerId, request(false, false)),
                ErrorCode.FORBIDDEN_MEMBER_TYPE);
        assertFailure(() -> settingService.getSettings(-1L), ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("예약 알림을 끄면 확정·변경은 안 오고 취소는 온다")
    void reservationOff() throws IOException {
        settingService.updateSettings(customerId, request(false, true));

        assertThat(delivered(customerId, NotificationType.RESERVATION_CONFIRMED)).isFalse();
        assertThat(delivered(customerId, NotificationType.RESERVATION_CHANGED)).isFalse();
        assertThat(delivered(customerId, NotificationType.RESERVATION_CANCELED)).isTrue();
        assertThat(delivered(customerId, NotificationType.WAITING_POSITION_CHANGED)).isTrue();
    }

    @Test
    @DisplayName("웨이팅 알림을 끄면 순번 갱신은 안 오고 호출·만료·취소는 온다")
    void waitingOff() throws IOException {
        settingService.updateSettings(customerId, request(true, false));

        assertThat(delivered(customerId, NotificationType.WAITING_POSITION_CHANGED)).isFalse();
        assertThat(delivered(customerId, NotificationType.WAITING_CALLED)).isTrue();
        assertThat(delivered(customerId, NotificationType.WAITING_EXPIRED)).isTrue();
        assertThat(delivered(customerId, NotificationType.WAITING_CANCELED)).isTrue();
        assertThat(delivered(customerId, NotificationType.RESERVATION_CONFIRMED)).isTrue();
    }

    @Test
    @DisplayName("점주 대기 목록 갱신은 항상 온다")
    void ownerListChangeAlwaysSent() throws IOException {
        assertThat(delivered(ownerId, NotificationType.WAITING_CHANGED)).isTrue();
    }

    @Test
    @DisplayName("한 회원의 설정은 다른 회원 알림에 영향이 없다")
    void otherMemberUnaffected() throws IOException {
        Long other = insertMember("다른 고객", "CUSTOMER");
        settingService.updateSettings(customerId, request(false, false));

        assertThat(settingService.getSettings(other)).isEqualTo(new NotificationSettingResponse(true, true));
        assertThat(delivered(other, NotificationType.RESERVATION_CONFIRMED)).isTrue();
        assertThat(delivered(other, NotificationType.WAITING_POSITION_CHANGED)).isTrue();
    }

    // 연결 하나에 보내고 전송 여부 확인
    private boolean delivered(Long memberId, NotificationType type) throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        emitterRepository.save(memberId, emitter);
        try {
            notificationService.send(new CustomerNotificationEvent(memberId, type, 1L, "알림"));
            return !mockingDetails(emitter).getInvocations().isEmpty();
        } finally {
            emitterRepository.delete(memberId, emitter);
        }
    }

    private int rowCount(Long memberId) {
        return jdbc.queryForObject("select count(*) from member_notification_setting where member_id = ?",
                Integer.class, memberId);
    }

    private static NotificationSettingRequest request(boolean reservation, boolean waiting) {
        return new NotificationSettingRequest(reservation, waiting);
    }

    private Long insertMember(String name, String type) {
        return jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES (?, '010-0000-0000', ?)
                RETURNING id""", Long.class, name, type);
    }

    private static void assertFailure(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
