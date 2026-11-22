package com.catchtable.notification.job;

import com.catchtable.notification.entity.NotificationJob;
import com.catchtable.notification.entity.NotificationJobKind;
import com.catchtable.notification.entity.NotificationJobResult;
import com.catchtable.notification.entity.NotificationJobStatus;
import com.catchtable.notification.repository.NotificationJobRepository;
import com.catchtable.payment.PaymentTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class NotificationJobAttemptTest {
    private static final LocalDate DAY = LocalDate.of(2030, 1, 2);

    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationJobProcessor processor;
    @Autowired NotificationJobRepository jobs;
    private long owner;
    private long store;

    @BeforeEach
    void prepare() {
        var data = new PaymentTestData(jdbc);
        owner = data.member("OWNER");
        store = data.store(owner);
    }

    @AfterEach
    void cleanup() {
        new PaymentTestData(jdbc).deleteStore(store, List.of(owner));
    }

    @ParameterizedTest
    @EnumSource(NotificationJobKind.class)
    void onlyTheCurrentClaimCanRecordDelivery(NotificationJobKind kind) {
        enqueue(kind);
        NotificationJob first = claim(kind);
        expire(first);
        NotificationJob second = claim(kind);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getAttemptCount()).isEqualTo(first.getAttemptCount() + 1);
        assertThat(processor.renew(first, NotificationJobWorker.LEASE)).isFalse();
        assertThat(processor.renew(second, NotificationJobWorker.LEASE)).isTrue();

        finish(first, false);
        assertThat(jobs.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(NotificationJobStatus.PROCESSING);
        finish(second, true);
        finish(first, false);
        NotificationJob recorded = jobs.findById(first.getId()).orElseThrow();
        assertThat(recorded.getResult()).isEqualTo(NotificationJobResult.SENT);
        assertThat(recorded.getStatus()).isEqualTo(NotificationJobStatus.DONE);
        if (kind != NotificationJobKind.NOTIFY) {
            assertThat(recorded.getRecipients()).isEqualTo(1);
            assertThat(recorded.getSentCount()).isEqualTo(1);
        }
    }

    @Test
    void failedAttemptsAreNotReportedAsSent() {
        enqueue(NotificationJobKind.NOTIFY);
        var job = claim(NotificationJobKind.NOTIFY);
        for (int n = 1; n < NotificationJobWorker.MAX_ATTEMPTS; n++) {
            expire(job);
            job = claim(NotificationJobKind.NOTIFY);
        }
        expire(job);
        var exhausted = processor.claim(Set.of(NotificationJobKind.NOTIFY), 1,
                NotificationJobWorker.LEASE, NotificationJobWorker.MAX_ATTEMPTS);
        assertThat(exhausted.advanced()).isTrue();
        assertThat(exhausted.jobs()).isEmpty();
        assertThat(jobs.findById(job.getId()).orElseThrow().getResult())
                .isEqualTo(NotificationJobResult.ATTEMPTS_EXCEEDED);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationJobKind.class, names = {"QUEUE", "REFRESH"})
    void aNewPendingJobSupersedesAnExpiredFanout(NotificationJobKind kind) {
        enqueue(kind);
        NotificationJob old = claim(kind);
        enqueue(kind);
        expire(old);
        var merged = processor.claim(Set.of(kind), 2, NotificationJobWorker.LEASE, NotificationJobWorker.MAX_ATTEMPTS);
        assertThat(merged.advanced()).isTrue();
        assertThat(merged.jobs()).isEmpty();
        assertThat(claim(kind).getId()).isNotEqualTo(old.getId());
        finish(old, true);
        assertThat(jobs.findById(old.getId()).orElseThrow().getResult()).isEqualTo(NotificationJobResult.MERGED);
    }

    private void enqueue(NotificationJobKind kind) {
        var now = OffsetDateTime.now();
        switch (kind) {
            case NOTIFY -> jobs.insertNotifyIfAbsent("attempt-" + owner, owner, "RESERVATION_CANCELED", 1L, null, "{}", now);
            case REFRESH -> jobs.coalesceRefresh(store, now);
            case QUEUE -> jobs.coalesceQueue(store, DAY, now);
        }
    }

    private NotificationJob claim(NotificationJobKind kind) {
        return processor.claim(Set.of(kind), 1, NotificationJobWorker.LEASE, NotificationJobWorker.MAX_ATTEMPTS).jobs().getFirst();
    }

    private void expire(NotificationJob job) {
        assertThat(jdbc.update("UPDATE notification_job SET lease_until = started_at WHERE id = ? AND status = 'PROCESSING'",
                job.getId())).isEqualTo(1);
    }

    private void finish(NotificationJob job, boolean delivered) {
        if (job.getKind() == NotificationJobKind.NOTIFY) {
            processor.complete(job.getId(), job.getAttemptCount(),
                    delivered ? NotificationJobResult.SENT : NotificationJobResult.NO_CONNECTION);
        } else {
            processor.completeFanout(job.getId(), job.getAttemptCount(), 1, delivered ? 1 : 0);
        }
    }
}
