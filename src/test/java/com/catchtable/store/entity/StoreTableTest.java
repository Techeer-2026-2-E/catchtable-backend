package com.catchtable.store.entity;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.StoreTableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreTableTest {

    private static StoreTable table(Integer minCapacity, int capacity) {
        return StoreTable.builder()
                .tableNumber(1)
                .minCapacity(minCapacity)
                .capacity(capacity)
                .build();
    }

    @Test
    @DisplayName("최소 인원을 넘기지 않으면 1명부터 받는다")
    void defaultMinCapacity() {
        StoreTable table = table(null, 4);

        assertThat(table.getMinCapacity()).isEqualTo(1);
        assertThat(table.canSeat(1)).isTrue();
    }

    @Test
    @DisplayName("최소~최대 인원 경계값까지 받고 벗어나면 못 받는다")
    void canSeatBoundary() {
        StoreTable table = table(3, 4);

        assertThat(table.canSeat(2)).isFalse();
        assertThat(table.canSeat(3)).isTrue();
        assertThat(table.canSeat(4)).isTrue();
        assertThat(table.canSeat(5)).isFalse();
    }

    @Test
    @DisplayName("최소 인원이 최대 인원보다 크면 생성할 수 없다")
    void createWithMinOverMax() {
        assertThatThrownBy(() -> table(5, 4))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TABLE_CAPACITY);
    }

    @Test
    @DisplayName("최대 인원을 최소 인원보다 작게 바꿀 수 없다")
    void changeToMinOverMax() {
        StoreTable table = table(3, 4);

        assertThatThrownBy(() -> table.changeCapacityRange(3, 2))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TABLE_CAPACITY);
    }

    @Test
    @DisplayName("응답에 최소·최대 인원이 제자리에 담긴다")
    void responseFields() {
        StoreTableResponse response = StoreTableResponse.from(table(2, 4));

        assertThat(response.minCapacity()).isEqualTo(2);
        assertThat(response.capacity()).isEqualTo(4);
    }
}
