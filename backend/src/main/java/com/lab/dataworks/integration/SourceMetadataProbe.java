package com.lab.dataworks.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.secret.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * REQ-DW-16：源端结构预览的连接逻辑收口在这里，两条路径共用同一实现——
 *
 * <ul>
 *   <li>已保存的源（REQ-DW-15）：{@code GET /api/integration/datasource/{id}/...}
 *       → {@link #specOfSaved(long)}，按 id 查库并解密密码；</li>
 *   <li>未保存的表单（本卡）：{@code POST /api/integration/datasource/preview/...}
 *       → {@link #specOfAdhoc(AdhocRequest)}，连接参数只在本请求内存活，
 *       <b>不落库、不保存凭据</b>。</li>
 * </ul>
 *
 * <p>整个探针只读：不写源端、不建任务；Kafka 字段结构来自抽样消息推断并显式标注。</p>
 */
@Component
public class SourceMetadataProbe {

    /** 支持元数据预览的 JDBC 类型；SPARK（Kyuubi）的 DatabaseMetaData 不可靠，不放开。 */
    private static final Set<String> JDBC_TYPES = Set.of("MYSQL", "POSTGRESQL", "DORIS");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretCipher cipher;
    private final KafkaPreviewService kafka;
    private final SourceMetadataService metadata;

    public SourceMetadataProbe(JdbcTemplate jdbc, ObjectMapper json, SecretCipher cipher,
                               KafkaPreviewService kafka, SourceMetadataService metadata) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
        this.kafka = kafka;
        this.metadata = metadata;
    }

    /** 一次预览所需的连接参数；password 为<b>明文</b>，仅在本请求内存活，绝不落库。 */
    public record Spec(String type, String jdbcUrl, String username, String password,
                       Map<String, Object> config) {
    }

    /**
     * 未保存表单的预览请求体。type/jdbcUrl/username/password/config 与数据源表单同名字段；
     * id 仅用于「编辑时密码留空复用已存密码」（守卫见 {@link #specOfAdhoc}）；
     * table/catalog/schema/sampleLimit 只在 /preview/columns 用到。
     * 多余表单字段（name/env 等）容忍并忽略——本接口不落库，不需要完整数据源定义。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdhocRequest(String type, String jdbcUrl, String username, String password,
                               Map<String, Object> config, Long id, String table,
                               String catalog, String schema, Integer sampleLimit) {
    }

    // ------------------------------------------------------------------ 入口

    /** 表（或 Kafka topic）清单。 */
    public Map<String, Object> tablesOf(Spec spec) {
        String type = normalizeType(spec.type());
        Map<String, Object> out = new LinkedHashMap<>();
        if ("KAFKA".equals(type)) {
            List<String> topics = kafka.topics(bootstrapOfConfig(spec.config()));
            out.put("kind", "KAFKA");
            out.put("items", topics.stream().sorted().map(t -> Map.<String, Object>of(
                    "name", t, "type", "TOPIC")).toList());
            out.put("truncated", false);
            return out;
        }
        if (!JDBC_TYPES.contains(type)) {
            throw ApiException.badRequest("该数据源类型（" + type + "）暂不支持结构预览");
        }
        try (Connection conn = open(spec)) {
            List<Map<String, Object>> items = metadata.tables(conn);
            out.put("kind", "JDBC");
            out.put("product", conn.getMetaData().getDatabaseProductName());
            out.put("items", items);
            out.put("truncated", items.size() >= SourceMetadataService.TABLE_LIMIT);
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw connectFailure(e);
        }
    }

    /**
     * 单表字段定义；Kafka 下同名为 topic，返回抽样推断结构。
     *
     * <p>DL-F3（DL-1 发现 3）：JDBC 路径此前把「查不到列」原样包成 200 空列——
     * 现在补两件事：</p>
     * <ul>
     *   <li>{@code schema.table} / {@code catalog.schema.table} 限定名按类型拆回
     *       精确锁定参数（`.` 在 DatabaseMetaData 里是普通字符，整串当表名必然 0 命中）；
     *       与显式 {@code ?catalog=/?schema=} 冲突时 400，不猜。</li>
     *   <li>列查空后再做一次表存在性探测，仍找不到 → 404「表不存在或当前账号不可见」
     *       （无权限的模式 getColumns 同样返回空，两者在 DatabaseMetaData 层不可区分，
     *       文案不写死「不存在」）。与 Kafka 分支的「topic 不存在→404」口径对齐。</li>
     * </ul>
     *
     * @param catalog/schema 由 tables 结果原样回传，用于精确锁定（可空）
     */
    public Map<String, Object> columnsOf(Spec spec, String table, String catalog, String schema,
                                         Integer sampleLimit) {
        String name = requireValidName(table, "表名");
        String type = normalizeType(spec.type());
        if ("KAFKA".equals(type)) {
            return kafkaColumns(bootstrapOfConfig(spec.config()), name,
                    sampleLimit == null ? 10 : sampleLimit);
        }
        if (!JDBC_TYPES.contains(type)) {
            throw ApiException.badRequest("该数据源类型（" + type + "）暂不支持结构预览");
        }
        String useCatalog = catalog == null || catalog.isBlank() ? null : requireValidName(catalog, "库名");
        String useSchema = schema == null || schema.isBlank() ? null : requireValidName(schema, "模式名");
        Qualified qualified = resolveQualifiedName(type, name);
        if (qualified.catalog() != null) {
            useCatalog = mergeScope(qualified.catalog(), useCatalog, "catalog");
        }
        if (qualified.schema() != null) {
            useSchema = mergeScope(qualified.schema(), useSchema, "schema");
        }
        String tableName = qualified.table();
        try (Connection conn = open(spec)) {
            List<Map<String, Object>> columns = metadata.columns(conn, useCatalog, useSchema, tableName);
            if (columns.isEmpty() && !metadata.tableExists(conn, useCatalog, useSchema, tableName)) {
                // 零列表是合法形态（PG 允许无列建表），所以「列空」不能直接判定表缺失，
                // 必须再探一次存在性；正常路径不多这次往返，只有本来要返回空的路径付成本。
                throw ApiException.notFound("表不存在或当前账号不可见: " + name);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", "JDBC");
            out.put("product", conn.getMetaData().getDatabaseProductName());
            out.put("table", name);
            out.put("inferred", false);
            out.put("columns", columns);
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw connectFailure(e);
        }
    }

    /** 限定名拆解结果：catalog/schema 为限定前缀（可空），table 为最后一段表名。 */
    record Qualified(String catalog, String schema, String table) {
    }

    /**
     * 把 {@code schema.table}（PostgreSQL）/ {@code 库.表}（MySQL、Doris）/
     * {@code 库.schema.表}（PostgreSQL 三级）拆成精确锁定参数。
     *
     * <p>层级语义与 {@code /tables} 清单返回的 catalog/schema 字段一致：PG 驱动里库进
     * TABLE_CAT、模式进 TABLE_SCHEM；MySQL/Doris 走 MySQL 协议，库在 catalog、
     * schema 恒为 null——前段喂错位置会被驱动忽略或匹配不到。</p>
     */
    static Qualified resolveQualifiedName(String type, String name) {
        if (!name.contains(".")) {
            return new Qualified(null, null, name);
        }
        String[] parts = name.split("\\.", -1);
        if (parts.length == 3 && "POSTGRESQL".equals(type)) {
            for (String p : parts) {
                requireSegment(p, name);
            }
            return new Qualified(parts[0], parts[1], parts[2]);
        }
        if (parts.length != 2) {
            // MySQL/Doris 没有三级名；空段（"a." / ".b" / "a..b"）与四段以上都是坏名字
            throw ApiException.badRequest("表名不合法: " + name);
        }
        requireSegment(parts[0], name);
        requireSegment(parts[1], name);
        if ("POSTGRESQL".equals(type)) {
            return new Qualified(null, parts[0], parts[1]);
        }
        return new Qualified(parts[0], null, parts[1]);
    }

    private static void requireSegment(String part, String raw) {
        if (part.isBlank()) {
            throw ApiException.badRequest("表名不合法: " + raw);
        }
    }

    /** 限定前缀与显式参数二选一；给了两个且不一致就 400，不静默偏向其中一个。 */
    private static String mergeScope(String prefix, String explicit, String label) {
        if (explicit == null) {
            return prefix;
        }
        if (explicit.equals(prefix)) {
            return explicit;
        }
        throw ApiException.badRequest("表名限定前缀与 " + label + " 参数冲突: " + prefix + " vs " + explicit);
    }

    // ------------------------------------------------------------------ 连接参数装配

    /** 已保存的源：按 id 取行（必须属于当前租户，或内置数据源）并解密密码。 */
    public Spec specOfSaved(long id) {
        Map<String, Object> ds = find(id);
        return new Spec(
                String.valueOf(ds.get("ds_type")).toUpperCase(Locale.ROOT),
                ds.get("jdbc_url") == null ? null : String.valueOf(ds.get("jdbc_url")),
                ds.get("username") == null ? null : String.valueOf(ds.get("username")),
                cipher.decrypt(ds.get("password") == null ? "" : String.valueOf(ds.get("password"))),
                parseConfig(ds.get("config") == null ? "{}" : String.valueOf(ds.get("config"))));
    }

    /**
     * 未保存表单：参数原样使用，明文密码只在内存里。
     * 与 {@code DataSourceController.testUnsaved} 同口径的守卫：编辑场景密码留空、且传了 id 时
     * 复用库内密码，但要求 JDBC 地址与已保存的一致——否则等于把库里的密码发给
     * 请求方控制的数据库服务端（恶意 MySQL 可索要明文口令）。
     */
    public Spec specOfAdhoc(AdhocRequest req) {
        String type = normalizeType(req.type());
        String password = req.password() == null ? "" : req.password();
        if (JDBC_TYPES.contains(type) && password.isEmpty() && req.id() != null) {
            Map<String, Object> saved = find(req.id());
            String savedUrl = saved.get("jdbc_url") == null
                    ? "" : String.valueOf(saved.get("jdbc_url")).trim();
            String reqUrl = req.jdbcUrl() == null ? "" : req.jdbcUrl().trim();
            if (!savedUrl.equalsIgnoreCase(reqUrl)) {
                throw ApiException.badRequest("复用已保存的密码时不能修改 JDBC 地址，请重新输入密码");
            }
            password = cipher.decrypt(String.valueOf(saved.get("password") == null
                    ? "" : saved.get("password")));
        }
        return new Spec(type, req.jdbcUrl(), req.username(), password,
                req.config() == null ? Map.of() : req.config());
    }

    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("缺少数据源类型，请先在表单选择类型");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 连接与工具（REQ-DW-15 原实现迁入，行为不变）

    /** 包内可见：单测里覆写以注入 mock 连接，不改变运行行为。 */
    Connection open(Spec spec) throws Exception {
        String url = JdbcUrlGuard.validate(spec.jdbcUrl());
        String driver = JdbcUrlGuard.driverFor(url);
        if (driver != null) {
            Class.forName(driver);
        }
        Properties props = new Properties();
        props.setProperty("user", spec.username() == null ? "" : spec.username());
        props.setProperty("password", spec.password() == null ? "" : spec.password());
        props.setProperty("connectTimeout", "8000");
        props.setProperty("socketTimeout", "15000");
        DriverManager.setLoginTimeout(8);
        return DriverManager.getConnection(url, props);
    }

    /** 与 KafkaPreviewController.bootstrapOf 同口径取 broker 地址（含出网守卫）。 */
    private String bootstrapOfConfig(Map<String, Object> config) {
        Object value = config.get("bootstrap.servers");
        if (value == null) {
            value = config.get("bootstrapServers");
        }
        if (value == null || String.valueOf(value).isBlank()) {
            throw ApiException.badRequest("数据源缺少 bootstrap.servers");
        }
        JdbcUrlGuard.validateBootstrapServers(String.valueOf(value));
        return String.valueOf(value);
    }

    private Map<String, Object> kafkaColumns(String bootstrap, String topic, int sampleLimit) {
        if (!topic.matches("[A-Za-z0-9._-]{1,249}")) {
            throw ApiException.badRequest("topic 名称不合法: " + topic);
        }
        if (!kafka.topicExists(bootstrap, topic)) {
            throw ApiException.notFound("topic 不存在: " + topic);
        }
        int rows = Math.max(5, Math.min(20, sampleLimit));
        List<KafkaPreviewService.Message> messages =
                kafka.preview(bootstrap, topic, rows, 5000, null, "earliest");
        Map<String, Object> inferred = metadata.inferFields(
                messages.stream().map(KafkaPreviewService.Message::value).toList());
        int parsed = (int) inferred.get("parsedCount");
        if (parsed == 0) {
            throw ApiException.badRequest("抽样的 " + messages.size()
                    + " 条消息都不是 JSON 对象，无法推断字段结构；请确认消息格式");
        }
        Map<String, Object> out = new LinkedHashMap<>(inferred);
        out.put("kind", "KAFKA");
        out.put("topic", topic);
        out.put("inferred", true);
        out.put("note", "抽样推断：字段结构来自最近抽样的消息，不代表全量 schema");
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String raw) {
        try {
            return json.readValue(raw, Map.class);
        } catch (Exception e) {
            throw ApiException.badRequest("数据源配置不是合法 JSON");
        }
    }

    static String requireValidName(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest(label + "不能为空");
        }
        // `%` 在 DatabaseMetaData pattern 里等于全表/全字段通配，含 `%` 直接 400，不静默放行；
        // 空白与控制符同样拒绝。`_` 允许（表名常见），结果侧按原名精确过滤。
        String v = raw.trim();
        if (v.length() > 128 || v.contains("%") || v.chars().anyMatch(c -> c < 0x21)) {
            throw ApiException.badRequest(label + "不合法: " + raw);
        }
        return v;
    }

    private static ApiException connectFailure(Exception e) {
        String detail = SourceMetadataService.rootText(e);
        if (detail.length() > 300) {
            detail = detail.substring(0, 300) + "…";
        }
        return ApiException.badRequest("源端连接失败（"
                + SourceMetadataService.classifyConnectionError(e) + "）：" + detail);
    }

    /** 取数据源行（含密文密码）：必须属于当前租户，或内置数据源。 */
    private Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.ds_type, d.jdbc_url, d.username, d.password, d.config_json::text as config
                  from platform.datasource d
                  join platform.project p on p.id = d.project_id
                 where d.id = ?
                   and (p.tenant_id = ?
                        or coalesce(d.config_json ->> 'builtin', 'false') = 'true')
                """, id, TenantScope.current());
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据源不存在: " + id);
        }
        return rows.get(0);
    }
}
