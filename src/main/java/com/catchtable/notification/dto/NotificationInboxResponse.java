package com.catchtable.notification.dto;

import org.springframework.data.domain.Slice;

import java.util.List;

// nextLastId: 다음 요청에 lastId로 넘길 값 (더 없으면 null)
public record NotificationInboxResponse(
        List<NotificationResponse> notifications,
        Long nextLastId,
        boolean hasNext
) {
    public static NotificationInboxResponse of(Slice<NotificationResponse> slice) {
        List<NotificationResponse> content = slice.getContent();
        Long nextLastId = slice.hasNext() ? content.get(content.size() - 1).id() : null;
        return new NotificationInboxResponse(content, nextLastId, slice.hasNext());
    }
}
