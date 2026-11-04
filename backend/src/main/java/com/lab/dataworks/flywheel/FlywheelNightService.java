package com.lab.dataworks.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REQ-NIGHT-LEARN-1 夜间岗位学习的数据面：岗位配置（=1 个标注项目）+ 一夜一岗晨报台账 + badcase TOP 聚合。
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li><b>值校验响亮 400，报当前值</b>——口径同 {@link OrchestratorSettings#write}：窗不是 HH:MM、
 *       准入分不在 0–10、maxRows 不在 1–500、开了关却没配 SQL/列映射，一律当场拒。夜里才发现配错=白跑一夜，
 *       而且晨报会把它记成 CONFIG_INVALID 假异常，追责追到错地方。</li>
 *   <li><b>租户收口</b>——读写全部带 tenant_id 条件（by-id-tenant-audit 口径）；跨租户的同号岗位/同夜报告
 *       直接拒，不覆写别人账。</li>
 *   <li><b>不碰全局闸门设置</b>——「夜间强制 auto」走 run spec（{@code spec.night}），
 *       本类<b>一个设置写调用都没有</b>（判据 M5 逐字钉这点）：否则白天的人工闸会被夜环
 *       悄悄改成 AUTO，那是把质量闸拆了。本类不引用编排设置类，全局闸门口径不受夜环影响。</li>
 * </ol>
 */
@Service
public class FlywheelNightService {

    private static final Logger log = LoggerFactory.getLogger(FlywheelNightService.class);
    private static final Pattern WINDOW = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final int REPORT_DAYS_MAX = 31;
    private static final int BADCASE_LIMIT_MAX = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public FlywheelNightService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 岗位配置 ----------

    private static final String POST_COLUMNS = """
            label_project_id as "labelProjectId", label_project_title as "labelProjectTitle",
            tenant_id as "tenantId", platform_project_id as "platformProjectId", enabled,
            window_start as "windowStart", window_end as "windowEnd",
            eval_min_score::float8 as "evalMinScore", feature_sql as "featureSql",
            q_column as "qColumn", a_column as "aColumn", ref_column as "refColumn",
            max_rows as "maxRows", updated_by as "updatedBy",
            (extract(epoch from updated_at) * 1000)::bigint as "updatedMs"
            """;

    /** 夜环与页面共用：返回本租户全部岗位（含未启用的，环侧按 enabled 自过滤）。 */
    public List<Map<String, Object>> listPosts(String tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select %s from platform.flywheel_night_post
                where tenant_id = ?
                order by label_project_id
                """.formatted(POST_COLUMNS), tenant);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(postView(r));
        }
        return out;
    }

    public Map<String, Object> upsertPost(String tenant, long labelProjectId,
                                          Map<String, Object> body, String operator) {
        if (body == null) {
            throw ApiException.badRequest("请求体不能为空（enabled/windowStart/windowEnd/evalMinScore/featureSql/…）");
        }
        boolean enabled = boolOf(body.get("enabled"), false);
        String windowStart = requireWindow(body.get("windowStart"), "windowStart", "00:00");
        String windowEnd = requireWindow(body.get("windowEnd"), "windowEnd", "06:00");
        double evalMinScore = requireScore(body.get("evalMinScore"));
        int maxRows = requireMaxRows(body.get("maxRows"));
        String featureSql = str(body.get("featureSql"));
        String qColumn = str(body.get("qColumn"));
        String aColumn = str(body.get("aColumn"));
        String refColumn = str(body.get("refColumn"));
        String title = str(body.get("labelProjectTitle"));
        Long platformProjectId = longOf(body.get("platformProjectId"));
        if (enabled) {
            // 开关打开却没得可跑 = 这一夜只能记 CONFIG_INVALID 假异常，当场拒更诚实
            if (featureSql == null || featureSql.isBlank()) {
                throw ApiException.badRequest("开启夜间学习必须给特征数据 SQL（featureSql 收到：" + body.get("featureSql") + "）");
            }
            if (qColumn == null || qColumn.isBlank() || aColumn == null || aColumn.isBlank()) {
                throw ApiException.badRequest("开启夜间学习必须给列映射 qColumn+aColumn（收到 q=" + qColumn + " a=" + aColumn + "）");
            }
        }
        String owner = postTenant(labelProjectId);
        if (owner != null && !owner.equals(tenant)) {
            throw ApiException.badRequest("岗位 labelProjectId=" + labelProjectId + " 已归属其它租户，拒绝跨租户覆写");
        }
        jdbc.update("""
                insert into platform.flywheel_night_post
                    (label_project_id, label_project_title, tenant_id, platform_project_id, enabled,
                     window_start, window_end, eval_min_score, feature_sql, q_column, a_column,
                     ref_column, max_rows, updated_by, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (label_project_id) do update set
                    label_project_title = excluded.label_project_title,
                    platform_project_id = excluded.platform_project_id,
                    enabled = excluded.enabled,
                    window_start = excluded.window_start,
                    window_end = excluded.window_end,
                    eval_min_score = excluded.eval_min_score,
                    feature_sql = excluded.feature_sql,
                    q_column = excluded.q_column,
                    a_column = excluded.a_column,
                    ref_column = excluded.ref_column,
                    max_rows = excluded.max_rows,
                    updated_by = excluded.updated_by,
                    updated_at = now()
                """, labelProjectId, title, tenant, platformProjectId, enabled,
                windowStart, windowEnd, evalMinScore, featureSql, qColumn, aColumn,
                refColumn, maxRows, operator);
        Map<String, Object> row = postRow(labelProjectId, tenant);
        return postView(row == null ? new LinkedHashMap<>() : row);
    }

    private String postTenant(long labelProjectId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select tenant_id from platform.flywheel_night_post where label_project_id = ?", labelProjectId);
        return rows.isEmpty() ? null : str(rows.get(0).get("tenant_id"));
    }

    private Map<String, Object> postRow(long labelProjectId, String tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select %s from platform.flywheel_night_post
                where label_project_id = ? and tenant_id = ?
                """.formatted(POST_COLUMNS), labelProjectId, tenant);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> postView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labelProjectId", r.get("labelProjectId"));
        m.put("labelProjectTitle", r.get("labelProjectTitle"));
        m.put("platformProjectId", r.get("platformProjectId"));
        m.put("enabled", r.get("enabled"));
        m.put("windowStart", r.get("windowStart"));
        m.put("windowEnd", r.get("windowEnd"));
        m.put("evalMinScore", r.get("evalMinScore"));
        m.put("featureSql", r.get("featureSql"));
        m.put("qColumn", r.get("qColumn"));
        m.put("aColumn", r.get("aColumn"));
        m.put("refColumn", r.get("refColumn"));
        m.put("maxRows", r.get("maxRows"));
        m.put("updatedBy", r.get("updatedBy"));
        m.put("updatedMs", r.get("updatedMs"));
        return m;
    }

    // ---------- 晨报 ----------

    private static final String REPORT_COLUMNS = """
            night_date::text as "nightDate", label_project_id as "labelProjectId",
            label_project_title as "labelProjectTitle", tenant_id as "tenantId",
            window_start as "windowStart", window_end as "windowEnd",
            runs, passed, abnormal,
            badcase_top::text as "badcaseTopJson", new_models::text as "newModelsJson",
            abnormal_runs::text as "abnormalRunsJson", detail::text as "detailJson",
            source, (extract(epoch from updated_at) * 1000)::bigint as "updatedMs"
            """;

    /** 引擎重报=覆盖（业务键 night_date+label_project_id 唯一），一夜一岗永远一行。 */
    public Map<String, Object> writeReport(String tenant, Map<String, Object> body) {
        if (body == null) {
            throw ApiException.badRequest("请求体不能为空（nightDate+labelProjectId 必给）");
        }
        String nightDate = requireDate(body.get("nightDate"));
        Long labelProjectId = longOf(body.get("labelProjectId"));
        if (labelProjectId == null) {
            throw ApiException.badRequest("labelProjectId 必须是整数（收到：" + body.get("labelProjectId") + "）");
        }
        List<Object> window = body.get("window") instanceof List<?> w ? new ArrayList<>(w) : List.of();
        String windowStart = window.size() > 0 ? requireWindow(window.get(0), "window[0]", "00:00") : "00:00";
        String windowEnd = window.size() > 1 ? requireWindow(window.get(1), "window[1]", "06:00") : "06:00";
        int runs = requireCount(body.get("runs"), "runs");
        int passed = requireCount(body.get("passed"), "passed");
        int abnormal = requireCount(body.get("abnormal"), "abnormal");
        if (passed > runs) {
            throw ApiException.badRequest("passed 不能大于 runs（收到 passed=" + passed + " runs=" + runs + "）");
        }
        Map<String, Object> existing = reportRow(nightDate, labelProjectId);
        if (existing != null && !tenant.equals(str(existing.get("tenantId")))) {
            throw ApiException.badRequest("晨报 " + nightDate + " 岗位 " + labelProjectId + " 属其它租户，拒绝跨租户覆写");
        }
        List<Object> abnormalRuns = new ArrayList<>();
        for (Object o : body.get("abnormalRuns") instanceof List<?> l ? l : List.of()) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                copy.put("kind", requireAbnormalKind(copy.get("kind")));   // 白名单外的异常=拒，不静默吞
                abnormalRuns.add(copy);
            } else {
                abnormalRuns.add(o);
            }
        }
        jdbc.update("""
                insert into platform.flywheel_night_report
                    (night_date, label_project_id, label_project_title, tenant_id,
                     window_start, window_end, runs, passed, abnormal,
                     badcase_top, new_models, abnormal_runs, detail, source, updated_at)
                values (?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?::json, ?::json, ?::json, ?::json, ?, now())
                on conflict (night_date, label_project_id) do update set
                    label_project_title = excluded.label_project_title,
                    window_start = excluded.window_start,
                    window_end = excluded.window_end,
                    runs = excluded.runs,
                    passed = excluded.passed,
                    abnormal = excluded.abnormal,
                    badcase_top = excluded.badcase_top,
                    new_models = excluded.new_models,
                    abnormal_runs = excluded.abnormal_runs,
                    detail = excluded.detail,
                    source = excluded.source,
                    updated_at = now()
                """, nightDate, labelProjectId, str(body.get("labelProjectTitle")), tenant,
                windowStart, windowEnd, runs, passed, abnormal,
                jsonArray(body.get("badcaseTop")), jsonArray(body.get("newModels")),
                jsonArray(abnormalRuns), jsonObject(body.get("detail")),
                str(body.get("source")));
        Map<String, Object> row = reportRow(nightDate, labelProjectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nightDate", nightDate);
        out.put("labelProjectId", labelProjectId);
        out.put("written", row != null);
        return out;
    }

    /**
     * 主页晨报聚合。date 缺省=本租户最新一夜；days=往回带几夜（默认 1，上限 {@value #REPORT_DAYS_MAX}）。
     * 返回 rows（一岗一行）+ summary（在岗数/圈数/通过/异常），异常数在页面单列展示。
     */
    public Map<String, Object> reports(String tenant, String date, int days) {
        int cap = Math.min(Math.max(1, days), REPORT_DAYS_MAX);
        String nightDate = date == null || date.isBlank() ? latestNightDate(tenant) : requireDate(date);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (nightDate != null) {
            // 取「不晚于 nightDate 的最近 cap 个夜」——按夜数取而不是按天数取：
            // 环停过一晚时按天算会带出空档，页面写成「近 N 夜」才对得上口径。
            List<Map<String, Object>> raw = jdbc.queryForList("""
                    select %s from platform.flywheel_night_report
                    where tenant_id = ? and night_date <= ?::date
                      and night_date in (
                          select distinct night_date from platform.flywheel_night_report
                          where tenant_id = ? and night_date <= ?::date
                          order by night_date desc limit ?)
                    order by night_date desc, abnormal desc, label_project_id
                    """.formatted(REPORT_COLUMNS), tenant, nightDate, tenant, nightDate, cap);
            for (Map<String, Object> r : raw) {
                rows.add(reportView(r));
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        int posts = 0, open = 0, runSum = 0, passSum = 0, abnSum = 0;
        for (Map<String, Object> r : rows) {
            posts++;
            if (abnormalOf(r) > 0) {
                open++;
            }
            runSum += intOf(r.get("runs"));
            passSum += intOf(r.get("passed"));
            abnSum += abnormalOf(r);
        }
        summary.put("posts", posts);
        summary.put("abnormalPosts", open);
        summary.put("runs", runSum);
        summary.put("passed", passSum);
        summary.put("abnormal", abnSum);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nightDate", nightDate);
        out.put("rows", rows);
        out.put("summary", summary);
        out.put("source", "platform.flywheel_night_report");
        return out;
    }

    private String latestNightDate(String tenant) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select max(night_date)::text as d from platform.flywheel_night_report where tenant_id = ?",
                tenant);
        return rows.isEmpty() ? null : str(rows.get(0).get("d"));
    }

    private Map<String, Object> reportRow(String nightDate, long labelProjectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select %s from platform.flywheel_night_report
                where night_date = ?::date and label_project_id = ?
                """.formatted(REPORT_COLUMNS), nightDate, labelProjectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> reportView(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nightDate", r.get("nightDate"));
        m.put("labelProjectId", r.get("labelProjectId"));
        m.put("labelProjectTitle", r.get("labelProjectTitle"));
        m.put("tenantId", r.get("tenantId"));
        m.put("window", List.of(String.valueOf(r.get("windowStart")), String.valueOf(r.get("windowEnd"))));
        m.put("runs", r.get("runs"));
        m.put("passed", r.get("passed"));
        m.put("abnormal", r.get("abnormal"));
        m.put("badcaseTop", parse(r.get("badcaseTopJson"), true));
        m.put("newModels", parse(r.get("newModelsJson"), true));
        m.put("abnormalRuns", parse(r.get("abnormalRunsJson"), true));
        m.put("detail", parse(r.get("detailJson"), false));
        m.put("source", r.get("source"));
        m.put("updatedMs", r.get("updatedMs"));
        return m;
    }

    private static int abnormalOf(Map<String, Object> row) {
        return intOf(row.get("abnormal"));
    }

    // ---------- badcase TOP（晨报第三项数据源） ----------

    /**
     * 按问题文本聚合 badcase_pool 计数。sinceMs 必传——不给就 400：
     * 无界全表扫会被夜环每小时一拍放大成常态慢查询，且「近 N 天 TOP」口径含糊等于没口径。
     */
    public List<Map<String, Object>> badcaseTop(String tenant, Long projectId, Long sinceMs, int limit) {
        if (sinceMs == null || sinceMs <= 0) {
            throw ApiException.badRequest("sinceMs 必传且为正整数毫秒（晨报的 badcase TOP 必须有时间下界，收到：" + sinceMs + "）");
        }
        int cap = Math.min(Math.max(1, limit), BADCASE_LIMIT_MAX);
        StringBuilder sql = new StringBuilder("""
                select question, count(*) as cnt from platform.badcase_pool
                where tenant_id = ? and created_at >= to_timestamp(? / 1000.0)
                  and question is not null
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.add(sinceMs);
        if (projectId != null) {
            sql.append(" and project_id = ?");
            args.add(projectId);
        }
        sql.append(" group by question order by cnt desc, max(id) desc limit ?");
        args.add(cap);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", r.get("question"));
            m.put("count", ((Number) r.get("cnt")).longValue());
            out.add(m);
        }
        return out;
    }

    // ---------- 校验（包级静态：不起 Spring 的用例可直钉，同 normalizeFeedback 口径） ----------

    static String requireWindow(Object raw, String field, String dflt) {
        String s = str(raw);
        if (s == null || s.isBlank()) {
            return dflt;
        }
        s = s.trim();
        if (!WINDOW.matcher(s).matches()) {
            throw ApiException.badRequest(field + " 必须是 HH:MM（收到：" + raw + "）");
        }
        return s;
    }

    static double requireScore(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return 7.0;
        }
        double d;
        try {
            d = Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("evalMinScore 必须是数字（收到：" + raw + "）");
        }
        if (!(d >= 0) || d > 10) {
            throw ApiException.badRequest("evalMinScore 允许 0–10（收到：" + raw + "）");
        }
        return d;
    }

    static int requireMaxRows(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return 200;
        }
        int n;
        try {
            n = Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("maxRows 必须是整数（收到：" + raw + "）");
        }
        if (n < 1 || n > 500) {
            throw ApiException.badRequest("maxRows 允许 1–500（分页契约，收到：" + raw + "）");
        }
        return n;
    }

    static String requireDate(Object raw) {
        String s = str(raw);
        if (s == null || s.isBlank()) {
            throw ApiException.badRequest("nightDate 必给，格式 YYYY-MM-DD（收到：" + raw + "）");
        }
        s = s.trim();
        if (!DATE.matcher(s).matches()) {
            throw ApiException.badRequest("nightDate 格式必须是 YYYY-MM-DD（收到：" + raw + "）");
        }
        return s;
    }

    static int requireCount(Object raw, String field) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return 0;
        }
        int n;
        try {
            n = Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " 必须是非负整数（收到：" + raw + "）");
        }
        if (n < 0) {
            throw ApiException.badRequest(field + " 必须是非负整数（收到：" + raw + "）");
        }
        return n;
    }

    /** 异常 kind 白名单：夜环加新类必须同步这里，否则晨报「异常单列」会静默吞掉没登记的类型。 */
    static String requireAbnormalKind(Object raw) {
        String s = str(raw);
        if (s == null || s.isBlank()) {
            return "UNKNOWN";
        }
        String k = s.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "EVAL_BELOW", "RUN_FAILED", "OVER_WINDOW", "NO_RUN", "CONFIG_INVALID", "API_UNREACHABLE" -> k;
            default -> throw ApiException.badRequest("abnormalRuns.kind 未登记：" + raw
                    + "（允许 EVAL_BELOW/RUN_FAILED/OVER_WINDOW/NO_RUN/CONFIG_INVALID/API_UNREACHABLE）");
        };
    }

    // ---------- 小工具 ----------

    private String jsonArray(Object raw) {
        try {
            return json.writeValueAsString(raw instanceof List<?> l ? l : List.of());
        } catch (Exception e) {
            log.warn("night report array serialize failed: {}", e.toString());
            return "[]";
        }
    }

    private String jsonObject(Object raw) {
        try {
            return json.writeValueAsString(raw instanceof Map<?, ?> m ? m : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }

    /** jsonb 文本列还原成结构；坏 JSON 不炸页面（回空结构，明细以 detail 原文另可查库）。 */
    private Object parse(Object raw, boolean wantList) {
        String s = str(raw);
        if (s == null || s.isBlank()) {
            return wantList ? List.of() : Map.of();
        }
        try {
            Object v = wantList ? json.readValue(s, List.class) : json.readValue(s, Map.class);
            return v == null ? (wantList ? List.of() : Map.of()) : v;
        } catch (Exception e) {
            log.warn("night report json parse failed wantList={}: {}", wantList, e.toString());
            return wantList ? List.of() : Map.of();
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean boolOf(Object v, boolean dflt) {
        if (v == null) {
            return dflt;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return dflt;
        }
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")) {
            return true;
        }
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")) {
            return false;
        }
        throw ApiException.badRequest("enabled 只认真值 true/false（收到：" + v + "）");
    }

    private static Long longOf(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("数字参数不可解析：" + v);
        }
    }

    private static int intOf(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }
}
