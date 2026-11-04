package com.lab.dataworks.workflow;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 补数据作业的查询与操作入口。提交入口在 {@link WorkflowController}
 * （{@code POST /api/workflows/{id}/backfill}），因为它要复用工作流的租户校验与审计口径。
 */
@RestController
@RequestMapping("/api")
public class BackfillController {

    private final BackfillService backfills;
    private final SessionService sessions;

    public BackfillController(BackfillService backfills, SessionService sessions) {
        this.backfills = backfills;
        this.sessions = sessions;
    }

    public record RetryRequest(String date, String bizDate) {
    }

    /** 进度 + 逐日结果。 */
    @GetMapping("/backfills/{jobId}")
    public Map<String, Object> detail(@PathVariable long jobId) {
        return backfills.detail(jobId);
    }

    /** 只重跑失败的天；传 {@code date} 时只重跑那一天。 */
    @PostMapping("/backfills/{jobId}/retry-failed")
    @RequireAtom("ops:backfill")
    public Map<String, Object> retryFailed(@PathVariable long jobId,
                                           @RequestBody(required = false) RetryRequest req,
                                           HttpServletRequest http) {
        String date = req == null ? null : (req.date() != null ? req.date() : req.bizDate());
        Map<String, Object> job = backfills.retryFailed(jobId, date);
        sessions.audit(user(http), "BACKFILL_RETRY", "b" + jobId,
                "date=" + (date == null ? "ALL_FAILED" : date) + " job=" + jobId);
        return job;
    }

    /** 取消：未开始的日期置 SKIPPED，已在跑的 run 自然结束并回写结果。 */
    @PostMapping("/backfills/{jobId}/cancel")
    @RequireAtom("ops:backfill")
    public Map<String, Object> cancel(@PathVariable long jobId, HttpServletRequest http) {
        Map<String, Object> job = backfills.cancel(jobId);
        sessions.audit(user(http), "BACKFILL_CANCEL", "b" + jobId, "job=" + jobId);
        return job;
    }

    /** 该工作流的补数历史（最近 50 条）。 */
    @GetMapping("/workflows/{workflowId}/backfills")
    public List<Map<String, Object>> history(@PathVariable long workflowId) {
        return backfills.history(workflowId);
    }

    private String user(HttpServletRequest request) {
        Object u = request.getAttribute("username");
        return u == null ? "unknown" : String.valueOf(u);
    }
}
