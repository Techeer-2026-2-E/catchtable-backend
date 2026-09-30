package com.catchtable.notification.repository;


import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SseEmitterRepository {

    private final Map<Long, List<SseEmitter>> emitters=new ConcurrentHashMap<>();

    public void save(Long memberId, SseEmitter emitter)
    {
        emitters.computeIfAbsent(memberId, id -> new CopyOnWriteArrayList<>())
                .add(emitter);
    }

    public void delete(Long memberId, SseEmitter emitter)
    {
        emitters.computeIfPresent(memberId, (id, list)->
        {
            list.remove(emitter);
            return list.isEmpty() ? null:list;
        });
    }

    public List<SseEmitter> findAllByMemberId(Long memberId)
    {
        return emitters.getOrDefault(memberId, List.of());
    }
}
