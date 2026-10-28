package com.catchtable.store.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRestaurantRowTest {

    @Test
    @DisplayName("앞뒤 공백은 지우고 빈 값은 null로 바꾼다")
    void cleansValues() {
        PublicRestaurantRow row = row(" 영업/정상 ", "  ", "", "953402.877076", "1952863.136712");

        assertThat(row.statusName()).isEqualTo("영업/정상");
        assertThat(row.phone()).isNull();
        assertThat(row.roadAddress()).isNull();
    }

    @Test
    @DisplayName("영업/정상만 영업 중으로 본다")
    void isOpen() {
        assertThat(row("영업/정상", null, null, "1", "1").isOpen()).isTrue();
        assertThat(row("폐업", null, null, "1", "1").isOpen()).isFalse();
        assertThat(row(null, null, null, "1", "1").isOpen()).isFalse();
    }

    @Test
    @DisplayName("X·Y좌표가 모두 있어야 좌표가 있다고 본다")
    void hasCoordinate() {
        assertThat(row("영업/정상", null, null, "1", "1").hasCoordinate()).isTrue();
        assertThat(row("영업/정상", null, null, "1", " ").hasCoordinate()).isFalse();
        assertThat(row("영업/정상", null, null, null, "1").hasCoordinate()).isFalse();
    }

    private PublicRestaurantRow row(String status, String phone, String roadAddress, String x, String y) {
        return new PublicRestaurantRow("3000000-101-2020-00001", status, phone, "서울특별시 종로구 내수동 1",
                roadAddress, "테스트식당", "2026-10-01", "한식", x, y);
    }
}
