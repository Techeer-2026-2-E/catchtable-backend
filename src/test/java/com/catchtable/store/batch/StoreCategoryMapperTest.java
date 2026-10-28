package com.catchtable.store.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.catchtable.store.entity.StoreCategory.*;
import static org.assertj.core.api.Assertions.assertThat;

class StoreCategoryMapperTest {

    @Test
    @DisplayName("업태구분명을 매장 카테고리로 바꾼다")
    void mapsBizType() {
        assertThat(StoreCategoryMapper.map("한식")).isEqualTo(KOREAN);
        assertThat(StoreCategoryMapper.map("통닭(치킨)")).isEqualTo(KOREAN);
        assertThat(StoreCategoryMapper.map("일식")).isEqualTo(JAPANESE);
        assertThat(StoreCategoryMapper.map("중국식")).isEqualTo(CHINESE);
        assertThat(StoreCategoryMapper.map("경양식")).isEqualTo(WESTERN);
        assertThat(StoreCategoryMapper.map("까페")).isEqualTo(CAFE);
        assertThat(StoreCategoryMapper.map("호프/통닭")).isEqualTo(BAR);
    }

    @Test
    @DisplayName("목록에 없는 업태와 빈 값은 ETC")
    void unknownIsEtc() {
        assertThat(StoreCategoryMapper.map("외국음식전문점(인도,태국등)")).isEqualTo(ETC);
        assertThat(StoreCategoryMapper.map("뷔페식")).isEqualTo(ETC);
        assertThat(StoreCategoryMapper.map(null)).isEqualTo(ETC);
    }
}
