package com.catchtable.notification.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
}
