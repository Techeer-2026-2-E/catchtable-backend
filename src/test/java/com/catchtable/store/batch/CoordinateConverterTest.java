package com.catchtable.store.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CoordinateConverterTest {

    private final CoordinateConverter converter = new CoordinateConverter();

    @Test
    @DisplayName("UTM-K 좌표를 위도·경도로 변환한다 (플루토, 종로구 내수동)")
    void toLatLng() {
        double[] latLng = converter.toLatLng(953402.877076, 1952863.136712);

        assertThat(latLng[0]).isCloseTo(37.574, within(0.01));
        assertThat(latLng[1]).isCloseTo(126.972, within(0.01));
    }
}
