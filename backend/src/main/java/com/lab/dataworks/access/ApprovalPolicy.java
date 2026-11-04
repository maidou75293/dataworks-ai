package com.lab.dataworks.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lab.dataworks.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TC7-G5：审批链策略（对标 DataWorks 审批中心「自定义审批策略」）。
 *
 * <p>一个租户按数据密级（L1–L4）最多一条策略；策略定义 1–2 级审批节点，
 * 每级 {@code mode}：{@code ANY}＝或签（池内任一人签过即过）、{@code ALL}＝会签
 * （池内全部签完才过，申请人自动排除出应签池）。{@code timeoutDays>0} 时，
 * 停留在某级超过该天数由 {@code AccessEscalationJob} 升级——把 {@code escalateTo}
 * 并入当级可签池并发提醒（不新增状态，级号仍由 PENDING/PENDING_SECOND 推导）。</p>
 *
 * <p><b>快照冻结</b>：提单时把当时的生效策略抄进 {@code access_request.policy_steps}，
 * 之后改策略不回溯在途单——否则会出现「审批人按自己没见过的链批到一半」。</p>
 *
 * <p>本类只做纯计算 + 一段查库取策略；状态落库与审计在
 * {@code AccessController}（与 approvalStep 同一分层口径，单测不依赖 Spring）。</p>
 */
final class ApprovalPolicy {

    static final Set<String> LEVELS = Set.of("L1", "L2", "L3", "L4");
    static final Set<String> MODES = Set.of("ANY", "ALL");
    /** 池上限：防止把整本通讯录塞进一个节点（审批列表渲染与审计 detail 都会撑爆）。 */
    static final int MAX_POOL = 20;
    /** 级数上限 2：中间态枚举只有 PENDING/PENDING_SECOND，放开 3 级要先扩状态机（见卡 scope）。 */
    static final int MAX_STEPS = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一级审批节点：模式 + 审批人池。 */
    record Step(String mode, List<String> approvers) {
    }

    /** 冻结在申请行上的链快照。 */
    record Snapshot(List<Step> steps, int timeoutDays, List<String> escalateTo) {
    }

    /** 推进结果：WAIT＝会签未齐；ADVANCED＝本级完成、进入下一级；COMPLETED＝终审通过。 */
    record Outcome(String kind, int nextStep, List<String> signed) {
    }

    static final String WAIT = "WAIT";
    static final String ADVANCED = "ADVANCED";
    static final String COMPLETED = "COMPLETED";

    private ApprovalPolicy() {
    }

    /** 校验后的策略草稿（落库的三个 jsonb/int 列 + 密级）。 */
    record Draft(String dataLevel, String stepsJson, int timeoutDays, String escalateToJson) {
    }

    /**
     * 校验 PUT 请求并序列化成规范草稿（字段顺序固定，便于 diff/回归断言）。
     * 任何非法输入显式 400（含合法取值清单），不做静默兜底。
     */
    static Draft canonicalize(AccessController.PolicyBody body) {
        List<String> errors = new ArrayList<>();
        String level = body.dataLevel() == null ? "" : body.dataLevel().trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            errors.add("dataLevel 只能是 L1-L4，收到: " + (body.dataLevel() == null ? "（空）" : body.dataLevel()));
        }
        if (body.steps() == null || body.steps().isEmpty()) {
            errors.add("steps 不能为空（1-2 级）");
        } else if (body.steps().size() > MAX_STEPS) {
            errors.add("当前版本审批链最多 " + MAX_STEPS + " 级，收到 " + body.steps().size() + " 级");
        }
        int timeoutDays = body.timeoutDays() == null ? 0 : body.timeoutDays();
        if (timeoutDays < 0 || timeoutDays > 365) {
            errors.add("timeoutDays 必须在 0-365（0=不升级），收到 " + timeoutDays);
        }
        List<String> escalateTo = dedupPool(body.escalateTo(), "escalateTo", errors);
        if (timeoutDays > 0 && escalateTo.isEmpty()) {
            errors.add("timeoutDays>0 必须同时配置 escalateTo（升级给谁），不能留空");
        }
        ArrayNode stepsNode = MAPPER.createArrayNode();
        if (body.steps() != null) {
            for (int i = 0; i < body.steps().size(); i++) {
                AccessController.StepBody raw = body.steps().get(i);
                String mode = raw == null || raw.mode() == null ? ""
                        : raw.mode().trim().toUpperCase(Locale.ROOT);
                if (!MODES.contains(mode)) {
                    errors.add("第 " + (i + 1) + " 级 mode 只能是 ANY(或签)/ALL(会签)，收到: "
                            + (raw == null || raw.mode() == null ? "（空）" : raw.mode()));
                    continue;
                }
                List<String> pool = dedupPool(raw.approvers(), "第 " + (i + 1) + " 级 approvers", errors);
                if (pool.isEmpty()) {
                    errors.add("第 " + (i + 1) + " 级 approvers 不能为空");
                }
                ObjectNode node = stepsNode.addObject();
                node.put("name", raw.name() == null || raw.name().isBlank()
                        ? ("ALL".equals(mode) ? "会签" : "或签") : raw.name().trim());
                node.put("mode", mode);
                ArrayNode users = node.putArray("approvers");
                pool.forEach(users::add);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("审批策略不合法：" + String.join("；", errors));
        }
        ArrayNode esc = MAPPER.createArrayNode();
        escalateTo.forEach(esc::add);
        return new Draft(level, stepsNode.toString(), timeoutDays, esc.toString());
    }

    /** 解析申请行上冻结的快照 JSON；数据坏掉时 fail-closed（403，不能让坏快照走旧宽松路径）。 */
    static Snapshot parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<Step> steps = new ArrayList<>();
            for (JsonNode s : root.path("steps")) {
                String mode = s.path("mode").asText("").toUpperCase(Locale.ROOT);
                if (!MODES.contains(mode)) {
                    throw new IllegalArgumentException("mode=" + mode);
                }
                List<String> pool = new ArrayList<>();
                for (JsonNode u : s.path("approvers")) {
                    String v = u.asText("").trim();
                    if (!v.isEmpty()) {
                        pool.add(v);
                    }
                }
                steps.add(new Step(mode, pool));
            }
            if (steps.isEmpty() || steps.size() > MAX_STEPS) {
                throw new IllegalArgumentException("steps=" + steps.size());
            }
            int timeoutDays = root.path("timeoutDays").asInt(0);
            List<String> escalateTo = new ArrayList<>();
            for (JsonNode u : root.path("escalateTo")) {
                String v = u.asText("").trim();
                if (!v.isEmpty()) {
                    escalateTo.add(v);
                }
            }
            return new Snapshot(List.copyOf(steps), timeoutDays, List.copyOf(escalateTo));
        } catch (Exception e) {
            throw new ApiException(403, "申请单上的审批链快照无法解析，已禁止继续审批（fail-closed）: "
                    + e.getMessage());
        }
    }

    /** 级号由状态推导：PENDING=0，PENDING_SECOND=1（≤2 级不动状态枚举的设计核心）。 */
    static int stepOf(String status) {
        return "PENDING_SECOND".equalsIgnoreCase(status == null ? "" : status.trim()) ? 1 : 0;
    }

    /**
     * 本级「应签池」：策略池 ∪（已升级时的 escalateTo），并排除申请人本人
     * （申请人永远不能进自己的审批池，v460 双人闸门口径在策略模式下同样成立）。
     */
    static List<String> eligibleApprovers(Snapshot snap, int step, boolean escalated, String requester) {
        Set<String> pool = new LinkedHashSet<>(snap.steps().get(step).approvers());
        if (escalated) {
            pool.addAll(snap.escalateTo());
        }
        pool.remove(requester);
        return List.copyOf(pool);
    }

    /** step_signed（jsonb text，形如 ["a","b"]）解析为列表；空/坏值按空处理（只影响展示，签批以追加为准）。 */
    static List<String> signedList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> out = new ArrayList<>();
            for (JsonNode n : MAPPER.readTree(json)) {
                String v = n.asText("").trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            throw ApiException.badRequest("step_signed 无法解析: " + e.getMessage());
        }
    }

    /** 写回 step_signed 用的 JSON 数组字符串。 */
    static String signedJson(List<String> signed) {
        ArrayNode arr = MAPPER.createArrayNode();
        signed.forEach(arr::add);
        return arr.toString();
    }

    /** 本级是否已升级（escalated_step 与当前级号对齐才算；上一级的升级不传染）。 */
    static boolean escalatedAt(Integer escalatedStep, int step) {
        return escalatedStep != null && escalatedStep == step;
    }

    /**
     * 审批推进纯函数（会签并发安全依赖调用方的 jsonb 数组追加 + returning 重判）。
     *
     * @param approve   通过（false＝驳回，任何一级驳回即终态）
     * @param step      当前级号
     * @param signed    当前级已签人（DB 里的 step_signed，不含 actor）
     * @param actor     本次签批人
     * @param escalated 当前级是否已超时升级（可签池并入 escalateTo）
     * @param requester 申请人（从应签池剔除）
     */
    static Outcome advance(Snapshot snap, boolean approve, int step, List<String> signed,
                           String actor, boolean escalated, String requester) {
        if (!approve) {
            return new Outcome("REJECTED", step, signed);
        }
        Step cur = snap.steps().get(step);
        List<String> signedNow = new ArrayList<>(signed);
        if ("ALL".equals(cur.mode())) {
            if (signedNow.contains(actor)) {
                throw ApiException.badRequest("你已在本级会签中签过，不能重复签批");
            }
            signedNow.add(actor);
        }
        boolean done;
        if ("ANY".equals(cur.mode())) {
            done = true;
        } else {
            List<String> eligible = eligibleApprovers(snap, step, escalated, requester);
            // 应签池只剩申请人（或空）：任何有权限的签批人（如平台管理员）签一次即视为齐
            done = eligible.isEmpty() || signedNow.containsAll(eligible);
        }
        if (!done) {
            return new Outcome(WAIT, step, List.copyOf(signedNow));
        }
        if (step + 1 < snap.steps().size()) {
            return new Outcome(ADVANCED, step + 1, List.of());
        }
        return new Outcome(COMPLETED, step, List.copyOf(signedNow));
    }

    /**
     * 提单时解析生效链：本租户启用的策略 → default 租户启用行 → null（旧硬编码）。
     * 返回冻结快照 JSON（含 escalateTo/timeoutDays），无策略返回 null。
     */
    static String snapshotFor(JdbcTemplate jdbc, String tenant, String level) {
        // 整条快照在 SQL 侧拼成单个 jsonb 再取 text：用户名里万一有分隔符也不会拆错
        List<String> rows = jdbc.queryForList("""
                select (jsonb_build_object(
                            'steps', steps,
                            'timeoutDays', coalesce(timeout_days, 0),
                            'escalateTo', coalesce(escalate_to, '[]'::jsonb)))::text
                  from platform.access_approval_policy
                 where data_level = ? and enabled
                   and tenant_id in (?, 'default')
                 order by (tenant_id = ?) desc, id
                 limit 1
                """, String.class, level, tenant, tenant);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> dedupPool(List<String> raw, String field, List<String> errors) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String v : raw) {
                if (v == null || v.isBlank()) {
                    errors.add(field + " 里有空用户名");
                    continue;
                }
                out.add(v.trim());
            }
            if (out.size() > MAX_POOL) {
                errors.add(field + " 超过 " + MAX_POOL + " 人（收到 " + out.size() + "）");
            }
        }
        return List.copyOf(out);
    }
}
