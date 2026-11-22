package com.catchtable.notification.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.notification.dto.NotificationInboxResponse;
import com.catchtable.notification.dto.NotificationReadRequest;
import com.catchtable.notification.dto.NotificationUnreadResponse;
import com.catchtable.notification.service.NotificationInboxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/notifications")
public class UserNotificationInboxController {

    private final NotificationInboxService inboxService;

    /** 알림함 목록 (최신순, lastId 커서) */
    @GetMapping
    public ResponseEntity<NotificationInboxResponse> getInbox(
            @LoginMember Long memberId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(inboxService.getInbox(memberId, lastId, size));
    }

    /** 안 읽은 알림 여부 (빨간 점) */
    @GetMapping("/unread")
    public ResponseEntity<NotificationUnreadResponse> hasUnread(@LoginMember Long memberId) {
        return ResponseEntity.ok(new NotificationUnreadResponse(inboxService.hasUnread(memberId)));
    }

    /** lastId까지 읽음 처리 */
    @PatchMapping("/read")
    public ResponseEntity<Void> markRead(
            @LoginMember Long memberId,
            @Valid @RequestBody NotificationReadRequest request) {
        inboxService.markRead(memberId, request.lastId());
        return ResponseEntity.noContent().build();
    }

    /** 알림 1건 삭제 */
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> delete(
            @LoginMember Long memberId,
            @PathVariable Long notificationId) {
        inboxService.delete(memberId, notificationId);
        return ResponseEntity.noContent().build();
    }
}
