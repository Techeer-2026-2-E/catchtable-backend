package com.catchtable.review.controller;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.reservation.entity.Reservation;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserStoreReviewControllerTest {

    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-10-01T12:00:00+09:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Member customer;
    private Store store;
    private Long tableId;
    private int reservationCount;

    @BeforeEach
    void setUp() {
        Member owner = member("review-owner@example.com", "점주", UserType.OWNER);
        customer = member("review-customer@example.com", "방준혁", UserType.CUSTOMER);
        store = store(owner, "리뷰 매장");
        entityManager.flush();
        tableId = insertTable(store.getId());
    }

    @Test
    @DisplayName("기본은 최신순이고, 평균 별점은 소수 첫째 자리로 반올림하며 작성자 이름은 가린다")
    void latestByDefault() throws Exception {
        Long oldest = insertReview(5, BASE.minusDays(3), null);
        Long middle = insertReview(4, BASE.minusDays(2), null);
        Long newest = insertReview(4, BASE.minusDays(1), null);

        mockMvc.perform(get(url(store.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.3)) // 13 / 3 = 4.333...
                .andExpect(jsonPath("$.reviewCount").value(3))
                .andExpect(jsonPath("$.reviews[*].reviewId").value(contains(
                        newest.intValue(), middle.intValue(), oldest.intValue())))
                .andExpect(jsonPath("$.reviews[0].writerName").value("방*혁"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("별점순은 별점이 높은 순, 같으면 최신순으로 정렬한다")
    void sortByRating() throws Exception {
        Long lowNew = insertReview(3, BASE.minusDays(1), null);
        Long highOld = insertReview(5, BASE.minusDays(3), null);
        Long highNew = insertReview(5, BASE.minusDays(2), null);

        mockMvc.perform(get(url(store.getId())).param("sort", "RATING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews[*].reviewId").value(contains(
                        highNew.intValue(), highOld.intValue(), lowNew.intValue())));
    }

    @Test
    @DisplayName("삭제한 리뷰는 목록·평균·개수에서 모두 빠진다")
    void excludesDeletedReviews() throws Exception {
        Long visible = insertReview(4, BASE.minusDays(2), null);
        insertReview(1, BASE.minusDays(1), BASE);

        mockMvc.perform(get(url(store.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.0))
                .andExpect(jsonPath("$.reviewCount").value(1))
                .andExpect(jsonPath("$.reviews[*].reviewId").value(contains(visible.intValue())));
    }

    @Test
    @DisplayName("다른 매장의 리뷰는 포함하지 않는다")
    void onlyTargetStore() throws Exception {
        Long mine = insertReview(5, BASE.minusDays(1), null);
        Store otherStore = store(member("review-owner2@example.com", "점주2", UserType.OWNER), "다른 매장");
        entityManager.flush();
        tableId = insertTable(otherStore.getId());
        insertReview(otherStore, 1, BASE.minusDays(1), null);

        mockMvc.perform(get(url(store.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(1))
                .andExpect(jsonPath("$.reviews[*].reviewId").value(contains(mine.intValue())));
    }

    @Test
    @DisplayName("다음 페이지가 있으면 hasNext가 true다")
    void paging() throws Exception {
        insertReview(5, BASE.minusDays(3), null);
        insertReview(4, BASE.minusDays(2), null);
        Long newest = insertReview(3, BASE.minusDays(1), null);

        mockMvc.perform(get(url(store.getId())).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(3))
                .andExpect(jsonPath("$.reviews[*].reviewId").value(contains(newest.intValue())))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("리뷰가 없으면 평균 별점은 null, 개수는 0이다")
    void noReviews() throws Exception {
        mockMvc.perform(get(url(store.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(nullValue()))
                .andExpect(jsonPath("$.reviewCount").value(0))
                .andExpect(jsonPath("$.reviews").isEmpty());
    }

    @Test
    @DisplayName("없는 매장이면 404")
    void storeNotFound() throws Exception {
        mockMvc.perform(get(url(Long.MAX_VALUE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
    }

    @Test
    @DisplayName("size가 50을 넘으면 400")
    void sizeTooLarge() throws Exception {
        mockMvc.perform(get(url(store.getId())).param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    private static String url(Long storeId) {
        return "/api/user/stores/" + storeId + "/reviews";
    }

    private Long insertReview(int rating, OffsetDateTime createdAt, OffsetDateTime deletedAt) {
        return insertReview(store, rating, createdAt, deletedAt);
    }

    // 작성 시각·삭제 시각을 정해서 넣어야 정렬·삭제 제외를 검증할 수 있어 SQL로 직접 넣는다
    private Long insertReview(Store target, int rating, OffsetDateTime createdAt, OffsetDateTime deletedAt) {
        Long reservationId = completedReservation(target);
        return jdbcTemplate.queryForObject("""
                INSERT INTO review (reservation_id, rating, content, created_at, updated_at, deleted_at)
                VALUES (?, ?, '맛있어요', ?, ?, ?)
                RETURNING id
                """, Long.class, reservationId, rating, createdAt, createdAt, deletedAt);
    }

    // 리뷰 1건당 예약 1건 (reservation_id UNIQUE). 같은 테이블이라 시간대가 겹치지 않게 하루씩 띄운다
    private Long completedReservation(Store target) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC)
                .minusYears(1)
                .plusDays(reservationCount++)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        Reservation reservation = Reservation.confirmed(
                customer, target, tableId, start, start.plusHours(2), 2, start.minusDays(1));
        entityManager.persist(reservation);
        entityManager.flush();
        return reservation.getId();
    }

    private Member member(String email, String name, UserType userType) {
        Member member = Member.builder()
                .email(email)
                .password("password")
                .name(name)
                .phone("010-0000-0000")
                .userType(userType)
                .build();
        entityManager.persist(member);
        return member;
    }

    private Store store(Member owner, String name) {
        Store store = Store.builder()
                .owner(owner)
                .name(name)
                .category(StoreCategory.KOREAN)
                .address("Seoul")
                .reservationDurationMinutes(120)
                .reservationSlotMinutes(30)
                .arrivalGraceMinutes(10)
                .build();
        entityManager.persist(store);
        return store;
    }

    private Long insertTable(Long storeId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO store_table (store_id, table_number, capacity, status)
                VALUES (?, 1, 4, 'ACTIVE')
                RETURNING id
                """, Long.class, storeId);
    }
}
