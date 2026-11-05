package com.catchtable.reservation.service;

import com.catchtable.payment.FakeTossPaymentsClient;
import com.catchtable.payment.PaymentTestData;
import com.catchtable.payment.ReconcileTestClock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"reservation.refund-outbox.scheduling-enabled=true",
        "reservation.refund-outbox.poll-interval-ms=50","reservation.refund-outbox.initial-delay-ms=50"})
@AutoConfigureMockMvc
@Import({FakeTossPaymentsClient.Config.class,ReconcileTestClock.Config.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class UnconfirmedRefundScheduledTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired FakeTossPaymentsClient toss;

    @Test void anAcceptedHttpRequestIsDeliveredByTheRealSchedulerWithoutAManualPoll() throws Exception {
        var data=new PaymentTestData(jdbc);
        Long customer=data.member("CUSTOMER"), owner=data.member("OWNER"), store=data.store(owner);
        try {
            Long reservation=data.hold(customer,store,0);
            jdbc.update("UPDATE reservation SET status='EXPIRED',deposit_amount_snapshot=10000,refund_policy_snapshot='STRICT' WHERE id=?",reservation);
            Long payment=data.payment(customer,reservation,"DONE",0);
            jdbc.update("UPDATE payment SET refund_required_at=?,refund_required_reason='예약 확정 없이 확인된 승인' WHERE id=?",OffsetDateTime.parse("2030-01-20T12:00:00+09:00"),payment);
            toss.register(data.paymentKey(payment),data.orderId(payment),10000,0);
            mvc.perform(post("/api/user/payments/{id}/unconfirmed-refund",payment).header("X-Member-Id",customer)
                    .header("Idempotency-Key","scheduled")).andExpect(status().isAccepted());
            await().atMost(Duration.ofSeconds(8)).untilAsserted(()-> {
                assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id=?",String.class,payment)).isEqualTo("REFUNDED");
                assertThat(jdbc.queryForObject("SELECT status FROM reservation_refund_outbox WHERE request_id=(SELECT id FROM reservation_refund_request WHERE payment_id=?)",String.class,payment)).isEqualTo("DELIVERED");
            });
            assertThat(toss.cancelExecutions()).isEqualTo(1);
        } finally { data.deleteStore(store,List.of(customer,owner)); }
    }
}
