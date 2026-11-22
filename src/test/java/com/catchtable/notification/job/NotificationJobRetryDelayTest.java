package com.catchtable.notification.job;

import com.catchtable.notification.entity.NotificationJob;
import com.catchtable.notification.entity.NotificationJobKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 느린 연결은 쓰기 기한이 지나면 이미 닫힌다. 같은 매장의 다음 순번 알림이 30초 lease에 막히지 않아야 한다.
class NotificationJobRetryDelayTest {
    private final NotificationJobProcessor processor = mock(NotificationJobProcessor.class);
    private final NotificationJobSender sender = mock(NotificationJobSender.class);
    private final NotificationJobWorker worker = new NotificationJobWorker(processor, sender, Clock.systemUTC(), false);

    @AfterEach
    void stop() {
        worker.shutdown();
    }

    @Test
    void writeTimeoutRetriesSoonInsteadOfHoldingTheLease() {
        NotificationJob job = mock(NotificationJob.class);
        when(job.getKind()).thenReturn(NotificationJobKind.QUEUE);
        when(job.getLeaseUntil()).thenReturn(OffsetDateTime.now().plus(NotificationJobWorker.LEASE));
        when(processor.claim(anyCollection(), anyInt(), any(), anyInt()))
                .thenReturn(new NotificationJobProcessor.ClaimBatch(List.of(job), true))
                .thenReturn(new NotificationJobProcessor.ClaimBatch(List.of(), false));
        when(sender.sendQueue(eq(job), any())).thenThrow(new NotificationJobSender.DeliveryRetry());

        worker.wake(NotificationJobKind.QUEUE);

        verify(processor, timeout(2000)).renew(job, NotificationJobWorker.DELIVERY_RETRY_DELAY);
    }

    @Test
    void otherFailuresKeepTheOriginalLease() {
        NotificationJob job = mock(NotificationJob.class);
        when(job.getKind()).thenReturn(NotificationJobKind.QUEUE);
        when(job.getLeaseUntil()).thenReturn(OffsetDateTime.now().plus(NotificationJobWorker.LEASE));
        when(processor.claim(anyCollection(), anyInt(), any(), anyInt()))
                .thenReturn(new NotificationJobProcessor.ClaimBatch(List.of(job), true))
                .thenReturn(new NotificationJobProcessor.ClaimBatch(List.of(), false));
        when(sender.sendQueue(eq(job), any())).thenThrow(new IllegalStateException("처리권 만료"));

        worker.wake(NotificationJobKind.QUEUE);

        verify(processor, timeout(2000).times(2)).claim(anyCollection(), anyInt(), any(), anyInt());
        verify(processor, never()).renew(any(), any());
    }
}
