package com.catchtable.notification.repository;

import com.catchtable.notification.entity.NotificationJob;
import com.catchtable.notification.entity.NotificationJobKind;
import com.catchtable.notification.entity.NotificationJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface NotificationJobRepository extends JpaRepository<NotificationJob, Long> {

    // ── 저장 (업무 TX 안에서 호출) ─────────────────────────────

    // 고객 알림 1건. 같은 job_key면 건너뜀 (저장 1, 건너뜀 0)
    @Transactional
    @Modifying
    @Query(value = """
            insert into notification_job
                (kind, job_key, member_id, event_type, target_id, call_count, payload, status, created_at)
            values ('NOTIFY', :jobKey, :memberId, :eventType, :targetId, :callCount,
                    cast(:payload as jsonb), 'PENDING', :now)
            on conflict (job_key) do nothing
            """, nativeQuery = true)
    int insertNotifyIfAbsent(@Param("jobKey") String jobKey,
                             @Param("memberId") Long memberId,
                             @Param("eventType") String eventType,
                             @Param("targetId") Long targetId,
                             @Param("callCount") Integer callCount,
                             @Param("payload") String payload,
                             @Param("now") OffsetDateTime now);

    // 점주 목록 갱신. 같은 매장 PENDING이 이미 있으면 건너뜀 (합치기)
    @Transactional
    @Modifying
    @Query(value = """
            insert into notification_job (kind, store_id, status, created_at)
            values ('REFRESH', :storeId, 'PENDING', :now)
            on conflict (store_id) where kind = 'REFRESH' and status = 'PENDING' do nothing
            """, nativeQuery = true)
    int insertRefreshIfAbsent(@Param("storeId") Long storeId,
                              @Param("now") OffsetDateTime now);

    // 순번 갱신. 같은 매장·날짜 PENDING이 이미 있으면 건너뜀 (합치기)
    @Transactional
    @Modifying
    @Query(value = """
            insert into notification_job (kind, store_id, waiting_date, status, created_at)
            values ('QUEUE', :storeId, :waitingDate, 'PENDING', :now)
            on conflict (store_id, waiting_date) where kind = 'QUEUE' and status = 'PENDING' do nothing
            """, nativeQuery = true)
    int insertQueueIfAbsent(@Param("storeId") Long storeId,
                            @Param("waitingDate") LocalDate waitingDate,
                            @Param("now") OffsetDateTime now);

    // ── 가져가기 (전송 스레드, 짧은 TX 안에서 호출) ─────────────

    // 가져갈 수 있는 작업을 잠그고 읽음. 다른 TX가 잠근 행은 건너뜀
    @Query(value = """
            select * from notification_job
            where kind in (:kinds)
              and (status = 'PENDING'
                   or (status = 'PROCESSING' and lease_until <= :now))
            order by id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<NotificationJob> lockClaimable(@Param("kinds") Collection<String> kinds,
                                        @Param("now") OffsetDateTime now,
                                        @Param("limit") int limit);

    // 멈춘 REFRESH를 다시 가져갔을 때, 같은 매장에 새 PENDING이 있는지
    boolean existsByKindAndStoreIdAndStatus(NotificationJobKind kind, Long storeId,
                                            NotificationJobStatus status);

    // 멈춘 QUEUE를 다시 가져갔을 때, 같은 매장·날짜에 새 PENDING이 있는지
    boolean existsByKindAndStoreIdAndWaitingDateAndStatus(NotificationJobKind kind, Long storeId,
                                                          LocalDate waitingDate,
                                                          NotificationJobStatus status);

    // ── 정리 ───────────────────────────────────────────────

    // 끝난 지 오래된 작업 삭제 (영구 기록은 알림함이 맡음)
    @Transactional
    @Modifying
    @Query("""
            delete from NotificationJob j
            where j.status in (com.catchtable.notification.entity.NotificationJobStatus.DONE,
                               com.catchtable.notification.entity.NotificationJobStatus.FAILED)
              and j.finishedAt < :threshold
            """)
    int deleteFinishedBefore(@Param("threshold") OffsetDateTime threshold);
}
