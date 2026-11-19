package com.catchtable.review.service;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.review.dto.ReviewCreateRequest;
import com.catchtable.review.dto.ReviewUpdateRequest;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 리뷰 작성·수정·삭제가 store의 review_count·rating_sum에 같은 트랜잭션으로 반영되는지 확인한다
@SpringBootTest
class ReviewStoreStatsTest {

    @Autowired private ReviewService reviewService;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private Fixture fixture;

    @AfterEach
    void cleanUp() {
        if (fixture == null) {
            return;
        }
        jdbcTemplate.update("""
                DELETE FROM review WHERE reservation_id IN (SELECT id FROM reservation WHERE store_id = ?)
                """, fixture.storeId());
        jdbcTemplate.update("DELETE FROM reservation WHERE store_id = ?", fixture.storeId());
        jdbcTemplate.update("DELETE FROM store_table WHERE store_id = ?", fixture.storeId());
        jdbcTemplate.update("DELETE FROM store WHERE id = ?", fixture.storeId());
        jdbcTemplate.update("DELETE FROM member WHERE id IN (?, ?)", fixture.memberId(), fixture.ownerId());
    }

    @Test
    void createAddsCountAndRating() {
        fixture = createReservations(2);

        reviewService.createReview(fixture.memberId(), create(fixture.reservationIds().get(0), 4));
        reviewService.createReview(fixture.memberId(), create(fixture.reservationIds().get(1), 5));

        assertStats(2, 9);
    }

    @Test
    void updateAppliesOnlyRatingDifference() {
        fixture = createReservations(1);
        Long reviewId = reviewService.createReview(fixture.memberId(), create(fixture.reservationIds().get(0), 4)).reviewId();

        // 별점을 보내지 않으면 store는 그대로다
        reviewService.updateReview(fixture.memberId(), reviewId, new ReviewUpdateRequest(null, "내용만 수정", null));
        assertStats(1, 4);

        reviewService.updateReview(fixture.memberId(), reviewId, new ReviewUpdateRequest(2, null, null));
        assertStats(1, 2);
    }

    @Test
    void deleteTwiceSubtractsOnce() {
        fixture = createReservations(2);
        reviewService.createReview(fixture.memberId(), create(fixture.reservationIds().get(0), 3));
        Long reviewId = reviewService.createReview(fixture.memberId(), create(fixture.reservationIds().get(1), 5)).reviewId();

        reviewService.deleteReview(fixture.memberId(), reviewId);
        reviewService.deleteReview(fixture.memberId(), reviewId);   // 이미 삭제됨 → 아무것도 안 함

        assertStats(1, 3);
    }

    @Test
    @Timeout(30)
    void concurrentCreatesDoNotLoseUpdates() throws Exception {
        int count = 20;
        fixture = createReservations(count);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Long reservationId : fixture.reservationIds()) {
                futures.add(executor.submit(() -> {
                    start.await();
                    reviewService.createReview(fixture.memberId(), create(reservationId, 4));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertStats(count, 4L * count);
    }

    private void assertStats(int reviewCount, long ratingSum) {
        var row = jdbcTemplate.queryForMap(
                "SELECT review_count, rating_sum FROM store WHERE id = ?", fixture.storeId());
        assertThat(((Number) row.get("review_count")).intValue()).isEqualTo(reviewCount);
        assertThat(((Number) row.get("rating_sum")).longValue()).isEqualTo(ratingSum);
    }

    private static ReviewCreateRequest create(Long reservationId, int rating) {
        return new ReviewCreateRequest(reservationId, rating, "리뷰", null);
    }

    // 완료된 예약을 count개 만든다. 같은 테이블이라 시간이 겹치지 않게 2시간씩 띄운다
    private Fixture createReservations(int count) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String suffix = UUID.randomUUID().toString();
            Member owner = member("owner-" + suffix + "@example.com", UserType.OWNER);
            Member customer = member("customer-" + suffix + "@example.com", UserType.CUSTOMER);
            Store store = Store.builder().owner(owner).name("리뷰 집계 매장")
                    .category(StoreCategory.KOREAN).address("Seoul")
                    .reservationDurationMinutes(60).reservationSlotMinutes(30).arrivalGraceMinutes(10).build();
            entityManager.persist(store);
            entityManager.flush();
            Long tableId = jdbcTemplate.queryForObject("""
                    INSERT INTO store_table (store_id, table_number, capacity, status)
                    VALUES (?, 1, 4, 'ACTIVE') RETURNING id
                    """, Long.class, store.getId());

            List<Long> reservationIds = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                OffsetDateTime begin = OffsetDateTime.now().minusHours(3L + 2L * i);
                Reservation reservation = Reservation.confirmed(customer, store, tableId,
                        begin, begin.plusHours(1), 2, begin.minusDays(1));
                entityManager.persist(reservation);
                entityManager.flush();
                jdbcTemplate.update("UPDATE reservation SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                        begin.plusHours(1), reservation.getId());
                reservationIds.add(reservation.getId());
            }
            return new Fixture(customer.getId(), owner.getId(), store.getId(), reservationIds);
        });
    }

    private Member member(String email, UserType type) {
        Member member = Member.builder().email(email).password("test-password").name("회원")
                .phone("010-0000-0000").userType(type).build();
        entityManager.persist(member);
        return member;
    }

    private record Fixture(Long memberId, Long ownerId, Long storeId, List<Long> reservationIds) {}
}
