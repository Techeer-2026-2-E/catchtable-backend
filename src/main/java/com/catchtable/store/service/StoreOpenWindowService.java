package com.catchtable.store.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Clock;

// 날짜별 영업 구간(store_open_window) 준비. 계산 규칙은 V43 SQL 함수 한 곳
@Service
@RequiredArgsConstructor
public class StoreOpenWindowService {

    // 매일 미리 계산할 날짜 수 (기본 예약 가능 일수). 넘는 날짜는 검색 때 계산
    static final int PRECOMPUTE_DAYS = 30;

    private static final String ENSURE_SQL = "SELECT ensure_store_open_window(ARRAY[CAST(? AS DATE), CAST(? AS DATE)])";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    // 검색 날짜와 전날(자정 넘김 영업) 준비. 호출한 트랜잭션에 포함
    public void ensureForSearch(LocalDate date) {
        Integer covered = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM store_open_window_date WHERE business_date IN (?, ?)",
                Integer.class, date, date.minusDays(1));
        if (covered != null && covered == 2) {
            return;
        }
        // 예약 가능 범위 밖 날짜는 결과가 항상 비므로 계산 생략 (임의 날짜로 테이블이 커지는 것 방지)
        LocalDate today = LocalDate.now(clock);
        Integer maxOpenDays = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(booking_open_days), 0) FROM store WHERE owner_id IS NOT NULL", Integer.class);
        if (date.isBefore(today) || date.isAfter(today.plusDays(maxOpenDays))) {
            return;
        }
        jdbcTemplate.queryForList(ENSURE_SQL, date.minusDays(1), date);
    }

    // 하루 1번: 어제 이전 삭제, 어제~오늘+PRECOMPUTE_DAYS 채움 (날짜 하나씩, 호출 트랜잭션 없으면 날짜별 커밋)
    public void refreshDaily() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        jdbcTemplate.update("DELETE FROM store_open_window_date WHERE business_date < ?", yesterday);
        jdbcTemplate.update("DELETE FROM store_open_window WHERE business_date < ?", yesterday);
        for (int i = 0; i <= PRECOMPUTE_DAYS + 1; i++) {
            LocalDate date = yesterday.plusDays(i);
            jdbcTemplate.queryForList(ENSURE_SQL, date, date);
        }
    }
}
