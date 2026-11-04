package com.lab.dataworks.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantDataScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据地图：把 Gravitino 元数据通过 MCP 的 catalog.* 工具暴露给控制台。
 * 浏览器只访问本服务的 /api/catalog/**，不直接接触 MCP token。
 *
 * <p>v447 P1-1：这条路径直接问 MCP，绕过了表管理的 {@link TenantDataScope}，
 * 外部审计实测 tenant_b 能枚举全部租户的库表。现在 schemas 列表按租户数据面过滤，
 * 表/FileSet 详情用 {@code requireDatabase} 拦（404，不暴露库存在）。</p>
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CatalogController.class);


    private final RestTemplate http;
    private final String mcpBaseUrl;
    private final String mcpToken;
    private final TenantDataScope scope;

    public CatalogController(RestTemplateBuilder builder,
                             @Value("${mcp.base-url}") String mcpBaseUrl,
                             @Value("${mcp.token:}") String mcpToken,
                             TenantDataScope scope) {
        this.http = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.mcpBaseUrl = mcpBaseUrl.endsWith("/")
                ? mcpBaseUrl.substring(0, mcpBaseUrl.length() - 1) : mcpBaseUrl;
        this.mcpToken = mcpToken;
        this.scope = scope;
    }

    @GetMapping("/catalogs")
    public JsonNode catalogs() {
        return call("catalog.list", Map.of());
    }

    @GetMapping("/catalogs/{catalog}/schemas")
    public JsonNode schemas(@PathVariable String catalog) {
        JsonNode result = call("catalog.list_schemas", Map.of("catalog", catalog));
        return filterSchemas(result);
    }

    @GetMapping("/catalogs/{catalog}/schemas/{schema}/tables")
    public JsonNode tables(@PathVariable String catalog, @PathVariable String schema) {
        scope.requireDatabase(schema);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("catalog", catalog);
        params.put("schema", schema);
        return call("catalog.list_tables", params);
    }

    @GetMapping("/catalogs/{catalog}/schemas/{schema}/tables/{table}")
    public JsonNode table(@PathVariable String catalog,
                          @PathVariable String schema,
                          @PathVariable String table) {
        scope.requireDatabase(schema);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("catalog", catalog);
        params.put("schema", schema);
        params.put("table", table);
        return call("catalog.describe_table", params);
    }

    @GetMapping("/catalogs/{catalog}/schemas/{schema}/filesets")
    public JsonNode filesets(@PathVariable String catalog, @PathVariable String schema) {
        scope.requireDatabase(schema);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("catalog", catalog);
        params.put("schema", schema);
        return call("catalog.list_filesets", params);
    }

    @GetMapping("/catalogs/{catalog}/schemas/{schema}/filesets/{fileset}")
    public JsonNode fileset(@PathVariable String catalog,
                            @PathVariable String schema,
                            @PathVariable String fileset) {
        scope.requireDatabase(schema);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("catalog", catalog);
        params.put("schema", schema);
        params.put("fileset", fileset);
        return call("catalog.describe_fileset", params);
    }

    /**
     * 只保留当前租户项目名下的库。默认租户（平台自身）不裁剪。
     * 返回值形如 {@code {"schemas": ["a","b"]}}；形状不认识时原样返回。
     */
    private JsonNode filterSchemas(JsonNode result) {
        if (result == null || scope.defaultTenant()) {
            return result;
        }
        JsonNode list = result.path("schemas");
        if (!list.isArray() || !(result instanceof ObjectNode object)) {
            return result;
        }
        Set<String> owned = scope.ownedDatabases();
        ArrayNode kept = ((ObjectNode) result).arrayNode();
        for (JsonNode item : list) {
            String name = item.asText("");
            if (!name.isBlank() && owned.contains(name.toLowerCase(Locale.ROOT))
                    && !isInternal(name)) {
                kept.add(item);
            }
        }
        object.set("schemas", kept);
        return object;
    }

    private boolean isInternal(String name) {
        return name.startsWith("__") || "information_schema".equalsIgnoreCase(name)
                || "mysql".equalsIgnoreCase(name);
    }

    private static String requestId() {
        String id = org.slf4j.MDC.get("requestId");
        return id == null || id.isBlank() ? "-" : id;
    }

    private JsonNode call(String tool, Map<String, Object> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (mcpToken != null && !mcpToken.isBlank()) {
            headers.setBearerAuth(mcpToken);
        }
        try {
            ResponseEntity<JsonNode> response = http.exchange(
                    mcpBaseUrl + "/tools/" + tool,
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("params", params), headers),
                    JsonNode.class);
            JsonNode body = response.getBody();
            return body == null ? null : body.path("result");
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // v449 P2-5：上游 404 之前被映射成 502 并把上游原始报文（内部服务名/错误码）
            // 回显给调用方。现在 404 归一成 404，其余只给通用文案，详情写日志。
            String detail = e.getResponseBodyAsString();
            log.warn("catalog tool {} failed status={} body={}", tool, e.getStatusCode(), detail);
            if (e.getStatusCode().value() == 404) {
                throw new ApiException(404, "元数据对象不存在");
            }
            throw new ApiException(502, "元数据服务暂时不可用（traceId=" + requestId() + "）");
        } catch (Exception e) {
            log.warn("catalog tool {} error: {}", tool, e.getMessage());
            throw new ApiException(502, "元数据服务暂时不可用（traceId=" + requestId() + "）");
        }
    }
}
