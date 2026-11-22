package com.catchtable.notification.repository;

import com.catchtable.notification.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 업무 TX가 있으면 같이 커밋, 없으면 따로 저장. 같은 event_key면 건너뜀 (저장 1, 건너뜀 0)
    @Transactional
    @Modifying
    @Query(value = """
            insert into notification
                (member_id, target_id, type, message, event_key, created_at)
            values (:memberId, :targetId, :type, :message, :eventKey, :now)
            on conflict (member_id, event_key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("memberId") Long memberId,
                       @Param("targetId") Long targetId,
                       @Param("type") String type,
                       @Param("message") String message,
                       @Param("eventKey") String eventKey,
                       @Param("now") OffsetDateTime now);

    // 알림함 목록 (삭제 안 한 것, lastId보다 작은 id부터 최신순)
    @Query("""
            select n from Notification n
            where n.memberId = :memberId
              and n.deletedAt is null
              and n.id < :lastId
            order by n.id desc
            """)
    Slice<Notification> findInbox(@Param("memberId") Long memberId,
                                  @Param("lastId") Long lastId,
                                  Pageable pageable);

    // 안 읽은 알림 여부 (빨간 점)
    boolean existsByMemberIdAndReadAtIsNullAndDeletedAtIsNull(Long memberId);

    // lastId까지 읽음 처리 (이후 도착한 알림은 안 읽음 유지)
    @Modifying(clearAutomatically = true)
    @Query("""
            update Notification n
            set n.readAt = :now
            where n.memberId = :memberId
              and n.id <= :lastId
              and n.readAt is null
              and n.deletedAt is null
            """)
    int markReadUpTo(@Param("memberId") Long memberId,
                     @Param("lastId") Long lastId,
                     @Param("now") OffsetDateTime now);

    // 본인 알림 1건 (삭제용)
    Optional<Notification> findByIdAndMemberId(Long id, Long memberId);
}
