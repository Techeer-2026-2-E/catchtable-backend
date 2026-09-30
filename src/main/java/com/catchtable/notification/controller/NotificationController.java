package com.catchtable.notification.controller;

import com.catchtable.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    // EventSource는 헤더를 못 보내므로 P1 임시 식별값을 쿼리로 받는다
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam Long memberId)
    {
        return notificationService.subscribe(memberId);
    }
}
