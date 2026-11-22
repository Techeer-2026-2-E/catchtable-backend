package com.catchtable.notification.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.notification.dto.NotificationInboxResponse;
import com.catchtable.notification.dto.NotificationResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 각 테스트는 롤백된다
@SpringBootTest
@Transactional
class NotificationInboxServiceTest {

    @Autowired NotificationInboxService inboxService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private Long memberId;
    private Long otherId;

    @BeforeEach
    void setUp() {
        memberId = insertMember("고객");
        otherId = insertMember("다른 고객");
    }

    @Test
    @DisplayName("목록은 최신순이고 lastId로 다음 페이지를 이어 받는다")
    void cursorPaging() {
        List<Long> ids = insertNotifications(memberId, 5);   // 오래된 순

        NotificationInboxResponse first = inboxService.getInbox(memberId, null, 2);
        assertThat(idsOf(first)).containsExactly(ids.get(4), ids.get(3));
        assertThat(first.hasNext()).isTrue();
        assertThat(first.nextLastId()).isEqualTo(ids.get(3));

        NotificationInboxResponse second = inboxService.getInbox(memberId, first.nextLastId(), 2);
        assertThat(idsOf(second)).containsExactly(ids.get(2), ids.get(1));

        NotificationInboxResponse last = inboxService.getInbox(memberId, second.nextLastId(), 2);
        assertThat(idsOf(last)).containsExactly(ids.get(0));
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextLastId()).isNull();
    }

    @Test
    @DisplayName("size가 없으면 20개, 범위를 벗어나면 1~50으로 맞춘다")
    void sizeBounds() {
        insertNotifications(memberId, 55);

        assertThat(inboxService.getInbox(memberId, null, null).notifications()).hasSize(20);
        assertThat(inboxService.getInbox(memberId, null, 0).notifications()).hasSize(1);
        assertThat(inboxService.getInbox(memberId, null, 100).notifications()).hasSize(50);
    }

    @Test
    @DisplayName("목록에는 내 알림 중 삭제하지 않은 것만 나온다")
    void onlyMineAndNotDeleted() {
        List<Long> mine = insertNotifications(memberId, 2);
        insertNotifications(otherId, 1);
        jdbc.update("UPDATE notification SET deleted_at = now() WHERE id = ?", mine.get(1));

        assertThat(idsOf(inboxService.getInbox(memberId, null, 20))).containsExactly(mine.get(0));
    }

    @Test
    @DisplayName("응답에 읽음 여부와 알림 내용이 담긴다")
    void responseFields() {
        Long id = insertNotifications(memberId, 1).get(0);
        jdbc.update("UPDATE notification SET read_at = now() WHERE id = ?", id);

        NotificationResponse response = inboxService.getInbox(memberId, null, 20).notifications().get(0);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.type().name()).isEqualTo("RESERVATION_CONFIRMED");
        assertThat(response.message()).isEqualTo("알림 1");
        assertThat(response.read()).isTrue();
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("lastId까지만 읽음 처리하고, 그 뒤 알림과 남의 알림은 그대로다")
    void markReadUpToLastId() {
        // id 순서: 내 것 2건 → 남의 것 → 내 것 1건
        List<Long> before = insertNotifications(memberId, 2);
        Long others = insertNotifications(otherId, 1).get(0);
        Long after = jdbc.queryForObject("""
                INSERT INTO notification (member_id, target_id, type, message, event_key, created_at)
                VALUES (?, 9, 'RESERVATION_CONFIRMED', '나중 알림', 'test-after', now())
                RETURNING id""", Long.class, memberId);

        // 남의 알림 id까지 읽음 요청 → 그보다 작은 내 알림만 읽음
        inboxService.markRead(memberId, others);

        assertThat(readIds(memberId)).containsExactlyInAnyOrderElementsOf(before).doesNotContain(after);
        assertThat(readIds(otherId)).isEmpty();
        assertThat(inboxService.hasUnread(memberId)).isTrue();
    }

    @Test
    @DisplayName("이미 읽은 알림은 다시 읽음 처리해도 처음 시각을 유지한다")
    void markReadKeepsFirstTime() {
        Long id = insertNotifications(memberId, 1).get(0);
        jdbc.update("UPDATE notification SET read_at = TIMESTAMPTZ '2026-01-01 00:00:00+09' WHERE id = ?", id);

        inboxService.markRead(memberId, id);

        assertThat(jdbc.queryForObject("SELECT read_at = TIMESTAMPTZ '2026-01-01 00:00:00+09' FROM notification WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("안 읽은 알림이 없거나 지운 알림만 안 읽었으면 hasUnread는 false다")
    void hasUnread() {
        assertThat(inboxService.hasUnread(memberId)).isFalse();

        Long id = insertNotifications(memberId, 1).get(0);
        assertThat(inboxService.hasUnread(memberId)).isTrue();

        jdbc.update("UPDATE notification SET deleted_at = now() WHERE id = ?", id);
        assertThat(inboxService.hasUnread(memberId)).isFalse();
    }

    @Test
    @DisplayName("삭제하면 행은 남고 deleted_at만 채워진다")
    void softDelete() {
        Long id = insertNotifications(memberId, 1).get(0);

        inboxService.delete(memberId, id);
        em.flush();

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM notification WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(inboxService.getInbox(memberId, null, 20).notifications()).isEmpty();
    }

    @Test
    @DisplayName("이미 지웠거나, 남의 것이거나, 없는 알림은 404다")
    void deleteNotFound() {
        Long id = insertNotifications(memberId, 1).get(0);
        Long others = insertNotifications(otherId, 1).get(0);
        inboxService.delete(memberId, id);
        em.flush();

        assertNotFound(() -> inboxService.delete(memberId, id));
        assertNotFound(() -> inboxService.delete(memberId, others));
        assertNotFound(() -> inboxService.delete(memberId, Long.MAX_VALUE));
        em.flush();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM notification WHERE id = ?",
                Boolean.class, others)).isTrue();
    }

    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    private Long insertMember(String name) {
        return jdbc.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES (?, '010-0000-0000', 'CUSTOMER')
                RETURNING id""", Long.class, name);
    }

    // 오래된 순으로 id를 돌려준다
    private List<Long> insertNotifications(Long owner, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(jdbc.queryForObject("""
                    INSERT INTO notification (member_id, target_id, type, message, event_key, created_at)
                    VALUES (?, ?, 'RESERVATION_CONFIRMED', ?, ?, now())
                    RETURNING id""", Long.class, owner, (long) i, "알림 " + i, "test-" + owner + "-" + i));
        }
        return ids;
    }

    private List<Long> idsOf(NotificationInboxResponse response) {
        return response.notifications().stream().map(NotificationResponse::id).toList();
    }

    private List<Long> readIds(Long owner) {
        return jdbc.queryForList("SELECT id FROM notification WHERE member_id = ? AND read_at IS NOT NULL",
                Long.class, owner);
    }
}
