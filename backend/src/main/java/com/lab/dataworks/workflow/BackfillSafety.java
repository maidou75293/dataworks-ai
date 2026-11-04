package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 补数幂等预检：补数会把同一个业务日期重跑一遍，只有「重跑覆盖而不是追加」的任务才安全。
 *
 * <p>平台不在运行时改写用户 SQL（把 {@code INSERT INTO} 偷偷换成 {@code INSERT OVERWRITE}
 * 会在分区表上误删其它日期），而是在提交补数时做静态检查：</p>
 *
 * <ul>
 *   <li>{@code INSERT OVERWRITE}：覆盖写，安全；</li>
 *   <li>{@code MERGE INTO}：按主键合并，安全；</li>
 *   <li>{@code INSERT INTO}：追加写，必须存在同目标表、带 {@code ${biz_date}} 的
 *       {@code DELETE FROM} 前置清理任务（"补数前删除目标分区"）。</li>
 * </ul>
 *
 * <p>SHELL / PYTHON / QUALITY_GATE 任务无法静态判定，只在结果里列为「未判定」，
 * 不因此阻断补数（否则所有带 Python 节点的工作流都不能补数）。</p>
 *
 * <p>检查对象是<b>已发布版本</b>的模型，不是草稿：补数实际跑的是 deployment 对应的
 * flow 文件。调用方负责传入正确的 model JSON。</p>
 */
public final class BackfillSafety {

    private static final Pattern INSERT_OVERWRITE = Pattern.compile(
            "(?is)\\binsert\\s+overwrite\\s+(?:table\\s+)?([`\"\\w.\\-]+)");
    private static final Pattern INSERT_INTO = Pattern.compile(
            "(?is)\\binsert\\s+into\\s+(?:table\\s+)?([`\"\\w.\\-]+)");
    private static final Pattern MERGE_INTO = Pattern.compile(
            "(?is)\\bmerge\\s+into\\s+(?:table\\s+)?([`\"\\w.\\-]+)");
    private static final Pattern DELETE_FROM = Pattern.compile(
            "(?is)\\bdelete\\s+from\\s+([`\"\\w.\\-]+)");

    private BackfillSafety() {
    }

    /**
     * @param safe          true 表示所有追加写都有覆盖/清理保障
     * @param unsafe        「任务编码: 追加写目标」列表，提交补数时直接展示给用户
     * @param unchecked     无法静态判定的任务编码（只提示，不阻断）
     */
    public record Analysis(boolean safe, List<String> unsafe, List<String> unchecked,
                           List<String> overwritten, List<String> cleaned) {
    }

    public static Analysis analyze(String modelJson, ObjectMapper mapper) {
        List<String> unsafe = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();
        Set<String> overwritten = new LinkedHashSet<>();
        Set<String> merged = new LinkedHashSet<>();
        Set<String> cleaned = new LinkedHashSet<>();
        List<String> appends = new ArrayList<>();   // "taskCode|table"
        JsonNode root;
        try {
            root = mapper.readTree(modelJson == null || modelJson.isBlank() ? "{}" : modelJson);
        } catch (Exception e) {
            // 模型都解析不了时由发布/触发链路报错；补数这里按「无法判定」放行，
            // 避免把一个上游数据问题伪装成补数校验失败。
            return new Analysis(true, List.of(), List.of(), List.of(), List.of());
        }
        for (JsonNode task : root.path("tasks")) {
            String code = task.path("code").asText("");
            String type = task.path("type").asText("SQL").toUpperCase(Locale.ROOT);
            if (!"SQL".equals(type)) {
                if (!code.isBlank()) {
                    unchecked.add(code);
                }
                continue;
            }
            String raw = task.path("script").asText("");
            if (raw.isBlank()) {
                continue;
            }
            String noComments = stripComments(raw);
            String masked = maskLiterals(noComments);
            Set<String> localOverwrite = new LinkedHashSet<>();
            Matcher overwrite = INSERT_OVERWRITE.matcher(masked);
            while (overwrite.find()) {
                localOverwrite.add(normalize(overwrite.group(1)));
            }
            overwritten.addAll(localOverwrite);
            Matcher merge = MERGE_INTO.matcher(masked);
            while (merge.find()) {
                merged.add(normalize(merge.group(1)));
            }
            Matcher insert = INSERT_INTO.matcher(masked);
            while (insert.find()) {
                String table = normalize(insert.group(1));
                if (localOverwrite.contains(table)) {
                    continue;   // INSERT OVERWRITE 已被上面的正则认领
                }
                appends.add(code + "|" + table);
            }
            Matcher delete = DELETE_FROM.matcher(masked);
            boolean dayScoped = noComments.contains("${biz_date}");
            while (delete.find()) {
                if (dayScoped) {
                    cleaned.add(normalize(delete.group(1)));
                }
            }
        }
        for (String item : appends) {
            String[] parts = item.split("\\|", 2);
            String table = parts[1];
            boolean safe = covered(table, overwritten) || covered(table, merged) || covered(table, cleaned);
            if (!safe) {
                unsafe.add(parts[0] + ": " + table);
            }
        }
        return new Analysis(unsafe.isEmpty(), List.copyOf(unsafe), List.copyOf(unchecked),
                List.copyOf(overwritten), List.copyOf(cleaned));
    }

    /** db.t 与 t 视为同一张表；两张都带库名时必须完全一致。 */
    static boolean covered(String target, Set<String> tables) {
        for (String candidate : tables) {
            if (target.equals(candidate)
                    || target.endsWith("." + candidate)
                    || candidate.endsWith("." + target)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String table) {
        String value = table == null ? "" : table.trim().toLowerCase(Locale.ROOT);
        value = value.replace("`", "").replace("\"", "");
        while (value.endsWith(";") || value.endsWith(",") || value.endsWith(")")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 去掉注释（保留字符串里的内容，供 ${biz_date} 判断）。 */
    static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--[^\\n]*", " ")
                .replaceAll("(?m)^\\s*#[^\\n]*", " ");
    }

    /** 把单引号字符串内容替换为空，避免 'insert into' 之类的字面量被当成语句。 */
    static String maskLiterals(String sql) {
        return sql.replaceAll("'(?:[^'\\\\]|\\\\.)*'", "''");
    }
}
