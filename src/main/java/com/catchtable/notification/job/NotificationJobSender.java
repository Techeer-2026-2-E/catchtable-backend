package com.catchtable.notification.job;

import com.catchtable.notification.dto.CustomerNotificationEvent;
import com.catchtable.notification.dto.NotificationMessage;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.entity.NotificationJob;
import com.catchtable.notification.entity.NotificationJobKind;
import com.catchtable.notification.entity.NotificationJobResult;
import com.catchtable.notification.job.NotificationJobTargetQuery.QueueRow;
import com.catchtable.notification.repository.SseEmitterRepository;
import com.catchtable.notification.service.NotificationSettingService;
import com.catchtable.waiting.dto.WaitingPositionPayload;
import com.catchtable.waiting.entity.WaitingStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// 작업 1개를 실제 SSE로 보낸다. TX 밖에서 실행된다
@Component
@RequiredArgsConstructor
public class NotificationJobSender {

    public record FanoutResult(int recipients, int sentCount) {}

    private static final String WAITING_CHANGED_MESSAGE = "대기 목록이 변경되었습니다.";
    private static final Set<String> CALL_ACTIVE = Set.of("CALLED", "CONFIRMED");

    private final SseEmitterRepository connections;
    private final NotificationSettingService settings;
    private final NotificationJobTargetQuery targets;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SseConnectionWriter writer;

    // NOTIFY: 저장해 둔 본문을 그대로 보냄
    public NotificationJobResult sendNotify(NotificationJob job, Runnable checkClaim) {
        if (!settings.allows(job.getMemberId(), job.getEventType())) {
            return NotificationJobResult.SETTING_DISABLED;
        }
        if (job.getEventType() == NotificationType.WAITING_CALLED && isStaleCall(job)) {
            return NotificationJobResult.STALE_CALL;
        }
        JsonNode body = read(job.getPayload());
        var outcome = sendToMember(job.getKind(), job.getMemberId(), body.get("eventId").asText(),
                job.getEventType().name(), body, checkClaim);
        requireDeliveryOutcome(outcome == SseConnectionWriter.Outcome.RETRY);
        return outcome == SseConnectionWriter.Outcome.SENT ? NotificationJobResult.SENT : NotificationJobResult.NO_CONNECTION;
    }

    // REFRESH: 매장 점주에게 "목록 다시 불러와"
    public FanoutResult sendRefresh(NotificationJob job, Runnable checkClaim) {
        Optional<Long> owner = targets.storeOwnerId(job.getStoreId());
        if (owner.isEmpty()) {
            return new FanoutResult(0, 0);
        }
        Long ownerId = owner.get();
        if (!settings.allows(ownerId, NotificationType.WAITING_CHANGED)) {
            return new FanoutResult(1, 0);
        }
        NotificationMessage message = NotificationMessage.from(
                new CustomerNotificationEvent(ownerId, NotificationType.WAITING_CHANGED, job.getStoreId(),
                        WAITING_CHANGED_MESSAGE),
                LocalDateTime.now(clock));
        var outcome = sendToMember(job.getKind(), ownerId, message.eventId(), NotificationType.WAITING_CHANGED.name(), message, checkClaim);
        requireDeliveryOutcome(outcome == SseConnectionWriter.Outcome.RETRY);
        return new FanoutResult(1, outcome == SseConnectionWriter.Outcome.SENT ? 1 : 0);
    }

    // QUEUE: 지금 대기열을 조회해서, 연결된 손님에게 현재 순번을 보냄
    public FanoutResult sendQueue(NotificationJob job, Runnable checkClaim) {
        List<QueueRow> queue = targets.activeQueue(job.getStoreId(), job.getWaitingDate());

        // 연결 없는 손님은 설정 조회 없이 건너뛰고, 연결된 손님만 설정을 한 번에 확인
        List<Long> connected = queue.stream()
                .map(QueueRow::memberId)
                .filter(memberId -> !connections.connections(memberId).isEmpty())
                .distinct()
                .toList();
        Set<Long> allowed = settings.allowedMembers(connected, NotificationType.WAITING_POSITION_CHANGED);

        LocalDateTime now = LocalDateTime.now(clock);
        int sent = 0;
        boolean retry = false;
        for (int i = 0; i < queue.size(); i++) {
            QueueRow row = queue.get(i);
            if (!allowed.contains(row.memberId())) {
                continue;
            }
            int position = i + 1;
            NotificationMessage message = NotificationMessage.from(
                    new CustomerNotificationEvent(row.memberId(), NotificationType.WAITING_POSITION_CHANGED,
                            row.waitingId(), null,
                            new WaitingPositionPayload(row.waitingId(), position, position - 1,
                                    WaitingStatus.valueOf(row.status()))),
                    now);
            var outcome = sendToMember(job.getKind(), row.memberId(), message.eventId(),
                    NotificationType.WAITING_POSITION_CHANGED.name(), message, checkClaim);
            if (outcome == SseConnectionWriter.Outcome.SENT) sent++;
            if (outcome == SseConnectionWriter.Outcome.RETRY) retry = true;
        }
        requireDeliveryOutcome(retry);
        return new FanoutResult(queue.size(), sent);
    }

    // 회원의 탭 하나라도 쓰기에 성공해야 해당 회원에게 전송한 것으로 집계한다.
    private SseConnectionWriter.Outcome sendToMember(NotificationJobKind kind, Long memberId, String eventId,
                                                      String eventName, Object data, Runnable checkClaim) {
        var outcome = SseConnectionWriter.Outcome.CLOSED;
        for (SseEmitter emitter : connections.findAllByMemberId(memberId)) {
            checkClaim.run();
            var written = writer.send(kind, memberId, emitter, SseEmitter.event().id(eventId).name(eventName).data(data));
            if (written == SseConnectionWriter.Outcome.SENT || outcome == SseConnectionWriter.Outcome.CLOSED) {
                outcome = written;
            }
        }
        return outcome;
    }

    private void requireDeliveryOutcome(boolean retry) {
        if (retry) throw new DeliveryRetry();
    }

    // 쓰기 기한 초과·전송 풀 포화. 느린 연결은 이미 닫혔으므로 워커가 곧 다시 시도한다.
    static final class DeliveryRetry extends IllegalStateException {
        DeliveryRetry() {
            super("SSE 전송 결과를 확정할 수 없어 작업을 재시도합니다.");
        }
    }

    // 새 호출 차수, 상태 변경(착석·취소 등), 기한 지남이면 지난 호출
    private boolean isStaleCall(NotificationJob job) {
        Optional<NotificationJobTargetQuery.CallState> current = targets.callState(job.getTargetId());
        if (current.isEmpty()) {
            return true;
        }
        var state = current.get();
        if (job.getCallCount() != null && job.getCallCount() != state.callCount()) {
            return true;
        }
        if (!CALL_ACTIVE.contains(state.status())) {
            return true;
        }
        return state.callExpiresAt() == null || !state.callExpiresAt().isAfter(OffsetDateTime.now(clock));
    }

    private JsonNode read(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 알림 본문을 읽을 수 없음", e);
        }
    }
}
