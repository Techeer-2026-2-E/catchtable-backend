package com.catchtable.store.batch;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Transactional
class StoreImportServiceTest {

    private static final String HEADER = "관리번호,영업상태명,전화번호,지번주소,도로명주소,사업장명,최종수정일자,업태구분명,X좌표,Y좌표\n";
    private static final String OPEN_ID = "TEST111-101-2020-00001";
    private static final String CLOSED_ID = "TEST111-101-2020-00002";
    private static final String NO_COORD_ID = "TEST111-101-2020-00003";

    @TempDir
    Path tempDir;

    @Autowired
    private StoreImportService storeImportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("영업 중이고 좌표가 있는 매장만 미입점 매장으로 적재한다")
    void importsOnlyOpenStoresWithCoordinate() throws Exception {
        Path csv = csv(HEADER
                + OPEN_ID + ",영업/정상,02-123-4567,서울특별시 종로구 내수동 1,,플루토,2026-10-01,경양식,953402.877076,1952863.136712\n"
                + CLOSED_ID + ",폐업,,서울특별시 종로구 내수동 2,,폐업식당,2026-10-01,한식,953402.877076,1952863.136712\n"
                + NO_COORD_ID + ",영업/정상,,서울특별시 종로구 내수동 3,,좌표없음,2026-10-01,한식,,\n");

        storeImportService.importFrom(csv);

        Map<String, Object> store = findStore(OPEN_ID);
        assertThat(store.get("owner_id")).isNull();
        assertThat(store.get("name")).isEqualTo("플루토");
        assertThat(store.get("category")).isEqualTo("WESTERN");
        assertThat(store.get("address")).isEqualTo("서울특별시 종로구 내수동 1");   // 도로명 없으면 지번
        assertThat(store.get("phone")).isEqualTo("02-123-4567");
        assertThat((Double) store.get("latitude")).isCloseTo(37.574, within(0.01));
        assertThat((Double) store.get("longitude")).isCloseTo(126.972, within(0.01));
        assertThat(countStore(CLOSED_ID)).isZero();
        assertThat(countStore(NO_COORD_ID)).isZero();
    }

    @Test
    @DisplayName("다시 적재하면 중복 없이 미입점 매장 정보를 갱신한다")
    void reimportUpdatesWithoutDuplicate() throws Exception {
        storeImportService.importFrom(csv(HEADER
                + OPEN_ID + ",영업/정상,,서울특별시 종로구 내수동 1,,플루토,2026-10-01,경양식,953402.877076,1952863.136712\n"));
        storeImportService.importFrom(csv(HEADER
                + OPEN_ID + ",영업/정상,,서울특별시 종로구 내수동 1,서울특별시 종로구 사직로 1,플루토 본점,2026-10-02,일식,953402.877076,1952863.136712\n"));

        assertThat(countStore(OPEN_ID)).isEqualTo(1);
        Map<String, Object> store = findStore(OPEN_ID);
        assertThat(store.get("name")).isEqualTo("플루토 본점");
        assertThat(store.get("category")).isEqualTo("JAPANESE");
        assertThat(store.get("address")).isEqualTo("서울특별시 종로구 사직로 1");
    }

    @Test
    @DisplayName("점주가 입점한 매장은 공공데이터로 덮어쓰지 않는다")
    void doesNotOverwriteOwnedStore() throws Exception {
        storeImportService.importFrom(csv(HEADER
                + OPEN_ID + ",영업/정상,,서울특별시 종로구 내수동 1,,플루토,2026-10-01,경양식,953402.877076,1952863.136712\n"));
        Member owner = Member.builder()
                .email("store-import-owner@example.com")
                .password("password")
                .name("owner")
                .phone("010-0000-0111")
                .userType(UserType.OWNER)
                .build();
        entityManager.persist(owner);
        entityManager.flush();
        jdbcTemplate.update("UPDATE store SET owner_id = ?, name = '점주가 바꾼 이름' WHERE external_id = ?",
                owner.getId(), OPEN_ID);

        storeImportService.importFrom(csv(HEADER
                + OPEN_ID + ",영업/정상,,서울특별시 종로구 내수동 1,,플루토 새이름,2026-10-02,경양식,953402.877076,1952863.136712\n"));

        assertThat(findStore(OPEN_ID).get("name")).isEqualTo("점주가 바꾼 이름");
    }

    private Path csv(String content) throws Exception {
        Path csv = Files.createTempFile(tempDir, "restaurants", ".csv");
        Files.writeString(csv, content, Charset.forName("MS949"));
        return csv;
    }

    private Map<String, Object> findStore(String externalId) {
        return jdbcTemplate.queryForMap("SELECT * FROM store WHERE external_id = ?", externalId);
    }

    private int countStore(String externalId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM store WHERE external_id = ?", Integer.class, externalId);
    }
}
