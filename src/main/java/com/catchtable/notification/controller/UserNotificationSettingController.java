package com.catchtable.notification.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.notification.dto.NotificationSettingRequest;
import com.catchtable.notification.dto.NotificationSettingResponse;
import com.catchtable.notification.service.NotificationSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/members/me/notification-settings")
public class UserNotificationSettingController {

    private final NotificationSettingService settingService;

    /** 알림 수신 설정 조회 (설정 전이면 기본값) */
    @GetMapping
    public ResponseEntity<NotificationSettingResponse> getSettings(@LoginMember Long memberId) {
        return ResponseEntity.ok(settingService.getSettings(memberId));
    }

    /** 알림 수신 설정 변경 (변경 결과 응답) */
    @PutMapping
    public ResponseEntity<NotificationSettingResponse> updateSettings(
            @LoginMember Long memberId,
            @Valid @RequestBody NotificationSettingRequest request) {
        return ResponseEntity.ok(settingService.updateSettings(memberId, request));
    }
}
