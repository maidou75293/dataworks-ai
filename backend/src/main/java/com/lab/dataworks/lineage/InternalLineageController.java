package com.lab.dataworks.lineage;

import com.lab.dataworks.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 集群内部接口：Prefect Flow 在 SQL 任务成功后回报实际执行的语句，
 * 用于运行期血缘（origin=RUNTIME）。AuthInterceptor 对 /api/internal 放行，
 * 这里自行校验 X-Internal-Token。
 */
@RestController
@RequestMapping("/api/internal/lineage")
public class InternalLineageController {

    private final JdbcTemplate jdbc;
    private final LineageService lineage;
    private final com.lab.dataworks.auth.InternalTokenService internalTokens;

    public InternalLineageController(JdbcTemplate jdbc, LineageService lineage,
                                     com.lab.dataworks.auth.InternalTokenService internalTokens) {
        this.jdbc = jdbc;
        this.lineage = lineage;
        this.internalTokens = internalTokens;
    }

    public record RuntimeLineageRequest(Long workflowId, Integer versionNo, String env,
                                        String taskCode, String sql, String runId) {
    }

    @PostMapping("/runtime")
    public Map<String, Object> runtime(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                       @RequestBody RuntimeLineageRequest req) {
        if (!internalTokens.configured()) {
            throw new ApiException(503, "内部接口未启用（PLATFORM_INTERNAL_TOKEN 未配置）");
        }
        if (!internalTokens.matches(token)) {
            throw new ApiException(401, "内部凭证无效");
        }
        if (req == null || req.workflowId() == null || req.versionNo() == null
                || req.versionNo() <= 0 || req.taskCode() == null || req.taskCode().isBlank()) {
            throw ApiException.badRequest("workflowId / versionNo / taskCode 必填且 versionNo > 0");
        }
        if (req.sql() == null || req.sql().isBlank()) {
            throw ApiException.badRequest("sql 不能为空");
        }
        // 解析是 O(SQL 长度) 的，内部接口也必须设上限，避免畸形上报把 CPU 打满
        if (req.sql().length() > 256 * 1024) {
            throw ApiException.badRequest("sql 过大，最多 256KB");
        }
        Integer exists = jdbc.queryForObject(
                "select count(*) from platform.workflow where id = ?", Integer.class, req.workflowId());
        if (exists == null || exists == 0) {
            throw ApiException.notFound("工作流不存在: " + req.workflowId());
        }
        Integer versionExists = jdbc.queryForObject("""
                select count(*) from platform.workflow_version
                where workflow_id = ? and version_no = ?
                """, Integer.class, req.workflowId(), req.versionNo());
        if (versionExists == null || versionExists == 0) {
            throw ApiException.notFound("版本不存在: v" + req.versionNo());
        }
        // 老版本 Flow 不传 env，按 DEV 兜底；重新发布后 Flow 会带上真实环境
        String env = req.env() == null || req.env().isBlank()
                ? "DEV" : req.env().trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("DEV", "PROD").contains(env)) {
            throw ApiException.badRequest("env 只能是 DEV 或 PROD");
        }
        String runId = req.runId() == null ? "" : req.runId().trim();
        if (runId.length() > 64) {
            runId = runId.substring(0, 64);
        }
        int inserted = lineage.recordRuntime(req.workflowId(), req.versionNo(), env,
                req.taskCode(), req.sql(), runId.isBlank() ? null : runId);
        return Map.of("ok", true, "edges", inserted);
    }

    public record FlinkPlanRequest(Long workflowId, Integer versionNo, String env,
                                   String taskCode, String jobId, String runId) {
    }

    /**
     * Flink 作业计划血缘：Flow 提交成功后带 jobId 回报，后端读
     * {@code /jobs/{id}/plan} 提表级 source → sink（measured）。
     */
    @PostMapping("/flink-plan")
    public Map<String, Object> flinkPlan(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody FlinkPlanRequest req) {
        if (!internalTokens.configured()) {
            throw new ApiException(503, "内部接口未启用（PLATFORM_INTERNAL_TOKEN 未配置）");
        }
        if (!internalTokens.matches(token)) {
            throw new ApiException(401, "内部凭证无效");
        }
        if (req == null || req.workflowId() == null || req.versionNo() == null
                || req.versionNo() <= 0 || req.taskCode() == null || req.taskCode().isBlank()
                || req.jobId() == null || req.jobId().isBlank()) {
            throw ApiException.badRequest("workflowId / versionNo / taskCode / jobId 必填且 versionNo > 0");
        }
        Integer exists = jdbc.queryForObject(
                "select count(*) from platform.workflow where id = ?", Integer.class, req.workflowId());
        if (exists == null || exists == 0) {
            throw ApiException.notFound("工作流不存在: " + req.workflowId());
        }
        String env = req.env() == null || req.env().isBlank()
                ? "DEV" : req.env().trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("DEV", "PROD").contains(env)) {
            throw ApiException.badRequest("env 只能是 DEV 或 PROD");
        }
        String runId = req.runId() == null || req.runId().isBlank() ? null
                : req.runId().trim().substring(0, Math.min(64, req.runId().trim().length()));
        int inserted = lineage.recordFlinkPlan(req.workflowId(), req.versionNo(), env,
                req.taskCode(), req.jobId().trim(), runId);
        return Map.of("ok", true, "edges", inserted);
    }

    private static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(
                (a == null ? "" : a).getBytes(StandardCharsets.UTF_8),
                (b == null ? "" : b).getBytes(StandardCharsets.UTF_8));
    }
}
