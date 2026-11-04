package com.lab.dataworks.lineage;

import com.lab.dataworks.query.DorisQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doris 计划血缘：用 {@code EXPLAIN <sql>} 拿到真实扫描的表。
 *
 * <p>Doris 的 EXPLAIN 是计划树文本，不提供稳定的列级映射，因此这里只做
 * <b>表级确认</b>：确认到的表把静态解析的边从 {@code inferred} 升为 {@code measured}；
 * 列级边仍由 jsqlparser 推断，保持 inferred。</p>
 *
 * <p>失败（外部项目连不上 / 语法不支持 EXPLAIN / SQL 过长）一律返回空集合，
 * 由调用方降级，不阻断血缘上报。</p>
 */
@Component
public class DorisPlanLineage {

    private static final Logger log = LoggerFactory.getLogger(DorisPlanLineage.class);
    /** Doris 计划里常见两种写法：TABLE: db.tbl / table=db.tbl */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)(?:TABLE\\s*:\\s*|table=)([`\"A-Za-z0-9_.\\-]+)");

    private final DorisQueryService doris;

    public DorisPlanLineage(DorisQueryService doris) {
        this.doris = doris;
    }

    /** 返回计划里出现的表名（小写，含 db 前缀与裸表名两种形式）。 */
    public Set<String> tables(long projectId, String sql) {
        if (sql == null || sql.isBlank()) {
            return Set.of();
        }
        try {
            DorisQueryService.Result result = doris.rawQuery(projectId, "EXPLAIN " + sql, 500);
            Set<String> out = new LinkedHashSet<>();
            for (List<String> row : result.rows()) {
                for (String cell : row) {
                    if (cell == null) {
                        continue;
                    }
                    Matcher m = TABLE_REF.matcher(cell);
                    while (m.find()) {
                        add(out, m.group(1));
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("doris explain lineage failed project={}: {}", projectId, e.getMessage());
            return Set.of();
        }
    }

    private static void add(Set<String> out, String raw) {
        String name = raw.replace("`", "").replace("\"", "").trim()
                .toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return;
        }
        out.add(name);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            out.add(name.substring(dot + 1));
        }
    }
}
