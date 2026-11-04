package com.lab.dataworks.query;

import com.lab.dataworks.auth.RequireAnyAtom;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询限额接口（D4）。目前是租户级；用户级覆盖等有多租户实际诉求再开。
 *
 * <p>权限用 {@code system:manage}：这些数值直接决定别人能跑多大的查询，
 * 不该由普通开发者改。</p>
 *
 * <p>D45：{@code GET} 除了当前值还返回 {@code fields}（字段含义 + 合法范围），
 * 界面直接照这份渲染，避免范围常量在前端再抄一遍抄歪。
 * 越界值不再静默夹到边界上（旧实现 clamp 把 timeoutS=99999 存成 3600，
 * 使用者以为设的是 99999），一律 400 并点名是哪个字段、允许什么区间。</p>
 *
 * <p>REQ-TC9-2：新增 {@code GET /api/query-limits/effective}——租户/项目侧的脱敏只读面。
 * 原先限额只有挂 {@code system:manage} 的管理面可读，DEVELOPER 想知道「超时到底几秒、
 * 行数上限多少、并发令牌几个」只能靠猜，TC-9 因此把「超时保护」判成 BLOCKED。
 * 可读面只回 {@code timeoutS / maxRows / maxConcurrency} 三个数字：不回 SQL、不回连接串
 * 与内部 host，其余限额项（maxMemMb/maxParallel/dailyQueryLimit）一并不吐，
 * 避免把 429 报文内嵌全量 Limits 那种旁路变成正式口径。
 * 每次现取 {@code resolve}，不缓存——「读到的」必须等于「正在生效的」（守卫 R06）。</p>
 */
@RestController
public class QueryLimitsController {

    /**
     * 一个字段的展示口径与合法区间。{@code enforced=false} 表示这个值能存但后端没有消费点，
     * 界面要标「未生效」，别让人以为改了就有用。
     */
    public record LimitField(String key, String label, String unit, int min, int max,
                             String hint, boolean enforced) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", label);
            m.put("unit", unit);
            m.put("min", min);
            m.put("max", max);
            m.put("hint", hint);
            m.put("enforced", enforced);
            return m;
        }
    }

    /** 顺序即界面展示顺序，与 {@link QueryLimitsService.Limits} 的字段一一对应。 */
    static final List<LimitField> FIELDS = List.of(
            new LimitField("maxMemMb", "单查询内存上限", "MB", 128, 65536,
                    "一条查询最多能用多少内存；调小会让大查询先失败。"
                            + "仅外部 Doris（JDBC 直连）下发 exec_mem_limit，内置通道不透传", true),
            new LimitField("timeoutS", "单查询超时", "秒", 1, 3600,
                    "一条查询最多跑多久，超了本次查询被终止：外部 Doris 下发 query_timeout 由 Doris 终止；"
                            + "内置通道到点中断等待并报错（Doris 端由服务端超时兜底清理）", true),
            new LimitField("maxRows", "返回行数上限", "行", 1, 1000000,
                    "一条查询最多返回多少行，超出部分被截断；"
                            + "内置与外部通道都按「本页上限与请求行数取小」生效", true),
            new LimitField("maxParallel", "单查询并行度", "实例", 1, 64,
                    "一条查询占用的并行实例数；仅外部 Doris（JDBC 直连）下发 "
                            + "parallel_fragment_exec_instance_num，内置通道不透传", true),
            new LimitField("maxConcurrency", "租户并发查询数", "条", 1, 200,
                    "同一租户同时在跑的查询数；再多的请求直接拒 429，不会排队", true),
            new LimitField("dailyQueryLimit", "每人每日查询次数", "次", 0, 1000000,
                    "0＝不限。当前后端没有消费点，改了不生效（D45 附带发现，待另立缺陷卡）", false));

    private final QueryLimitsService limits;
    private final TenantGuard guard;

    public QueryLimitsController(QueryLimitsService limits, TenantGuard guard) {
        this.limits = limits;
        this.guard = guard;
    }

    public record LimitsRequest(Integer maxMemMb, Integer timeoutS, Integer maxRows,
                                Integer maxParallel, Integer maxConcurrency,
                                Integer dailyQueryLimit) {
    }

    @GetMapping("/api/query-limits")
    @RequireAtom("system:manage")
    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>(limits.view(TenantScope.current()));
        List<Map<String, Object>> fields = new ArrayList<>();
        for (LimitField f : FIELDS) {
            fields.add(f.toMap());
        }
        out.put("fields", fields);
        out.put("defaults", QueryLimitsService.DEFAULTS);
        return out;
    }

    /**
     * 限额脱敏只读面（REQ-TC9-2）。权限 {@code query:run} 或 {@code project:read} 任一即可，
     * 不得挂 {@code system:manage}——这个面就是给跑查询的人核对「正在生效的值」用的。
     * projectId 只是调用方的项目语义：传了就校验归属（不属于当前租户 404，
     * 与 {@code /api/query/tables} 等按 id 直达路由同一口径）；限额本身是租户级，
     * 取值始终来自当前租户，不带 projectId 回同值不泄露。
     */
    @GetMapping("/api/query-limits/effective")
    @RequireAnyAtom({"query:run", "project:read"})
    public Map<String, Object> effective(@RequestParam(required = false) Long projectId) {
        if (projectId != null) {
            guard.requireProject(projectId);
        }
        QueryLimitsService.Limits l = limits.resolve(TenantScope.current());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timeoutS", l.timeoutS());
        out.put("maxRows", l.maxRows());
        out.put("maxConcurrency", l.maxConcurrency());
        return out;
    }

    @PutMapping("/api/query-limits")
    @RequireAtom("system:manage")
    public Map<String, Object> update(@RequestBody(required = false) LimitsRequest req) {
        QueryLimitsService.Limits current = limits.resolve(TenantScope.current());
        LimitsRequest body = req == null ? new LimitsRequest(null, null, null, null, null, null) : req;
        // null＝不改这一项，沿用当前值；非 null 必须先过范围校验
        QueryLimitsService.Limits value = new QueryLimitsService.Limits(
                pick(body.maxMemMb(), current.maxMemMb(), "maxMemMb"),
                pick(body.timeoutS(), current.timeoutS(), "timeoutS"),
                pick(body.maxRows(), current.maxRows(), "maxRows"),
                pick(body.maxParallel(), current.maxParallel(), "maxParallel"),
                pick(body.maxConcurrency(), current.maxConcurrency(), "maxConcurrency"),
                pick(body.dailyQueryLimit(), current.dailyQueryLimit(), "dailyQueryLimit"));
        return limits.update(TenantScope.current(), value, "admin");
    }

    private static int pick(Integer requested, int current, String key) {
        if (requested == null) {
            return current;
        }
        return requireRange(key, requested);
    }

    /** 越界 → 400 带字段名与区间；只有落在校验范围内才接受。 */
    static int requireRange(String key, int value) {
        for (LimitField f : FIELDS) {
            if (!f.key().equals(key)) {
                continue;
            }
            if (value < f.min() || value > f.max()) {
                throw ApiException.badRequest(f.label() + "（" + key + "）必须在 "
                        + f.min() + "-" + f.max() + " " + f.unit() + "之间，收到 " + value);
            }
            return value;
        }
        throw ApiException.badRequest("未知限额字段: " + key);
    }
}
