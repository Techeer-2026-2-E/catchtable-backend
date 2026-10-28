package com.catchtable.store.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRestaurantCsvReaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("MS949 CSV를 헤더 이름으로 한 줄씩 읽는다")
    void readsMs949CsvByHeaderName() throws Exception {
        // 실제 파일처럼 필요 없는 컬럼이 섞여 있고 순서도 다르다
        Path csv = tempDir.resolve("restaurants.csv");
        Files.writeString(csv, """
                개방자치단체코드,사업장명,관리번호,영업상태명,전화번호,지번주소,도로명주소,최종수정일자,업태구분명,X좌표,Y좌표
                3000000,"플루토, 내수점",3000000-101-2020-00001,영업/정상,02-123-4567,서울특별시 종로구 내수동 1,서울특별시 종로구 사직로 1,2026-10-01,경양식,953402.877076,1952863.136712
                3000000,폐업식당,3000000-101-2020-00002,폐업,,서울특별시 종로구 내수동 2,,2026-10-01,한식,,
                """, Charset.forName("MS949"));

        List<PublicRestaurantRow> rows = new ArrayList<>();
        new PublicRestaurantCsvReader().read(csv, rows::add);

        assertThat(rows).hasSize(2);
        PublicRestaurantRow first = rows.get(0);
        assertThat(first.mgtNo()).isEqualTo("3000000-101-2020-00001");
        assertThat(first.bizName()).isEqualTo("플루토, 내수점");   // 따옴표 안 쉼표
        assertThat(first.bizType()).isEqualTo("경양식");
        assertThat(first.x()).isEqualTo("953402.877076");
        PublicRestaurantRow second = rows.get(1);
        assertThat(second.isOpen()).isFalse();
        assertThat(second.phone()).isNull();
        assertThat(second.hasCoordinate()).isFalse();
    }

    @Test
    @DisplayName("MS949로 해석되지 않는 바이트가 있어도 멈추지 않고 끝까지 읽는다")
    void keepsReadingMalformedBytes() throws Exception {
        Charset ms949 = Charset.forName("MS949");
        Path csv = tempDir.resolve("malformed.csv");
        try (var out = Files.newOutputStream(csv)) {
            out.write("관리번호,영업상태명,전화번호,지번주소,도로명주소,사업장명,최종수정일자,업태구분명,X좌표,Y좌표\n".getBytes(ms949));
            // 실제 원본(53816행)처럼 사업장명 중간에 깨진 바이트(0x82 뒤에 쉼표)가 있다
            out.write("3200000-101-1989-02013,폐업,,서울특별시 강남구,,때".getBytes(ms949));
            out.write(0x82);
            out.write(",1989-01-01,분식,,\n".getBytes(ms949));
            out.write("3000000-101-2020-00001,영업/정상,,서울특별시 종로구,,플루토,2026-10-01,경양식,953402.877076,1952863.136712\n".getBytes(ms949));
        }

        List<PublicRestaurantRow> rows = new ArrayList<>();
        new PublicRestaurantCsvReader().read(csv, rows::add);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).bizName()).contains("\uFFFD");
        assertThat(rows.get(0).isOpen()).isFalse();
        assertThat(rows.get(1).bizName()).isEqualTo("플루토");
    }
}
