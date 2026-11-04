package com.lab.dataworks.lineage;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 血缘解析（表级 + 简单列级）。
 *
 * <p>用 JSqlParser 解析，能正确处理 CTE、子查询、JOIN、UNION：
 * <ul>
 *   <li>INSERT INTO ... SELECT / CREATE TABLE ... AS SELECT：产出 source → target；</li>
 *   <li>INSERT 带显式列清单且 SELECT 项是普通列时，额外产出列级映射；</li>
 *   <li>UPDATE / DELETE：目标表是写入表，来源是其余表；</li>
 *   <li>纯 SELECT：source → 空目标（只读）。</li>
 * </ul>
 * 解析失败（方言/动态 SQL）时退回正则表级识别，宁可少记不可乱记。</p>
 */
public final class SqlLineageParser {

    private static final Logger log = LoggerFactory.getLogger(SqlLineageParser.class);

    /**
     * edgeType: TABLE 或 COLUMN；列级边同时也会有一条对应的表级边。
     *
     * <p>V103 起同时拆出 catalog / db：节点 ID 统一 {@code catalog.db.table}。
     * {@code sourceTable} / {@code targetTable} 保留 SQL 里的原始写法（含点分段），
     * 供旧接口与测试按原样匹配；新字段用于图上的统一节点标识。</p>
     *
     * <p>confidence：{@code measured} = 引擎计划确认；{@code inferred} = 静态解析推断。</p>
     */
    public record Edge(String sourceCatalog, String sourceDb, String sourceTable,
                       String sourceColumn, String targetCatalog, String targetDb,
                       String targetTable, String targetColumn, String edgeType,
                       String confidence) {

        static Edge table(String source, String target) {
            return table(source, target, "inferred");
        }

        static Edge table(String source, String target, String confidence) {
            return new Edge(catalogOf(source), dbOf(source), source, "",
                    catalogOf(target), dbOf(target), target, "", "TABLE",
                    confidence == null ? "inferred" : confidence);
        }

        static Edge column(String sourceTable, String targetTable,
                           String sourceColumn, String targetColumn) {
            return new Edge(catalogOf(sourceTable), dbOf(sourceTable), sourceTable, sourceColumn,
                    catalogOf(targetTable), dbOf(targetTable), targetTable, targetColumn,
                    "COLUMN", "inferred");
        }

        /** 只改置信度（EXPLAIN 确认后把 inferred 提升为 measured）。 */
        public Edge withConfidence(String value) {
            return new Edge(sourceCatalog, sourceDb, sourceTable, sourceColumn,
                    targetCatalog, targetDb, targetTable, targetColumn, edgeType,
                    value == null ? confidence : value);
        }

        /** 统一节点 ID：catalog.db.table（没有库时中间段为空）。 */
        public String sourceNode() {
            return node(sourceCatalog, sourceDb, sourceTable);
        }

        public String targetNode() {
            return node(targetCatalog, targetDb, targetTable);
        }

        static String node(String catalog, String db, String table) {
            if (table == null || table.isBlank()) {
                return "";
            }
            String cat = catalog == null || catalog.isBlank() ? "doris" : catalog;
            String[] parts = parts(table);
            // 已是三段式：原样返回，避免 doris.db.db.table
            if (parts.length >= 3) {
                return table.trim();
            }
            if (parts.length == 2) {
                return cat + "." + table.trim();
            }
            return cat + "." + (db == null ? "" : db) + "." + table.trim();
        }
    }

    /** 去掉反引号/双引号后按点分段；空名字返回空数组。 */
    static String[] parts(String name) {
        if (name == null) {
            return new String[0];
        }
        String clean = name.replace("`", "").replace("\"", "").trim();
        return clean.isEmpty() ? new String[0] : clean.split("\\.");
    }

    /** catalog：三段取第一段，否则默认 doris（平台 Doris 是默认执行引擎）。 */
    static String catalogOf(String name) {
        String[] parts = parts(name);
        return parts.length >= 3 ? parts[0] : "doris";
    }

    /** db：三段取第二段，两段取第一段，单段为空。 */
    static String dbOf(String name) {
        String[] parts = parts(name);
        if (parts.length >= 3) {
            return parts[1];
        }
        return parts.length == 2 ? parts[0] : "";
    }

    // 兜底：JSqlParser 解析不了时仍能识别最常见的 INSERT / CTAS / FROM
    private static final Pattern FALLBACK_INSERT = Pattern.compile(
            "(?is)\\binsert\\s+(?:overwrite\\s+)?(?:into\\s+)?(?:table\\s+)?([`\"\\w.\\-]+)");
    private static final Pattern FALLBACK_CTAS = Pattern.compile(
            "(?is)\\bcreate\\s+(?:or\\s+replace\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?([`\"\\w.\\-]+)\\s+as\\s+select");
    private static final Pattern FALLBACK_FROM = Pattern.compile(
            "(?is)\\b(?:from|join)\\s+([`\"\\w.\\-]+)");
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|--[^\\n]*");

    public List<Edge> parse(String rawSql) {
        Set<Edge> edges = new LinkedHashSet<>();
        if (rawSql == null || rawSql.isBlank()) {
            return List.of();
        }
        // 先用原始 SQL 解析：COMMENT 正则会把字符串里的 '--'（如 URL）也当注释删掉，
        // 只有解析失败时才做去注释重试。
        String cleaned = COMMENT.matcher(rawSql).replaceAll(" ");
        // 发布期 SQL 来自用户模型，同样不能无上限地交给解析器
        if (rawSql.length() > 256 * 1024) {
            log.warn("sql too large ({} chars), fallback to regex", rawSql.length());
            edges.addAll(fallback(cleaned));
            return List.copyOf(edges);
        }
        try {
            collectAll(rawSql, edges);
        } catch (Exception | StackOverflowError first) {
            // 深层嵌套（几百层子查询）会让 JSqlParser 递归爆栈；这是 Error 不是
            // Exception，必须显式接住，否则接口 500、运行期回调白白重试三次。
            // 爆栈说明是嵌套深度问题，去注释重试还会再爆一次，直接走正则兜底。
            if (first instanceof StackOverflowError) {
                log.warn("jsqlparser stack overflow (SQL nesting too deep), fallback to regex");
                edges.addAll(fallback(cleaned));
            } else {
                try {
                    collectAll(cleaned, edges);
                } catch (Exception | StackOverflowError e) {
                    log.warn("jsqlparser failed, fallback to regex: {}",
                            e instanceof StackOverflowError ? "SQL nesting too deep" : e.getMessage());
                    edges.addAll(fallback(cleaned));
                }
            }
        }
        if (edges.isEmpty()) {
            edges.addAll(fallback(COMMENT.matcher(rawSql).replaceAll(" ")));
        }
        return new ArrayList<>(edges);
    }

    private void collectAll(String sql, Set<Edge> out) throws Exception {
        Statements statements = CCJSqlParserUtil.parseStatements(sql);
        if (statements != null) {
            for (Statement statement : statements.getStatements()) {
                collect(statement, out);
            }
        }
    }

    private void collect(Statement statement, Set<Edge> out) {
        TablesNamesFinder finder = new TablesNamesFinder();
        List<String> all = new ArrayList<>();
        // INSERT/CTAS 只从 SELECT 侧取来源：findTableList(statement) 会把写入目标也算进来，
        // 导致每条 INSERT 都多出一条自己指向自己的假边。
        if (statement instanceof Insert insert) {
            if (insert.getSelect() != null) {
                addNames(finder.getTableList(insert.getSelect()), all);
            }
        } else if (statement instanceof CreateTable create && create.getSelect() != null) {
            addNames(finder.getTableList(create.getSelect()), all);
        } else {
            addNames(finder.getTableList(statement), all);
        }
        Map<String, String> cteTables = cteTables(statement);
        // TablesNamesFinder 不一定深入 WITH 子句，这里显式补上 CTE 内部的真实表
        for (WithItem item : withItems(statement)) {
            addNames(withItemTables(item), all);
        }
        Set<String> sources = new LinkedHashSet<>(all);
        sources.removeAll(cteTables.keySet());

        if (statement instanceof Insert insert) {
            String target = normalize(insert.getTable() == null
                    ? null : insert.getTable().getFullyQualifiedName());
            if (target == null) {
                return;
            }
            // 不剔除 target：INSERT INTO t SELECT ... FROM t 的自读自写要保留
            addTableEdges(sources, target, out);
            addInsertColumns(insert, target, sources, cteTables, out);
        } else if (statement instanceof CreateTable create && create.getSelect() != null) {
            String target = normalize(create.getTable() == null
                    ? null : create.getTable().getFullyQualifiedName());
            if (target == null) {
                return;
            }
            addTableEdges(sources, target, out);
            addCreateColumns(create, target, sources, cteTables, out);
        } else if (statement instanceof Update update) {
            String target = normalize(update.getTable() == null
                    ? null : update.getTable().getFullyQualifiedName());
            if (target != null) {
                sources.remove(target);
                addTableEdges(sources, target, out);
            }
        } else if (statement instanceof Delete delete) {
            String target = normalize(delete.getTable() == null
                    ? null : delete.getTable().getFullyQualifiedName());
            if (target != null) {
                sources.remove(target);
                addTableEdges(sources, target, out);
            }
        } else if (statement instanceof Select) {
            // 只有查询才是「只读血缘」；CREATE/ DROP / TRUNCATE / ALTER 等 DDL
            // 不产生读写关系，否则会把建表当成一次读操作（假边）。
            addReadOnlyEdges(sources, out);
        }
    }

    private void addNames(List<String> raw, List<String> out) {
        for (String table : raw) {
            String name = normalize(table);
            if (name != null) {
                out.add(name);
            }
        }
    }

    private void addTableEdges(Set<String> sources, String target, Set<Edge> out) {
        if (sources.isEmpty()) {
            out.add(Edge.table("", target));
            return;
        }
        for (String source : sources) {
            out.add(Edge.table(source, target));
        }
    }

    private void addReadOnlyEdges(Set<String> sources, Set<Edge> out) {
        for (String source : sources) {
            out.add(Edge.table(source, ""));
        }
    }

    /** INSERT INTO t(c1,c2) SELECT a,b FROM s —— 只处理 SELECT 项是普通列的情况。 */
    private void addInsertColumns(Insert insert, String target, Set<String> sources,
                                  Map<String, String> cteTables, Set<Edge> out) {
        if (insert.getColumns() == null || insert.getColumns().isEmpty() || insert.getSelect() == null) {
            return;
        }
        SelectBody body = insert.getSelect().getSelectBody();
        if (!(body instanceof PlainSelect plain)) {
            return;
        }
        List<String> targets = new ArrayList<>();
        for (Column column : insert.getColumns()) {
            String name = normalizeColumn(column.getColumnName());
            if (name == null) {
                return;
            }
            targets.add(name);
        }
        Map<String, String> aliases = aliasMap(plain);
        List<SelectItem> items = plain.getSelectItems();
        for (int i = 0; i < Math.min(items.size(), targets.size()); i++) {
            if (!(items.get(i) instanceof SelectExpressionItem item)
                    || !(item.getExpression() instanceof Column column)) {
                continue;
            }
            String sourceTable = resolveTable(column, aliases, sources, cteTables);
            String sourceColumn = normalizeColumn(column.getColumnName());
            if (sourceTable == null || sourceColumn == null) {
                continue;
            }
            out.add(Edge.column(sourceTable, target, sourceColumn, targets.get(i)));
        }
    }

    /** CREATE TABLE t AS SELECT a AS c FROM s —— 以 SELECT 别名作为目标列。 */
    private void addCreateColumns(CreateTable create, String target, Set<String> sources,
                                  Map<String, String> cteTables, Set<Edge> out) {
        SelectBody body = create.getSelect().getSelectBody();
        if (!(body instanceof PlainSelect plain)) {
            return;
        }
        Map<String, String> aliases = aliasMap(plain);
        for (SelectItem raw : plain.getSelectItems()) {
            if (!(raw instanceof SelectExpressionItem item)
                    || !(item.getExpression() instanceof Column column)) {
                continue;
            }
            String targetColumn = item.getAlias() == null ? null
                    : normalizeColumn(item.getAlias().getName());
            String sourceTable = resolveTable(column, aliases, sources, cteTables);
            String sourceColumn = normalizeColumn(column.getColumnName());
            if (targetColumn == null || sourceTable == null || sourceColumn == null) {
                continue;
            }
            out.add(Edge.column(sourceTable, target, sourceColumn, targetColumn));
        }
    }

    private Map<String, String> aliasMap(PlainSelect plain) {
        Map<String, String> aliases = new LinkedHashMap<>();
        collectAlias(plain.getFromItem(), aliases);
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                collectAlias(join.getRightItem(), aliases);
            }
        }
        return aliases;
    }

    private void collectAlias(FromItem item, Map<String, String> aliases) {
        if (item instanceof Table table) {
            String real = normalize(table.getFullyQualifiedName());
            if (real == null) {
                return;
            }
            aliases.put(real, real);
            aliases.put(normalize(table.getName()), real);
            if (table.getAlias() != null) {
                String alias = normalize(table.getAlias().getName());
                if (alias != null) {
                    aliases.put(alias, real);
                }
            }
        }
    }

    private String resolveTable(Column column, Map<String, String> aliases, Set<String> sources,
                                Map<String, String> cteTables) {
        String table;
        if (column.getTable() != null) {
            String alias = normalize(column.getTable().getName());
            table = alias == null ? null : aliases.get(alias);
        } else {
            table = sources.size() == 1 ? sources.iterator().next() : null;
        }
        // 多层 CTE（b 引用 a，a 引用真实表）要一路追到真实表；
        // 任何一层是多表 CTE 或出现环，就无法可靠归属列级血缘。
        Set<String> seen = new LinkedHashSet<>();
        while (table != null && cteTables.containsKey(table)) {
            if (!seen.add(table)) {
                return null;
            }
            table = cteTables.get(table);
        }
        return table;
    }

    private List<WithItem> withItems(Statement statement) {
        List<WithItem> items = new ArrayList<>();
        if (statement instanceof Select select && select.getWithItemsList() != null) {
            items.addAll(select.getWithItemsList());
        } else if (statement instanceof Insert insert) {
            if (insert.getWithItemsList() != null) {
                items.addAll(insert.getWithItemsList());
            }
            if (insert.getSelect() != null && insert.getSelect().getWithItemsList() != null) {
                items.addAll(insert.getSelect().getWithItemsList());
            }
        } else if (statement instanceof CreateTable create && create.getSelect() != null
                && create.getSelect().getWithItemsList() != null) {
            items.addAll(create.getSelect().getWithItemsList());
        } else if (statement instanceof Update update && update.getWithItemsList() != null) {
            items.addAll(update.getWithItemsList());
        } else if (statement instanceof Delete delete && delete.getWithItemsList() != null) {
            items.addAll(delete.getWithItemsList());
        }
        return items;
    }

    /**
     * CTE 内部真实表。不能直接把 SubSelect 交给 TablesNamesFinder：
     * JSqlParser 4.4 会把表别名（u、r）也算成表。重新按 SELECT 语句解析即可。
     */
    private List<String> withItemTables(WithItem item) {
        if (item.getSubSelect() == null || item.getSubSelect().getSelectBody() == null) {
            return List.of();
        }
        try {
            Statement inner = CCJSqlParserUtil.parse(item.getSubSelect().getSelectBody().toString());
            return new TablesNamesFinder().getTableList(inner);
        } catch (Exception e) {
            log.warn("cte parse failed: {}", e.getMessage());
            return new TablesNamesFinder().getTableList(item.getSubSelect());
        }
    }

    /**
     * CTE 名 → 唯一来源表。一个 CTE 里 JOIN 多张表时映射为 null，
     * 这时列级血缘无法可靠归属，只保留表级边。
     */
    private Map<String, String> cteTables(Statement statement) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (WithItem item : withItems(statement)) {
            String name = normalize(item.getName());
            if (name == null || item.getSubSelect() == null) {
                continue;
            }
            List<String> inner = new ArrayList<>();
            for (String table : withItemTables(item)) {
                String normalized = normalize(table);
                if (normalized != null) {
                    inner.add(normalized);
                }
            }
            mapping.put(name, inner.size() == 1 ? inner.get(0) : null);
        }
        return mapping;
    }

    /**
     * 兜底解析：**按语句绑定**源与目标。
     *
     * <p>外部审计发现旧实现把全文的 targets 和 sources 做笛卡尔积：
     * 两个 INSERT（s1→t1、s2→t2）会多出 s2→t1、s1→t2 两条假边。这里先按分号切语句，
     * 每条语句内部再配对，跨语句不混。</p>
     */
    private Set<Edge> fallback(String sql) {
        Set<Edge> edges = new LinkedHashSet<>();
        for (String statement : splitStatements(sql)) {
            String target = null;
            Matcher ins = FALLBACK_INSERT.matcher(statement);
            if (ins.find()) {
                target = normalize(ins.group(1));
            }
            if (target == null) {
                Matcher ctas = FALLBACK_CTAS.matcher(statement);
                if (ctas.find()) {
                    target = normalize(ctas.group(1));
                }
            }
            Set<String> sources = new LinkedHashSet<>();
            Matcher from = FALLBACK_FROM.matcher(statement);
            while (from.find()) {
                String source = normalize(from.group(1));
                if (source != null) {
                    sources.add(source);
                }
            }
            if (target != null) {
                sources.remove(target);
            }
            if (target == null) {
                for (String source : sources) {
                    edges.add(Edge.table(source, ""));
                }
            } else if (sources.isEmpty()) {
                edges.add(Edge.table("", target));
            } else {
                for (String source : sources) {
                    edges.add(Edge.table(source, target));
                }
            }
        }
        return edges;
    }

    /** 按分号切语句，跳过引号与注释里的分号。 */
    private List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                current.append(c);
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    current.append("*/");
                    i++;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                current.append(c);
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                current.append(c);
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ';') {
                if (!current.toString().isBlank()) {
                    out.add(current.toString());
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString());
        }
        return out;
    }

    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replace("`", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[;,)]+$", "");
        if (value.isEmpty() || value.contains("${") || value.startsWith("(")) {
            return null;
        }
        if (Set.of("select", "values", "dual", "table", "unnest", "into", "from", "join",
                "where", "on", "using", "as", "set", "insert", "overwrite", "update", "delete")
                .contains(value)) {
            return null;
        }
        if (value.contains("$") || value.contains("{")) {
            return null;
        }
        return value;
    }

    private static String normalizeColumn(String raw) {
        String value = normalize(raw);
        return value == null ? null : value.replaceAll("\\s+", "");
    }
}
