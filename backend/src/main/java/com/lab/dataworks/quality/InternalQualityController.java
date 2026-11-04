package com.lab.dataworks.quality;

import com.lab.dataworks.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 集群内部接口：Flow 在开始执行任务前问一句「上游强规则今天过了吗」（TC7-G2）。
 *
 * <p>为什么必须走接口而不是让 Flow 直接查库：反查下游要沿血缘做传递闭包，
 * 逻辑只有一份（{@link QualityBlockService}），两边各写一套迟早漂移。</p>
 */
@RestController
@RequestMapping("/api/internal/quality")
public class InternalQualityController {

    private final JdbcTemplate jdbc;
    private final QualityBlockService blocks;
    private final com.lab.dataworks.auth.InternalTokenService internalTokens;

    public InternalQualityController(JdbcTemplate jdbc, QualityBlockService blocks,
                                     com.lab.dataworks.auth.InternalTokenService internalTokens) {
        this.jdbc = jdbc;
        this.blocks = blocks;
        this.internalTokens = internalTokens;
    }

    public record BlockersRequest(Long workflowId, String env, String bizDate) {
    }

    /**
     * 返回该工作流本次是否被上游强规则挡住。
     *
     * <p>响应示例：{@code {"blocked":true,"blockers":[{"ruleCode":"q_orders",...,"message":"..."}]}}。
     * 前端/运行实例直接展示 message，不再自己拼口径。</p>
     */
    @PostMapping("/blockers")
    public Map<String, Object> blockers(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody BlockersRequest req) {
        if (!internalTokens.configured()) {
            throw new ApiException(503, "内部接口未启用（PLATFORM_INTERNAL_TOKEN 未配置）");
        }
        if (!internalTokens.matches(token)) {
            throw new ApiException(401, "内部凭证无效");
        }
        if (req == null || req.workflowId() == null || req.workflowId() <= 0) {
            throw ApiException.badRequest("workflowId 必填且大于 0");
        }
        String env = req.env() == null || req.env().isBlank()
                ? "DEV" : req.env().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DEV", "PROD").contains(env)) {
            throw ApiException.badRequest("env 只能是 DEV 或 PROD");
        }
        if (req.bizDate() == null || req.bizDate().isBlank()) {
            throw ApiException.badRequest("bizDate 不能为空");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(req.bizDate().trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("bizDate 格式应为 yyyy-MM-dd: " + req.bizDate());
        }
        Integer exists = jdbc.queryForObject(
                "select count(*) from platform.workflow where id = ?", Integer.class, req.workflowId());
        if (exists == null || exists == 0) {
            throw ApiException.notFound("工作流不存在: " + req.workflowId());
        }
        List<QualityBlockService.Blocker> blockers = blocks.forWorkflow(req.workflowId(), env, date);
        List<Map<String, Object>> items = new ArrayList<>(blockers.size());
        for (QualityBlockService.Blocker blocker : blockers) {
            items.add(QualityBlockService.toView(blocker));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blocked", !items.isEmpty());
        out.put("blockers", items);
        return out;
    }
}
