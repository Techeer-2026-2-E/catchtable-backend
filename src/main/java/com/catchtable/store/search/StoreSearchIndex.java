package com.catchtable.store.search;

import com.catchtable.store.entity.StoreCategory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// OpenSearch 매장 색인 접근 (별칭 stores → 실제 색인 stores_v{n})
public class StoreSearchIndex {

    private static final String[] TEXT_FIELDS = {"name^3", "menus^2", "address", "area", "categoryName"};

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String alias;
    private final double enteredStoreBoost;
    private final boolean searchEnabled;

    public StoreSearchIndex(RestClient client, ObjectMapper mapper, String alias, double enteredStoreBoost,
                            boolean searchEnabled) {
        this.client = client;
        this.mapper = mapper;
        this.alias = alias;
        this.enteredStoreBoost = enteredStoreBoost;
        this.searchEnabled = searchEnabled;
    }

    // 키워드 검색 사용 여부 (재색인만 켠 경우 false)
    public boolean searchEnabled() {
        return searchEnabled;
    }

    // 관련도 → 입점 매장 가산 → id 순 매장 id 목록
    public List<Long> searchIds(String keyword, StoreCategory category, int from, int size) {
        List<Object> filter = category == null ? List.of() : List.of(Map.of("term", Map.of("category", category.name())));
        Map<String, Object> textMatch = Map.of("multi_match", Map.of(
                "query", keyword, "type", "cross_fields", "operator", "and", "fields", TEXT_FIELDS));
        Map<String, Object> compactMatch = Map.of("match", Map.of(
                "name.compact", Map.of("query", keyword, "operator", "and")));
        Map<String, Object> bool = Map.of(
                "filter", filter, "should", List.of(textMatch, compactMatch), "minimum_should_match", 1);
        Map<String, Object> body = Map.of(
                "from", from, "size", size, "_source", false, "track_total_hits", false,
                "query", Map.of("function_score", Map.of(
                        "query", Map.of("bool", bool),
                        "functions", List.of(Map.of(
                                "filter", Map.of("term", Map.of("reservationEnabled", true)),
                                "weight", enteredStoreBoost)),
                        "boost_mode", "multiply")),
                "sort", List.of(Map.of("_score", "desc"), Map.of("id", "asc")));

        JsonNode hits = client.post().uri("/{alias}/_search", alias)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class)
                .path("hits").path("hits");
        List<Long> ids = new ArrayList<>();
        hits.forEach(hit -> ids.add(Long.parseLong(hit.path("_id").asText())));
        return ids;
    }

    // 기존 stores_v{n} 중 가장 큰 n + 1
    public int nextVersion() {
        JsonNode indices = client.get().uri("/_cat/indices/{pattern}?format=json&h=index", alias + "_v*")
                .retrieve().body(JsonNode.class);
        int max = 0;
        for (JsonNode index : indices) {
            String suffix = index.path("index").asText().substring(alias.length() + 2);
            if (suffix.chars().allMatch(Character::isDigit) && !suffix.isEmpty()) {
                max = Math.max(max, Integer.parseInt(suffix));
            }
        }
        return max + 1;
    }

    public String indexName(int version) {
        return alias + "_v" + version;
    }

    public void createIndex(String index, Map<String, Object> settingsAndMappings) {
        client.put().uri("/{index}", index).contentType(MediaType.APPLICATION_JSON)
                .body(settingsAndMappings).retrieve().toBodilessEntity();
    }

    // 문서 일괄 색인. 한 건이라도 실패하면 예외
    public void bulkIndex(String index, List<Map<String, Object>> documents) {
        bulk(index, documents, List.of(), "/_bulk");
    }

    // 별칭(현재 색인)에 변경 반영: 문서 upsert + 없어진 매장 삭제
    public void applyChanges(List<Map<String, Object>> documents, List<Long> deletedStoreIds) {
        bulk(alias, documents, deletedStoreIds, "/_bulk?refresh=wait_for");   // 반영 직후 검색 가능
    }

    private void bulk(String index, List<Map<String, Object>> documents, List<Long> deleteIds, String uri) {
        if (documents.isEmpty() && deleteIds.isEmpty()) {
            return;
        }
        StringBuilder ndjson = new StringBuilder();
        try {
            for (Map<String, Object> doc : documents) {
                ndjson.append(mapper.writeValueAsString(Map.of("index", Map.of("_index", index, "_id", doc.get("id")))))
                        .append('\n').append(mapper.writeValueAsString(doc)).append('\n');
            }
            for (Long id : deleteIds) {
                ndjson.append(mapper.writeValueAsString(Map.of("delete", Map.of("_index", index, "_id", id))))
                        .append('\n');
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        JsonNode result = client.post().uri(uri)
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(ndjson.toString()).retrieve().body(JsonNode.class);
        if (result.path("errors").asBoolean()) {
            throw new IllegalStateException("OpenSearch bulk 실패: " + firstError(result));
        }
    }

    public void refresh(String index) {
        client.post().uri("/{index}/_refresh", index).retrieve().toBodilessEntity();
    }

    // 별칭을 새 색인으로 옮기고, 새 색인 외의 stores_v* 색인을 지운다
    public List<String> switchAliasAndDeleteOld(String newIndex) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (String old : aliasedIndices()) {
            actions.add(Map.of("remove", Map.of("index", old, "alias", alias)));
        }
        actions.add(Map.of("add", Map.of("index", newIndex, "alias", alias)));
        client.post().uri("/_aliases").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", actions)).retrieve().toBodilessEntity();

        List<String> deleted = new ArrayList<>();
        JsonNode indices = client.get().uri("/_cat/indices/{pattern}?format=json&h=index", alias + "_v*")
                .retrieve().body(JsonNode.class);
        for (JsonNode node : indices) {
            String index = node.path("index").asText();
            if (!index.equals(newIndex)) {
                client.delete().uri("/{index}", index).retrieve().toBodilessEntity();
                deleted.add(index);
            }
        }
        return deleted;
    }

    private List<String> aliasedIndices() {
        try {
            JsonNode body = client.get().uri("/_alias/{alias}", alias).retrieve().body(JsonNode.class);
            List<String> names = new ArrayList<>();
            body.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (HttpClientErrorException.NotFound e) {
            return List.of();
        }
    }

    private static String firstError(JsonNode bulkResult) {
        for (JsonNode item : bulkResult.path("items")) {
            for (JsonNode op : item) {
                if (op.has("error")) {
                    return op.path("error").toString();
                }
            }
        }
        return "unknown";
    }

    // 색인 문서 한 건
    public static Map<String, Object> document(long id, String name, String address, String area, StoreCategory category,
                                               List<String> menus, boolean reservationEnabled, boolean waitingAvailable,
                                               Double latitude, Double longitude) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("name", name);
        doc.put("address", address);
        doc.put("area", area);
        doc.put("category", category.name());
        doc.put("categoryName", category.getDescription());
        doc.put("menus", menus);
        doc.put("reservationEnabled", reservationEnabled);
        doc.put("waitingAvailable", waitingAvailable);
        if (latitude != null && longitude != null) {
            doc.put("location", Map.of("lat", latitude, "lon", longitude));
        }
        return doc;
    }
}
