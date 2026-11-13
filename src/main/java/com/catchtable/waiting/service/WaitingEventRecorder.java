package com.catchtable.waiting.service;

import com.catchtable.waiting.dto.WaitingEventReference;
import com.catchtable.waiting.dto.WaitingEventSnapshot;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingDeferHistory;
import com.catchtable.waiting.entity.WaitingEndHistory;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class WaitingEventRecorder {
    private final WaitingEventRepository events;

    public WaitingEventReference record(Waiting w, String type, String incident, OffsetDateTime at,
                                        Long actorId, WaitingEventSnapshot before) {
        var ref = reference(w, incident);
        events.append(ref, type, at, "WAITING", w.getId().toString(), details(w, actorId, before));
        return ref;
    }

    public WaitingEventReference ended(WaitingEndHistory history, WaitingEventSnapshot before) {
        Waiting w = history.getWaiting();
        var ref = reference(w, "end:" + history.getId());
        String type = WaitingStatus.ACTIVE.contains(history.getPreviousStatus())
                ? history.getReason().name() : "END_REASON_CHANGED";
        // Free-form cancellation details stay in the existing access-controlled source history.
        events.append(ref, type, history.getCreatedAt(), "WAITING_END_HISTORY", history.getId().toString(),
                details(w, history.getActorId(), before));
        return ref;
    }

    public WaitingEventReference deferred(WaitingDeferHistory history, WaitingEventSnapshot before,
                                           Waiting next, WaitingEventSnapshot nextBefore) {
        Waiting w = history.getWaiting();
        var ref = reference(w, "defer:" + history.getId());
        var data = details(w, history.getActorId(), before);
        data.put("beforePosition", history.getBeforePosition());
        data.put("afterPosition", history.getAfterPosition());
        data.put("swappedWaitingId", next.getId());
        events.append(ref, "DEFERRED", history.getCreatedAt(), "WAITING_DEFER_HISTORY",
                history.getId().toString(), data);
        var moved = details(next, history.getActorId(), nextBefore);
        moved.put("deferredWaitingId", w.getId());
        events.append(reference(next, "queue-moved:" + history.getId()), "QUEUE_MOVED", history.getCreatedAt(),
                "WAITING_DEFER_HISTORY", history.getId().toString(), moved);
        return ref;
    }

    private WaitingEventReference reference(Waiting w, String incident) {
        return new WaitingEventReference(w.getId(), w.getCallCount(), w.getRecallCount(),
                "waiting:" + w.getId() + ":" + incident);
    }

    private Map<String, Object> details(Waiting w, Long actorId, WaitingEventSnapshot before) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actorId", actorId);
        data.put("before", before);
        data.put("after", WaitingEventSnapshot.of(w));
        return data;
    }
}
