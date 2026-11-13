package com.catchtable.notification.repository;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SseEmitterRepository {
    public record Connection(String generation, SseEmitter emitter) {}
    private final Map<Long, List<Connection>> emitters = new ConcurrentHashMap<>();

    public void save(Long memberId, SseEmitter emitter) {
        emitters.compute(memberId, (id, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            if (list.stream().noneMatch(c -> c.emitter() == emitter))
                list.add(new Connection(UUID.randomUUID().toString(), emitter));
            return list;
        });
    }
    public void delete(Long memberId, SseEmitter emitter) {
        emitters.computeIfPresent(memberId, (id, list) -> {
            list.removeIf(c -> c.emitter() == emitter);
            return list.isEmpty() ? null : list;
        });
    }
    public List<SseEmitter> findAllByMemberId(Long memberId) {
        return connections(memberId).stream().map(Connection::emitter).toList();
    }
    public List<Connection> connections(Long memberId) {
        return List.copyOf(emitters.getOrDefault(memberId, List.of()));
    }
    public SseEmitter find(Long memberId, String generation) {
        return connections(memberId).stream().filter(c -> c.generation().equals(generation))
                .map(Connection::emitter).findFirst().orElse(null);
    }
}
