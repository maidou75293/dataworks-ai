package com.lab.dataworks.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gravitino REST 客户端（V101）。
 *
 * <p>只暴露本期需要的 6 个动作：列 Catalog、查单个、创建、ALTER（属性/改名）、删除。
 * 约定：</p>
 * <ul>
 *   <li>连接/读取超时各 3 秒；对「连接失败 / 5xx / 429」重试 1 次，业务 4xx 不重试
 *       （重复 CREATE 会撞唯一约束，删 Catalog 本身幂等）。</li>
 *   <li>失败抛 {@link GravitinoException}，带固定错误码 + HTTP 状态 + 请求地址 +
 *       Gravitino 原始 message，前端和验收脚本可按码定位。</li>
 *   <li>删除默认带 {@code force=true}：Gravitino 的软删除（写 deleted_at），
 *       只级联清理它自己的元数据，不会删 Doris 里的库表。</li>
 * </ul>
 */
@Component
public class GravitinoClient {

    private static final Logger log = LoggerFactory.getLogger(GravitinoClient.class);
    private static final String DEFAULT_BASE_URL = "http://gravitino.dw-dev.svc.cluster.local:8090";
    private static final String DEFAULT_METALAKE = "dataworks";
    private static final long RETRY_DELAY_MS = 100L;
    /**
     * Gravitino REST 只按 {@code application/vnd.gravitino.v1+json} 做内容协商：
     * 发 {@code application/json} 时列表接口直接 406（2026-10-26 v500 冒烟实测）。
     */
    static final String ACCEPT = "application/vnd.gravitino.v1+json";

    private final RestTemplate http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String metalake;
    private final int retries;

    @Autowired
    public GravitinoClient(RestTemplateBuilder builder, ObjectMapper mapper,
                           @Value("${gravitino.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                           @Value("${gravitino.metalake:" + DEFAULT_METALAKE + "}") String metalake,
                           @Value("${gravitino.timeout-seconds:3}") long timeoutSeconds,
                           @Value("${gravitino.retries:1}") int retries) {
        this(builder
                        .setConnectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .build(),
                mapper, baseUrl, metalake, retries);
    }

    /** 测试用：直接注入 RestTemplate（MockRestServiceServer 需要拿到同一个实例）。 */
    GravitinoClient(RestTemplate http, ObjectMapper mapper, String baseUrl, String metalake,
                    int retries) {
        this.http = http;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.baseUrl = stripTrailingSlash(baseUrl == null || baseUrl.isBlank()
                ? DEFAULT_BASE_URL : baseUrl.trim());
        this.metalake = metalake == null || metalake.isBlank() ? DEFAULT_METALAKE : metalake.trim();
        this.retries = Math.max(0, retries);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String metalake() {
        return metalake;
    }

    /** GET /api/metalakes/{metalake}/catalogs */
    public List<String> listCatalogs() {
        JsonNode root = getJson(catalogsPath());
        List<String> names = new ArrayList<>();
        JsonNode identifiers = root == null ? null : root.path("identifiers");
        if (identifiers != null && identifiers.isArray()) {
            for (JsonNode item : identifiers) {
                String name = item.path("name").asText("");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** GET /api/metalakes/{metalake}/catalogs/{catalog}；不存在返回 null。 */
    public Map<String, Object> getCatalog(String catalog) {
        JsonNode node;
        try {
            node = getJson(catalogPath(catalog));
        } catch (GravitinoException e) {
            if (e.notFound()) {
                return null;
            }
            throw e;
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return mapper.convertValue(node,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    /** Catalog 是否存在。404 = false，其余错误照抛，避免把 Gravitino 故障当成「不存在」。 */
    public boolean catalogExists(String catalog) {
        return getCatalog(catalog) != null;
    }

    /**
     * GET /api/metalakes/{metalake}/catalogs/{catalog}/schemas
     *
     * <p>响应兼容两种形态：标准 {@code identifiers:[{name:..}]}，以及部分版本在
     * {@code details=true} 下返回的 {@code schemas:[{name:..}]}；元素是纯字符串时也接受。
     * 顺序按 Gravitino 返回顺序去重，调用方需要稳定顺序时自行排序。</p>
     */
    public List<String> listSchemas(String catalog) {
        return namesOf(getJson(schemasPath(catalog)), "schemas");
    }

    /** GET .../schemas/{schema}/tables；只返回表名（不逐表拉 columns）。 */
    public List<String> listTables(String catalog, String schema) {
        return namesOf(getJson(tablesPath(catalog, schema)), "tables");
    }

    /**
     * GET .../schemas/{schema}/tables/{table}；不存在返回 null（与 getCatalog 同口径）。
     *
     * <p>响应体在 Gravitino 1.x 是 {@code {"code":0,"table":{...}}}，也兼容直接返回
     * table 对象的旧版本；本方法只做 JSON → Map 的转换，列字段归一化在 MetadataReader。</p>
     */
    public Map<String, Object> getTable(String catalog, String schema, String table) {
        JsonNode node;
        try {
            node = getJson(tablePath(catalog, schema, table));
        } catch (GravitinoException e) {
            if (e.notFound()) {
                return null;
            }
            throw e;
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path("table");
        if (!value.isObject()) {
            value = node;
        }
        return mapper.convertValue(value,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    /** POST /api/metalakes/{metalake}/catalogs */
    public void createCatalog(String name, String provider, Map<String, String> properties,
                              String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", "relational");
        body.put("provider", provider);
        body.put("comment", comment == null ? "" : comment);
        body.put("properties", properties == null ? Map.of() : properties);
        call(HttpMethod.POST, catalogsPath(), body);
    }

    /** PUT /api/metalakes/{metalake}/catalogs/{catalog}（setProperty 列表）。 */
    public void alterCatalog(String catalog, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        List<Map<String, Object>> updates = new ArrayList<>();
        properties.forEach((key, value) -> updates.add(Map.of(
                "@type", "setProperty",
                "property", key,
                "value", value == null ? "" : value)));
        call(HttpMethod.PUT, catalogPath(catalog), Map.of("updates", updates));
    }

    /**
     * 改名（数据源改名后 Catalog 名随之变化）。优先 rename 而不是删了重建：
     * 前者保留 catalog_id 下的 schema/table 元数据，也不会产生新的软删记录。
     */
    public void renameCatalog(String catalog, String newName) {
        if (catalog == null || newName == null || catalog.equalsIgnoreCase(newName)) {
            return;
        }
        call(HttpMethod.PUT, catalogPath(catalog), Map.of("updates", List.of(Map.of(
                "@type", "rename", "newName", newName))));
    }

    /** DELETE /api/metalakes/{metalake}/catalogs/{catalog}?force=true（软删，幂等）。 */
    public void deleteCatalog(String catalog) {
        try {
            call(HttpMethod.DELETE, catalogPath(catalog) + "?force=true", null);
        } catch (GravitinoException e) {
            if (e.notFound()) {
                return;
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- 内部

    private JsonNode getJson(String path) {
        String body = call(HttpMethod.GET, path, null);
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new GravitinoException(GravitinoException.ERROR, 0,
                    "GET " + baseUrl + path, "响应不是合法 JSON: " + e.getMessage(), false);
        }
    }

    private String catalogsPath() {
        return "/api/metalakes/" + encode(metalake) + "/catalogs";
    }

    private String catalogPath(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            throw new GravitinoException(GravitinoException.BAD_REQUEST, 0,
                    catalogsPath(), "catalog 名不能为空", false);
        }
        return catalogsPath() + "/" + encode(catalog);
    }

    private String schemasPath(String catalog) {
        return catalogPath(catalog) + "/schemas";
    }

    private String tablesPath(String catalog, String schema) {
        if (schema == null || schema.isBlank()) {
            throw new GravitinoException(GravitinoException.BAD_REQUEST, 0,
                    schemasPath(catalog), "schema 名不能为空", false);
        }
        return schemasPath(catalog) + "/" + encode(schema) + "/tables";
    }

    private String tablePath(String catalog, String schema, String table) {
        if (table == null || table.isBlank()) {
            throw new GravitinoException(GravitinoException.BAD_REQUEST, 0,
                    tablesPath(catalog, schema), "table 名不能为空", false);
        }
        return tablesPath(catalog, schema) + "/" + encode(table);
    }

    /**
     * 从列表响应里取名字：优先 identifiers，其次同名的业务数组（schemas/tables）；
     * 元素既支持对象 {@code {name:..}} 也支持裸字符串，按出现顺序去重。
     */
    static List<String> namesOf(JsonNode root, String arrayKey) {
        Set<String> names = new LinkedHashSet<>();
        if (root != null) {
            collectNames(root.path("identifiers"), names);
            collectNames(root.path(arrayKey), names);
        }
        return new ArrayList<>(names);
    }

    private static void collectNames(JsonNode array, Set<String> out) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            String name = item.isTextual() ? item.asText("") : item.path("name").asText("");
            if (name != null && !name.isBlank()) {
                out.add(name);
            }
        }
    }

    private String call(HttpMethod method, String path, Object body) {
        String url = baseUrl + path;
        String payload;
        try {
            payload = body == null ? null : mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new GravitinoException(GravitinoException.BAD_REQUEST, 0,
                    method + " " + url, "请求体序列化失败: " + e.getMessage(), false);
        }

        GravitinoException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                // 必须用 Gravitino 的媒体类型；application/json 会被 406 拒绝
                headers.setAccept(List.of(MediaType.parseMediaType(ACCEPT)));
                ResponseEntity<String> response = http.exchange(
                        url, method, new HttpEntity<>(payload, headers), String.class);
                return response.getBody();
            } catch (RestClientResponseException e) {
                GravitinoException mapped = mapResponse(e, method, url);
                if (!mapped.transientFailure() || attempt >= retries) {
                    throw mapped;
                }
                last = mapped;
            } catch (ResourceAccessException e) {
                GravitinoException mapped = new GravitinoException(
                        GravitinoException.UNAVAILABLE, 0, method + " " + url,
                        e.getMostSpecificCause() == null
                                ? e.getMessage() : e.getMostSpecificCause().getMessage(), true);
                if (attempt >= retries) {
                    throw mapped;
                }
                last = mapped;
            } catch (RestClientException e) {
                throw new GravitinoException(GravitinoException.ERROR, 0,
                        method + " " + url, e.getMessage(), false);
            }
            log.warn("gravitino call failed, retrying ({}/{}): {}", attempt + 1, retries, last);
            sleep();
        }
        throw last == null ? new GravitinoException(GravitinoException.ERROR, 0,
                method + " " + url, "unknown", false) : last;
    }

    private GravitinoException mapResponse(RestClientResponseException e, HttpMethod method,
                                           String url) {
        int status = e.getStatusCode().value();
        String code;
        boolean transientFailure = false;
        if (status == 404) {
            code = GravitinoException.NOT_FOUND;
        } else if (status == 409) {
            code = GravitinoException.CONFLICT;
        } else if (status == 401 || status == 403) {
            code = GravitinoException.FORBIDDEN;
        } else if (status >= 500 || status == 429) {
            code = GravitinoException.UNAVAILABLE;
            transientFailure = true;
        } else if (status >= 400) {
            code = GravitinoException.BAD_REQUEST;
        } else {
            code = GravitinoException.ERROR;
        }
        return new GravitinoException(code, status, method + " " + url,
                detailOf(e.getResponseBodyAsString()), transientFailure);
    }

    /** Gravitino 错误体是 {"code":..,"type":..,"message":..}；取 message/type 便于诊断。 */
    private String detailOf(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(body);
            String type = node.path("type").asText("");
            String message = node.path("message").asText("");
            if (!type.isBlank() || !message.isBlank()) {
                return (type + " " + message).trim();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体（网关 HTML）原样截断返回
        }
        return body;
    }

    private static String encode(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
