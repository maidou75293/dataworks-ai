package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * REQ-DW-15：源端结构只读预览。
 *
 * <p>JDBC 侧只用 {@link DatabaseMetaData}（getTables / getColumns），不执行任何业务 SQL、
 * 不写源端；Kafka 侧把抽样消息的 JSON 值合并成字段结构，并显式标注「抽样推断」。
 * 类型推断与连接错误分类是纯函数，便于脱离数据库回归。</p>
 */
@Service
public class SourceMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SourceMetadataService.class);

    /** 表清单返回上限：超过则截断并置 truncated，防止万表库把弹框打挂。 */
    public static final int TABLE_LIMIT = 500;

    /** PostgreSQL / Doris 里不该出现在业务清单里的系统模式。 */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog");

    private final ObjectMapper json;

    public SourceMetadataService(ObjectMapper json) {
        this.json = json;
    }

    // ------------------------------------------------------------------ JDBC 元数据

    /** 读库/表清单（TABLE + VIEW）。JDBC 异常原样上抛，由控制器分类成明确文案。 */
    public List<Map<String, Object>> tables(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next() && out.size() < TABLE_LIMIT) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && (schema.toLowerCase(Locale.ROOT).startsWith("pg_")
                        || SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT)))) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("catalog", rs.getString("TABLE_CAT"));
                item.put("schema", schema);
                item.put("name", rs.getString("TABLE_NAME"));
                item.put("type", rs.getString("TABLE_TYPE"));
                item.put("comment", rs.getString("REMARKS"));
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 读某张表的字段定义：字段名、类型、是否可空、注释。
     *
     * <p>入参 table 作为 DatabaseMetaData 的 pattern 使用，`%` / `_` 是通配符；
     * 这里禁止调用方传 `%`，并在结果侧按原名精确过滤，避免一个名字读出全库字段。</p>
     */
    public List<Map<String, Object>> columns(Connection conn, String catalog, String schema,
                                             String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> primaryKeys = primaryKeyColumns(meta, catalog, schema, table);
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (!table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    // 表名里的 `_` 在 pattern 里是单字通配，可能放宽命中别的表：丢弃
                    continue;
                }
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", name);
                col.put("typeName", rs.getString("TYPE_NAME"));
                col.put("size", rs.getInt("COLUMN_SIZE"));
                col.put("nullable", "YES".equalsIgnoreCase(nullSafe(rs.getString("IS_NULLABLE"))));
                col.put("comment", rs.getString("REMARKS"));
                col.put("primaryKey", primaryKeys.contains(name.toUpperCase(Locale.ROOT)));
                String def = null;
                try {
                    def = rs.getString("COLUMN_DEF");
                } catch (SQLException ignored) {
                    // 个别驱动对 COLUMN_DEF 不支持，不影响主字段
                }
                col.put("defaultValue", def);
                out.add(col);
            }
        }
        return out;
    }

    /**
     * 表存在性探测（DL-F3）：{@code getColumns} 查空后用它区分「表缺失/不可见」与「零列表」。
     *
     * <p>入参 table 同样是 pattern（{@code _} 为单字通配），必须与 {@link #columns} 一致
     * 在结果侧按原名 equalsIgnoreCase 精确过滤，避免 {@code e2e_src} 匹配到
     * {@code e2e_srcx} 之类的邻表造成假「存在」。</p>
     */
    public boolean tableExists(Connection conn, String catalog, String schema, String table)
            throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(catalog, schema, table, new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> primaryKeyColumns(DatabaseMetaData meta, String catalog, String schema,
                                          String table) {
        Set<String> out = new LinkedHashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                out.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
            }
        } catch (SQLException | RuntimeException e) {
            // MySQL/PG 都支持 getPrimaryKeys；驱动异常不该让整次预览失败
            log.debug("read primary keys failed for {}: {}", table, e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ Kafka 抽样推断（纯函数）

    /**
     * 从抽样消息值推断字段结构。
     *
     * <p>规则：只有能解析成 JSON 对象的值参与推断；字段类型取观测到的类型集合并
     * （如 {@code INTEGER|STRING}）；某字段在部分记录缺失、或观测到 null，记为可空。
     * 结果永远带 inferred=true 语义（由调用方标注「抽样推断」），不冒充权威 schema。</p>
     */
    public Map<String, Object> inferFields(List<String> values) {
        int parsed = 0;
        Map<String, LinkedHashSet<String>> types = new LinkedHashMap<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String raw : values) {
            JsonNode node = parseJsonObject(raw);
            if (node == null) {
                continue;
            }
            parsed++;
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                types.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>())
                        .add(typeNameOf(entry.getValue()));
                seen.merge(entry.getKey(), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : types.entrySet()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", e.getKey());
            col.put("typeName", String.join("|", e.getValue()));
            boolean sawNull = e.getValue().contains("NULL");
            col.put("nullable", sawNull || seen.get(e.getKey()) < parsed);
            col.put("seenCount", seen.get(e.getKey()));
            columns.add(col);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sampleCount", values.size());
        out.put("parsedCount", parsed);
        out.put("columns", columns);
        return out;
    }

    private JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String typeNameOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return "NULL";
        }
        if (node.isBoolean()) {
            return "BOOLEAN";
        }
        if (node.isIntegralNumber()) {
            return "INTEGER";
        }
        if (node.isNumber()) {
            return "NUMBER";
        }
        if (node.isArray()) {
            return "ARRAY";
        }
        if (node.isObject()) {
            return "OBJECT";
        }
        return "STRING";
    }

    // ------------------------------------------------------------------ 连接错误分类（纯函数）

    /**
     * 把底层连接异常分类成「网络 / 凭据 / 权限 / 其它」，供接口返回明确文案。
     * 分类靠异常链上的驱动惯用消息，不做枚举白名单——认不出的归「其它」并保留原文。
     */
    public static String classifyConnectionError(Throwable e) {
        String text = scanText(e).toLowerCase(Locale.ROOT);
        if (containsAny(text, "access denied for", "password authentication failed",
                "login failed", "invalid username", "authentication", "auth failed")) {
            return "凭据";
        }
        // MySQL 1142/1044 惯用 "command denied"（单数），别写成 commands 漏判
        if (containsAny(text, "permission denied", "command denied", "commands denied", "no schema",
                "unknown database", "does not exist", "not found")) {
            return "权限";
        }
        if (containsAny(text, "communications link failure", "connect timed out",
                "connection timed out", "connection refused", "unknownhost",
                "no route to host", "network is unreachable", "connection reset",
                "couldn't open connection", "tcpip wait", "unknownhostexception",
                "connectexception", "sockettimeoutexception")) {
            return "网络";
        }
        return "其它";
    }

    /** 分类用的扫描文本 = 异常链消息 + 各级类名（UnknownHostException 的 message 只有主机名）。 */
    static String scanText(Throwable e) {
        StringBuilder sb = new StringBuilder(rootText(e));
        Throwable t = e;
        int depth = 0;
        while (t != null && depth < 6) {
            sb.append(' ').append(t.getClass().getSimpleName());
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 异常链最深处的原文（驱动真实原因常被包了两三层）。 */
    public static String rootText(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int depth = 0;
        while (t != null && depth < 6) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
            depth++;
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? e.getClass().getSimpleName() : text;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
