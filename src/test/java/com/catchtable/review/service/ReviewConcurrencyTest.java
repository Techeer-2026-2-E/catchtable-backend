package com.catchtable.review.service;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.review.dto.ReviewUpdateRequest;
import com.catchtable.review.entity.Review;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReviewConcurrencyTest {

    @Autowired private ReviewService reviewService;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Timeout(15)
    void concurrentUpdateDoesNotUndoDeletion() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Fixture fixture = createReview(transaction);
        var executor = Executors.newSingleThreadExecutor();
        try {
            boolean retryDelete = transaction.execute(status -> {
                reviewService.updateReview(fixture.memberId(), fixture.reviewId(),
                        new ReviewUpdateRequest(5, "수정한 리뷰", null));

                var deletion = executor.submit(() -> {
                    try {
                        return transaction.execute(deleting -> {
                            // 대기 중인 삭제를 시간 제한으로 끝내고 수정 커밋 뒤 재시도한다.
                            jdbcTemplate.execute("SET LOCAL lock_timeout = '250ms'");
                            reviewService.deleteReview(fixture.memberId(), fixture.reviewId());
                            return false;
                        });
                    } catch (PessimisticLockingFailureException exception) {
                        return true;
                    }
                });
                try {
                    return deletion.get(5, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("동시 삭제 요청을 완료하지 못했습니다.", exception);
                }
            });
            if (retryDelete) {
                reviewService.deleteReview(fixture.memberId(), fixture.reviewId());
            }

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT deleted_at FROM review WHERE id = ?", OffsetDateTime.class, fixture.reviewId()
            )).isNotNull();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            jdbcTemplate.update("DELETE FROM review WHERE id = ?", fixture.reviewId());
            jdbcTemplate.update("DELETE FROM reservation WHERE id = ?", fixture.reservationId());
            jdbcTemplate.update("DELETE FROM store_table WHERE id = ?", fixture.tableId());
            jdbcTemplate.update("DELETE FROM store WHERE id = ?", fixture.storeId());
            jdbcTemplate.update("DELETE FROM member WHERE id IN (?, ?)", fixture.memberId(), fixture.ownerId());
        }
    }

    private Fixture createReview(TransactionTemplate transaction) {
        return transaction.execute(status -> {
            String suffix = UUID.randomUUID().toString();
            Member owner = member("owner-" + suffix + "@example.com", UserType.OWNER);
            Member customer = member("customer-" + suffix + "@example.com", UserType.CUSTOMER);
            Store store = Store.builder().owner(owner).name("리뷰 경합 매장")
                    .category(StoreCategory.KOREAN).address("Seoul")
                    .reservationDurationMinutes(60).reservationSlotMinutes(30).arrivalGraceMinutes(10).build();
            entityManager.persist(store);
            entityManager.flush();
            Long tableId = jdbcTemplate.queryForObject("""
                    INSERT INTO store_table (store_id, table_number, capacity, status)
                    VALUES (?, 1, 4, 'ACTIVE') RETURNING id
                    """, Long.class, store.getId());
            OffsetDateTime start = OffsetDateTime.now().minusHours(3);
            Reservation reservation = Reservation.confirmed(customer, store, tableId,
                    start, start.plusHours(1), 2, start.minusDays(1));
            entityManager.persist(reservation);
            entityManager.flush();
            jdbcTemplate.update("UPDATE reservation SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                    start.plusHours(1), reservation.getId());
            Review review = Review.ofReservation(reservation, 4, "원래 리뷰", null);
            entityManager.persist(review);
            entityManager.flush();
            return new Fixture(customer.getId(), owner.getId(), store.getId(), tableId,
                    reservation.getId(), review.getId());
        });
    }

    private Member member(String email, UserType type) {
        Member member = Member.builder().email(email).password("test-password").name("회원")
                .phone("010-0000-0000").userType(type).build();
        entityManager.persist(member);
        return member;
    }

    private record Fixture(Long memberId, Long ownerId, Long storeId, Long tableId,
                           Long reservationId, Long reviewId) {}
}
