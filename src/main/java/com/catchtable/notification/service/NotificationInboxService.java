package com.catchtable.notification.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.notification.dto.NotificationInboxResponse;
import com.catchtable.notification.dto.NotificationResponse;
import com.catchtable.notification.entity.Notification;
import com.catchtable.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

// 알림함 조회·읽음·삭제
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationInboxService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    // 최신순 목록. lastId가 없으면 처음부터, 있으면 그보다 오래된 것부터
    public NotificationInboxResponse getInbox(Long memberId, Long lastId, Integer size) {
        long cursor = (lastId == null) ? Long.MAX_VALUE : lastId;
        int pageSize = (size == null) ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);

        Slice<NotificationResponse> slice = notificationRepository
                .findInbox(memberId, cursor, PageRequest.of(0, pageSize))
                .map(NotificationResponse::from);
        return NotificationInboxResponse.of(slice);
    }

    // 안 읽은 알림이 하나라도 있는지 (빨간 점)
    public boolean hasUnread(Long memberId) {
        return notificationRepository.existsByMemberIdAndReadAtIsNullAndDeletedAtIsNull(memberId);
    }

    // lastId까지 읽음 처리. 그 뒤에 온 알림은 안 읽음으로 남음
    @Transactional
    public void markRead(Long memberId, Long lastId) {
        notificationRepository.markReadUpTo(memberId, lastId, OffsetDateTime.now(clock));
    }

    // 본인 알림 1건 삭제 (soft delete). 없거나 남의 것이거나 이미 지웠으면 404
    @Transactional
    public void delete(Long memberId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndMemberId(notificationId, memberId)
                .filter(n -> !n.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
        notification.delete(OffsetDateTime.now(clock));
    }
}
