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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MyReviewControllerTest {

    private static final String IMAGE = "https://img.example.com/a.jpg";
    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-10-01T12:00:00+09:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Member me;
    private Member other;
    private Store store;
    private Long tableId;
    private int reservationCount;

    @BeforeEach
    void setUp() {
        Member owner = member("my-review-owner@example.com", UserType.OWNER);
        me = member("my-review-me@example.com", UserType.CUSTOMER);
        other = member("my-review-other@example.com", UserType.CUSTOMER);
        store = store(owner, "리뷰 매장");
        entityManager.flush();
        tableId = insertTable(store.getId());
    }

    // ---- 수정 ----

    @Test
    @DisplayName("보낸 필드만 바뀌고 나머지는 그대로다")
    void updateOnlySentFields() throws Exception {
        Long reviewId = insertReview(me, 4, IMAGE, BASE, null);

        mockMvc.perform(patch(reviewUrl(reviewId)).header("X-Member-Id", me.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.content").value("맛있어요"))
                .andExpect(jsonPath("$.imageUrl").value(IMAGE));
    }

    @Test
    @DisplayName("imageUrl을 빈 문자열로 보내면 사진이 삭제된다")
    void emptyImageUrlRemovesImage() throws Exception {
        Long reviewId = insertReview(me, 4, IMAGE, BASE, null);

        mockMvc.perform(patch(reviewUrl(reviewId)).header("X-Member-Id", me.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(nullValue()))
                .andExpect(jsonPath("$.rating").value(4));

        entityManager.flush();
        assertThat(column(reviewId, "image_url")).isNull();
    }

    @Test
    @DisplayName("다른 사진 URL을 보내면 사진이 교체된다")
    void replaceImage() throws Exception {
        Long reviewId = insertReview(me, 4, IMAGE, BASE, null);

        mockMvc.perform(patch(reviewUrl(reviewId)).header("X-Member-Id", me.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\": \"https://img.example.com/b.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("https://img.example.com/b.jpg"));
    }

    @Test
    @DisplayName("공백만 있는 내용, 범위를 벗어난 별점은 400")
    void invalidUpdate() throws Exception {
        Long reviewId = insertReview(me, 4, null, BASE, null);

        mockMvc.perform(patch(reviewUrl(reviewId)).header("X-Member-Id", me.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(reviewUrl(reviewId)).header("X-Member-Id", me.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\": 6}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("남의 리뷰, 삭제된 리뷰, 없는 리뷰는 수정할 수 없다 (404)")
    void updateNotFound() throws Exception {
        Long othersReview = insertReview(other, 4, null, BASE, null);
        Long deletedReview = insertReview(me, 4, null, BASE, BASE);

        for (Long reviewId : new Long[]{othersReview, deletedReview, Long.MAX_VALUE}) {
            mockMvc.perform(patch(reviewUrl(reviewId)).header("X-Member-Id", me.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rating\": 5}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
        }
    }

    // ---- 삭제 ----

    @Test
    @DisplayName("삭제하면 204이고 매장 리뷰 목록에서 빠진다")
    void deleteReview() throws Exception {
        Long reviewId = insertReview(me, 4, null, BASE, null);

        mockMvc.perform(delete(reviewUrl(reviewId)).header("X-Member-Id", me.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/user/stores/" + store.getId() + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount").value(0))
                .andExpect(jsonPath("$.reviews").isEmpty());
    }

    @Test
    @DisplayName("이미 삭제한 내 리뷰를 다시 삭제해도 204이고 처음 삭제 시각은 유지된다")
    void deleteTwice() throws Exception {
        Long reviewId = insertReview(me, 4, null, BASE, BASE);

        mockMvc.perform(delete(reviewUrl(reviewId)).header("X-Member-Id", me.getId()))
                .andExpect(status().isNoContent());

        entityManager.flush();
        OffsetDateTime deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM review WHERE id = ?", OffsetDateTime.class, reviewId);
        assertThat(deletedAt.toInstant()).isEqualTo(BASE.toInstant());
    }

    @Test
    @DisplayName("남의 리뷰와 없는 리뷰는 삭제할 수 없다 (404)")
    void deleteNotFound() throws Exception {
        Long othersReview = insertReview(other, 4, null, BASE, null);

        mockMvc.perform(delete(reviewUrl(othersReview)).header("X-Member-Id", me.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(reviewUrl(Long.MAX_VALUE)).header("X-Member-Id", me.getId()))
                .andExpect(status().isNotFound());

        entityManager.flush();
        assertThat(column(othersReview, "deleted_at")).isNull();
    }

    // ---- 내 리뷰 목록 ----

    @Test
    @DisplayName("내 리뷰만 최신순으로, 삭제 리뷰는 빼고, 매장 정보와 함께 조회한다")
    void myReviews() throws Exception {
        Long older = insertReview(me, 3, null, BASE.minusDays(2), null);
        Long newer = insertReview(me, 5, IMAGE, BASE.minusDays(1), null);
        insertReview(me, 1, null, BASE, BASE);           // 삭제된 내 리뷰
        insertReview(other, 4, null, BASE, null);        // 남의 리뷰

        mockMvc.perform(get("/api/user/members/me/reviews").header("X-Member-Id", me.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews[*].reviewId").value(contains(
                        newer.intValue(), older.intValue())))
                .andExpect(jsonPath("$.reviews[0].storeId").value(store.getId().intValue()))
                .andExpect(jsonPath("$.reviews[0].storeName").value("리뷰 매장"))
                .andExpect(jsonPath("$.reviews[0].visitedAt").exists())
                .andExpect(jsonPath("$.reviews[0].imageUrl").value(IMAGE))
                .andExpect(jsonPath("$.reviews[0].updatedAt").exists())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("회원 식별 헤더가 없으면 401")
    void unauthorized() throws Exception {
        mockMvc.perform(get("/api/user/members/me/reviews"))
                .andExpect(status().isUnauthorized());
    }

    private static String reviewUrl(Long reviewId) {
        return "/api/user/reviews/" + reviewId;
    }

    private Object column(Long reviewId, String column) {
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM review WHERE id = ?", reviewId);
        return row.get(column);
    }

    // 작성·삭제 시각을 정해서 넣어야 정렬·삭제 여부를 검증할 수 있어 SQL로 직접 넣는다
    private Long insertReview(Member writer, int rating, String imageUrl,
                              OffsetDateTime createdAt, OffsetDateTime deletedAt) {
        Long reservationId = reservation(writer);
        return jdbcTemplate.queryForObject("""
                INSERT INTO review (reservation_id, rating, content, image_url, created_at, updated_at, deleted_at)
                VALUES (?, ?, '맛있어요', ?, ?, ?, ?)
                RETURNING id
                """, Long.class, reservationId, rating, imageUrl, createdAt, createdAt, deletedAt);
    }

    // 리뷰 1건당 예약 1건 (reservation_id UNIQUE). 같은 테이블이라 시간대가 겹치지 않게 하루씩 띄운다
    private Long reservation(Member writer) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC)
                .minusYears(1)
                .plusDays(reservationCount++)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        Reservation reservation = Reservation.confirmed(
                writer, store, tableId, start, start.plusHours(2), 2, start.minusDays(1));
        entityManager.persist(reservation);
        entityManager.flush();
        return reservation.getId();
    }

    private Member member(String email, UserType userType) {
        Member member = Member.builder()
                .email(email)
                .password("password")
                .name("방준혁")
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
