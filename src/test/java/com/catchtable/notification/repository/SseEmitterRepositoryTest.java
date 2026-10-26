package com.catchtable.notification.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterRepositoryTest {

    private final SseEmitterRepository repository = new SseEmitterRepository();

    @Test
    @DisplayName("한 회원의 연결 여러 개를 모두 보관한다")
    void saveMultipleEmitters() {
        SseEmitter first = new SseEmitter();
        SseEmitter second = new SseEmitter();

        repository.save(1L, first);
        repository.save(1L, second);

        assertThat(repository.findAllByMemberId(1L)).containsExactly(first, second);
    }

    @Test
    @DisplayName("다른 회원의 연결은 섞이지 않는다")
    void separatedByMember() {
        SseEmitter mine = new SseEmitter();
        SseEmitter other = new SseEmitter();

        repository.save(1L, mine);
        repository.save(2L, other);

        assertThat(repository.findAllByMemberId(1L)).containsExactly(mine);
    }

    @Test
    @DisplayName("삭제하면 해당 연결만 빠진다")
    void deleteOne() {
        SseEmitter first = new SseEmitter();
        SseEmitter second = new SseEmitter();
        repository.save(1L, first);
        repository.save(1L, second);

        repository.delete(1L, first);

        assertThat(repository.findAllByMemberId(1L)).containsExactly(second);
    }

    @Test
    @DisplayName("마지막 연결을 지우면 빈 목록을 돌려준다")
    void deleteLast() {
        SseEmitter emitter = new SseEmitter();
        repository.save(1L, emitter);

        repository.delete(1L, emitter);

        assertThat(repository.findAllByMemberId(1L)).isEmpty();
    }

    @Test
    @DisplayName("연결이 없는 회원은 빈 목록, 없는 연결 삭제는 무시한다")
    void unknownMember() {
        repository.delete(99L, new SseEmitter());

        assertThat(repository.findAllByMemberId(99L)).isEmpty();
    }

    @Test
    @Timeout(15)
    @DisplayName("마지막 기존 연결의 삭제와 재접속이 겹쳐도 새 연결을 보관한다")
    @SuppressWarnings("unchecked")
    void reconnectSurvivesTheLastOldConnectionClosing() throws Exception {
        SseEmitter oldConnection = new SseEmitter();
        SseEmitter newConnection = new SseEmitter();
        CountDownLatch adding = new CountDownLatch(1);
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var list = new CopyOnWriteArrayList<SseEmitter>() {
            @Override
            public boolean add(SseEmitter emitter) {
                if (emitter == newConnection) {
                    adding.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("save was not released");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
                return super.add(emitter);
            }
        };
        list.add(oldConnection);
        var members = (Map<Long, List<SseEmitter>>) ReflectionTestUtils.getField(repository, "emitters");
        members.put(1L, list);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var saving = executor.submit(() -> repository.save(1L, newConnection));
            try {
                assertThat(adding.await(5, TimeUnit.SECONDS)).isTrue();
                var closing = executor.submit(() -> {
                    deleting.countDown();
                    repository.delete(1L, oldConnection);
                });
                assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    closing.get(2, TimeUnit.SECONDS);
                } catch (TimeoutException expectedWhenSaveAndDeleteAreAtomic) {
                    // 등록과 삭제가 원자적으로 처리되면 등록 완료까지 삭제가 기다린다.
                } finally {
                    release.countDown();
                }
                saving.get(5, TimeUnit.SECONDS);
                closing.get(5, TimeUnit.SECONDS);
                assertThat(repository.findAllByMemberId(1L)).containsExactly(newConnection);
            } finally {
                release.countDown();
            }
        }
    }
}
