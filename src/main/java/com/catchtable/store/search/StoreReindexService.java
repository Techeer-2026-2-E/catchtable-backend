package com.catchtable.store.search;

import com.catchtable.store.dto.MapStoreResponse;
import com.catchtable.store.entity.StoreCategory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

// 전체 재색인: 새 색인 생성 → 매장·메뉴 일괄 색인 → 별칭 교체 → 이전 색인 삭제
@Slf4j
public class StoreReindexService {

    private static final int CHUNK_SIZE = 1_000;
    private static final String CHUNK_SQL = """
            SELECT s.id, s.name, s.address, s.category, s.owner_id IS NOT NULL AS reservation_enabled,
                   s.waiting_available, s.latitude, s.longitude,
                   ARRAY(SELECT m.name FROM menu m WHERE m.store_id = s.id ORDER BY m.id) AS menus
            FROM store s
            WHERE s.id > ?
            ORDER BY s.id
            LIMIT ?
            """;

    private final StoreSearchIndex index;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;
    private final Clock clock;

    public StoreReindexService(StoreSearchIndex index, JdbcTemplate jdbcTemplate, ObjectMapper mapper, Clock clock) {
        this.index = index;
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
        this.clock = clock;
    }

    // 새 색인 이름 반환
    public String reindexAll() {
        Instant startedAt = clock.instant();
        String newIndex = index.indexName(index.nextVersion());
        index.createIndex(newIndex, indexDefinition());

        long lastId = 0;
        int total = 0;
        while (true) {
            List<Map<String, Object>> docs = jdbcTemplate.query(CHUNK_SQL, (rs, rowNum) -> {
                String address = rs.getString("address");
                Array menus = rs.getArray("menus");
                return StoreSearchIndex.document(
                        rs.getLong("id"), rs.getString("name"), address, MapStoreResponse.areaOf(address),
                        StoreCategory.valueOf(rs.getString("category")),
                        Arrays.asList((String[]) menus.getArray()),
                        rs.getBoolean("reservation_enabled"), rs.getBoolean("waiting_available"),
                        rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class));
            }, lastId, CHUNK_SIZE);
            if (docs.isEmpty()) {
                break;
            }
            index.bulkIndex(newIndex, docs);
            total += docs.size();
            lastId = (long) docs.get(docs.size() - 1).get("id");
        }
        index.refresh(newIndex);
        List<String> deleted = index.switchAliasAndDeleteOld(newIndex);

        log.info("매장 검색 재색인 완료: 색인 {}, 매장 {}건, 삭제한 이전 색인 {}, 소요 {}ms",
                newIndex, total, deleted, Duration.between(startedAt, clock.instant()).toMillis());
        return newIndex;
    }

    // stores-index.json + synonyms.txt
    private Map<String, Object> indexDefinition() {
        try (InputStream json = new ClassPathResource("opensearch/stores-index.json").getInputStream();
             InputStream synonyms = new ClassPathResource("opensearch/synonyms.txt").getInputStream()) {
            Map<String, Object> definition = mapper.readValue(json, new TypeReference<>() {});
            List<String> rules = new ArrayList<>();
            for (String line : new String(synonyms.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String rule = line.strip();
                if (!rule.isEmpty() && !rule.startsWith("#")) {
                    rules.add(rule);
                }
            }
            Map<String, Object> analysis = analysis(definition);
            section(analysis, "filter", "store_synonyms").put("synonyms", rules);
            section(analysis, "char_filter", "synonym_canonical").put("mappings", canonicalMappings(rules));
            return definition;
        } catch (IOException e) {
            throw new IllegalStateException("매장 색인 설정을 읽지 못했습니다", e);
        }
    }

    // 공백 무시 필드용: 같은 뜻 단어를 첫 단어로 통일 (돈가스=>돈까스)
    private static List<String> canonicalMappings(List<String> rules) {
        List<String> mappings = new ArrayList<>();
        for (String rule : rules) {
            String[] words = rule.split(",");
            for (int i = 1; i < words.length; i++) {
                mappings.add(words[i].strip() + "=>" + words[0].strip());
            }
        }
        return mappings;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> analysis(Map<String, Object> definition) {
        Map<String, Object> settings = (Map<String, Object>) definition.get("settings");
        return (Map<String, Object>) settings.get("analysis");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> analysis, String type, String name) {
        return (Map<String, Object>) ((Map<String, Object>) analysis.get(type)).get(name);
    }
}
