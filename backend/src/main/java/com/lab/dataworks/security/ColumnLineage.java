package com.lab.dataworks.security;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TC7-G6：把外层 SELECT 的每个结果列**回溯到源列名**，堵「改名列绕过列级脱敏」。
 *
 * <p>此前 {@link DataGuardService} 只拿结果列名和 {@code managed_column.column_name}
 * 比对，{@code select phone as p} 一类改写直接落空。本类用 JSqlParser 解析查询块，
 * 覆盖这些形态：别名（显式/隐式）、表达式（concat/upper/trim/substr/CAST/|| 等）、
 * 子查询改名、CTE 改名、UNION 按位置合并、派生表枚举列上的 {@code *} 展开。
 * {@code select *} 不展开——执行后结果标签就是物理列名，仍由原名匹配兜住。</p>
 *
 * <p>fail-closed 原则：凡是「这是 SELECT、但我列不出外层结果列的来历」的形态
 * （解析异常、标量子查询、无法识别的 FROM 项、超过递归深度等），
 * {@code parsed=false}，由调用方在 ENFORCE + 列级粒度 + 存在待遮规则时拒绝执行。
 * 判定不了不能当成「不涉及敏感列」放过。</p>
 */
public final class ColumnLineage {

    /** 递归上限：超深嵌套直接按解析失败处理（fail-closed），防止栈爆。 */
    private static final int MAX_DEPTH = 16;
    /** {@code select *} 在 labels/origins 里的占位标签（真实标签要等执行结果元数据）。 */
    static final String STAR = "*";

    /**
     * @param applicable false=不是 SELECT（DML/DDL/元数据语句），不参与回溯也不 fail-closed
     * @param parsed     外层结果列能否被完整枚举；false 时调用方必须按安全策略拒绝
     * @param origins    结果列标签（小写）→ 源列名集合（小写物理列名，不做表限定）
     * @param labels     按 SELECT 列表顺序的结果列标签（小写；{@code *} 为 {@link #STAR}），
     *                   供 UNION 按位置对齐
     */
    public record Trace(boolean applicable, boolean parsed,
                        Map<String, Set<String>> origins, List<String> labels) {

        static final Trace NOT_APPLICABLE = new Trace(false, true, Map.of(), List.of());
        static final Trace FAILED = new Trace(true, false, Map.of(), List.of());

        /**
         * 结果标签命中的源列：先精确匹配，再忽略空白差异——无别名的表达式列，
         * JDBC 返回的标签来自 SQL 原文，可能与解析树 toString 的空格/换行不一致。
         */
        public Set<String> originsOf(String label) {
            if (label == null) {
                return Set.of();
            }
            Set<String> hit = origins.get(label);
            if (hit != null) {
                return hit;
            }
            String compact = compact(label);
            if (compact.isEmpty()) {
                return Set.of();
            }
            for (Map.Entry<String, Set<String>> entry : origins.entrySet()) {
                if (!STAR.equals(entry.getKey()) && compact(entry.getKey()).equals(compact)) {
                    return entry.getValue();
                }
            }
            return Set.of();
        }

        private static String compact(String value) {
            return value.replaceAll("\\s+", "");
        }
    }

    private ColumnLineage() {
    }

    /** 解析 SQL 并回溯外层结果列来源；任何异常/无法识别形态都收敛为 fail-closed。 */
    public static Trace trace(String sql) {
        if (sql == null || sql.isBlank()) {
            return Trace.NOT_APPLICABLE;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(normalizeParams(sql));
            if (!(statement instanceof Select select)) {
                return Trace.NOT_APPLICABLE;
            }
            Map<String, Trace> ctes = new HashMap<>();
            if (select.getWithItemsList() != null) {
                for (WithItem with : select.getWithItemsList()) {
                    String name = with == null ? "" : ident(with.getName()).toLowerCase(Locale.ROOT);
                    if (name.isEmpty()) {
                        return Trace.FAILED;
                    }
                    SubSelect sub = with.getSubSelect();
                    ctes.put(name, sub == null ? Trace.FAILED
                            : traceBody(sub.getSelectBody(), ctes, 1));
                }
            }
            return traceBody(select.getSelectBody(), ctes, 0);
        } catch (Exception e) {
            // JSqlParser 解析不了（Doris 方言 / 非 ASCII 裸别名等）→ 交调用方 fail-closed
            return Trace.FAILED;
        }
    }

    /**
     * 把 BI/数据服务的 {@code :name} 具名参数换成 {@code ?} 再交给 JSqlParser。
     * 只用于解析列来历、不参与执行，字符串内容会跳过、不改写。
     */
    static String normalizeParams(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                out.append(c);
                continue;
            }
            // 与 DataServiceController.rewrite 同一口径：:name 具名参数，:: 是类型转换
            if (c == ':' && i + 1 < sql.length()
                    && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')
                    && (i == 0 || sql.charAt(i - 1) != ':')) {
                int j = i + 2;
                while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j))
                        || sql.charAt(j) == '_' || sql.charAt(j) == '$')) {
                    j++;
                }
                out.append('?');
                i = j - 1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static Trace traceBody(SelectBody body, Map<String, Trace> ctes, int depth) {
        if (depth > MAX_DEPTH || body == null) {
            return Trace.FAILED;
        }
        if (body instanceof PlainSelect plain) {
            return tracePlain(plain, ctes, depth);
        }
        if (body instanceof SetOperationList union) {
            return traceUnion(union, ctes, depth);
        }
        // VALUES / 其它形态：无法枚举列来源，按解析失败处理
        return Trace.FAILED;
    }

    /** UNION/EXCEPT：标签取第一枝，各枝按**位置**把源列并起来（漏遮即泄露）。 */
    private static Trace traceUnion(SetOperationList union, Map<String, Trace> ctes, int depth) {
        List<SelectBody> bodies = union.getSelects();
        if (bodies == null || bodies.isEmpty()) {
            return Trace.FAILED;
        }
        List<Trace> parts = new ArrayList<>();
        for (SelectBody body : bodies) {
            Trace part = traceBody(body, ctes, depth);
            if (!part.parsed()) {
                return Trace.FAILED;
            }
            parts.add(part);
        }
        Trace first = parts.get(0);
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (int pos = 0; pos < first.labels().size(); pos++) {
            String label = first.labels().get(pos);
            Set<String> set = new LinkedHashSet<>();
            for (int b = 0; b < parts.size(); b++) {
                Trace part = parts.get(b);
                if (part.labels().size() != first.labels().size()) {
                    return Trace.FAILED; // 枝间列数不一致：位置无法对齐，拒绝
                }
                String other = part.labels().get(pos);
                if (!label.equals(other) && (STAR.equals(label) || STAR.equals(other))) {
                    // 同一位置一边是具名列、一边是 * 展开：* 枝可能带出别的敏感列，
                    // 标签归因不了 → 整条 fail-closed
                    return Trace.FAILED;
                }
                set.addAll(part.origins().getOrDefault(other, Set.of()));
            }
            merged.computeIfAbsent(label, k -> new LinkedHashSet<>()).addAll(set);
        }
        return new Trace(true, true, merged, first.labels());
    }

    private static Trace tracePlain(PlainSelect plain, Map<String, Trace> ctes, int depth) {
        // 1) 收集 FROM/JOIN 里的来源：别名/名字 → 派生（子查询/CTE）trace；物理表不用登记
        List<FromItem> sources = new ArrayList<>();
        if (plain.getFromItem() != null) {
            sources.add(plain.getFromItem());
        }
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join.getRightItem() != null) {
                    sources.add(join.getRightItem());
                }
            }
        }
        Map<String, Trace> derived = new LinkedHashMap<>();
        for (FromItem item : sources) {
            if (item instanceof Table table) {
                String name = ident(table.getName()).toLowerCase(Locale.ROOT);
                String alias = table.getAlias() != null
                        ? ident(table.getAlias().getName()).toLowerCase(Locale.ROOT) : "";
                Trace cte = ctes.get(name);
                if (cte != null) {
                    if (!cte.parsed()) {
                        return Trace.FAILED;
                    }
                    derived.put(name, cte);
                    if (!alias.isEmpty()) {
                        derived.put(alias, cte);
                    }
                }
            } else if (item instanceof SubSelect sub) {
                String alias = sub.getAlias() != null
                        ? ident(sub.getAlias().getName()).toLowerCase(Locale.ROOT) : "";
                Trace inner = traceBody(sub.getSelectBody(), ctes, depth + 1);
                if (!inner.parsed()) {
                    return Trace.FAILED;
                }
                if (!alias.isEmpty()) {
                    derived.put(alias, inner);
                }
            } else {
                // LATERAL / 表函数 / 括号 FROM 等识别不了的形态 → fail-closed
                return Trace.FAILED;
            }
        }

        // 2) 枚举 SELECT 列表
        Map<String, Set<String>> origins = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        if (plain.getSelectItems() == null) {
            return Trace.FAILED;
        }
        boolean parsed = true;
        for (SelectItem item : plain.getSelectItems()) {
            if (item instanceof AllColumns) {
                Trace single = derived.size() == 1 ? derived.values().iterator().next() : null;
                if (single != null && !single.labels().contains(STAR)) {
                    // select * from (select phone as p ...) x —— * 展开成派生列，必须带来源
                    expandDerived(single, origins, labels);
                } else {
                    labels.add(STAR);
                    origins.put(STAR, Set.of());
                }
            } else if (item instanceof AllTableColumns atc) {
                String qualifier = atc.getTable() != null
                        ? ident(atc.getTable().getName()).toLowerCase(Locale.ROOT) : "";
                Trace inner = qualifier.isEmpty() ? null : derived.get(qualifier);
                if (inner != null && !inner.labels().contains(STAR)) {
                    expandDerived(inner, origins, labels);
                } else {
                    labels.add(STAR);
                    origins.put(STAR, Set.of());
                }
            } else if (item instanceof SelectExpressionItem sei) {
                Expression expression = sei.getExpression();
                if (expression == null) {
                    parsed = false;
                    continue;
                }
                String label = label(sei, expression);
                if (label.isEmpty()) {
                    parsed = false;
                    continue;
                }
                Collector collector = new Collector();
                try {
                    expression.accept(collector);
                } catch (RuntimeException e) {
                    parsed = false;
                    continue;
                }
                if (collector.untraceable) {
                    parsed = false; // 标量子查询/IN 子查询里的列不回溯，整条拒绝
                    continue;
                }
                Set<String> set = origins.computeIfAbsent(label, k -> new LinkedHashSet<>());
                for (Column column : collector.columns) {
                    resolveColumn(column, derived, set);
                }
                if (!labels.contains(label)) {
                    labels.add(label);
                }
            } else {
                parsed = false;
            }
        }
        return new Trace(true, parsed, origins, labels);
    }

    private static void expandDerived(Trace inner, Map<String, Set<String>> origins,
                                      List<String> labels) {
        for (String label : inner.labels()) {
            if (!origins.containsKey(label)) {
                origins.put(label, new LinkedHashSet<>(inner.origins().getOrDefault(label, Set.of())));
                labels.add(label);
            }
        }
    }

    /** 列引用 → 源物理列名：命中派生别名/无限定但派生里有此标签时递归展开（内部已递归到底）。 */
    private static void resolveColumn(Column column, Map<String, Trace> derived, Set<String> out) {
        String name = columnName(column);
        if (name.isEmpty()) {
            return;
        }
        String qualifier = column.getTable() != null
                ? ident(column.getTable().getName()).toLowerCase(Locale.ROOT) : "";
        if (!qualifier.isEmpty()) {
            Trace inner = derived.get(qualifier);
            if (inner != null && inner.parsed() && inner.origins().containsKey(name)) {
                out.addAll(inner.origins().get(name));
                return;
            }
        } else {
            // 无限定列：先按标签在各派生源里找（select p from (select phone as p..) x）
            boolean found = false;
            for (Trace inner : derived.values()) {
                if (inner.parsed() && inner.origins().containsKey(name)) {
                    out.addAll(inner.origins().get(name));
                    found = true;
                }
            }
            if (found) {
                // 派生标签命中：别名本身也可能是另一张物理表的真实列，一并保留兜底
                out.add(name);
                return;
            }
        }
        // 物理表（含无限定的列）：列名本身就是物理列名；原名匹配仍可兜底
        out.add(name);
    }

    /** 结果列标签：显式/隐式别名优先，裸列取列名，表达式取表达式文本（与 JDBC 标签同源）。 */
    private static String label(SelectExpressionItem item, Expression expression) {
        Alias alias = item.getAlias();
        if (alias != null && alias.getName() != null && !alias.getName().isBlank()) {
            return ident(alias.getName()).toLowerCase(Locale.ROOT);
        }
        if (expression instanceof Column column) {
            String name = columnName(column);
            if (!name.isEmpty()) {
                return name;
            }
        }
        return expression.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String columnName(Column column) {
        String raw = column.getColumnName();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return ident(raw).toLowerCase(Locale.ROOT);
    }

    /** 去掉反引号/双引号/方括号；内部点号（t.col）取最后一段。 */
    static String ident(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '`' && last == '`') || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        if (value.contains(".")) {
            value = value.substring(value.lastIndexOf('.') + 1);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** 只收集表达式里的列引用；遇到子查询表达式则标记不可回溯（fail-closed）。 */
    private static final class Collector extends ExpressionVisitorAdapter {
        private final List<Column> columns = new ArrayList<>();
        private boolean untraceable = false;

        @Override
        public void visit(Column column) {
            columns.add(column);
        }

        @Override
        public void visit(SubSelect subSelect) {
            untraceable = true;
        }
    }
}
