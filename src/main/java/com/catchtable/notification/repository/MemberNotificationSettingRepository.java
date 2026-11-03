package com.catchtable.notification.repository;

import com.catchtable.notification.entity.MemberNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface MemberNotificationSettingRepository extends JpaRepository<MemberNotificationSetting, Long> {

    // 첫 저장·동시 요청에도 한 행
    @Modifying(clearAutomatically = true)
    @Query(value = """
            insert into member_notification_setting
                (member_id, reservation_enabled, waiting_enabled, created_at, updated_at)
            values (:memberId, :reservationEnabled, :waitingEnabled, :now, :now)
            on conflict (member_id) do update
                set reservation_enabled = excluded.reservation_enabled,
                    waiting_enabled = excluded.waiting_enabled,
                    updated_at = excluded.updated_at
            """, nativeQuery = true)
    void upsert(@Param("memberId") Long memberId,
                @Param("reservationEnabled") boolean reservationEnabled,
                @Param("waitingEnabled") boolean waitingEnabled,
                @Param("now") OffsetDateTime now);
}
