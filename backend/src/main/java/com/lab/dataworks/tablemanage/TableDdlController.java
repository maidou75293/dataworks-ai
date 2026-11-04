package com.lab.dataworks.tablemanage;

import com.lab.dataworks.auth.RequireAtom;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 表编辑入口（P1）：预检、加列/改列/删列/改属性、重命名、清空数据、工单与审批。
 *
 * <p>前端只提交结构化变更；SQL 由 {@link TableDdlService} 生成。权限统一走
 * {@code table:ddl}（本期的验收口径；权限点拆分见设计文档，后续版本再落）。</p>
 */
@RestController
@RequestMapping("/api/tables")
public class TableDdlController {

    private final TableDdlService ddl;

    public TableDdlController(TableDdlService ddl) {
        this.ddl = ddl;
    }

    // ---------------------------------------------------------------- 预检 / 变更

    @PostMapping("/{database}/{table}/alter/precheck")
    @RequireAtom("table:ddl")
    public Map<String, Object> precheck(@PathVariable String database,
                                        @PathVariable String table,
                                        @RequestBody TableDdlService.AlterRequest req) {
        return ddl.precheck(req.projectId(), database, table, req);
    }

    @PostMapping("/{database}/{table}/alter")
    @RequireAtom("table:ddl")
    public Map<String, Object> alter(@PathVariable String database,
                                     @PathVariable String table,
                                     @RequestBody TableDdlService.AlterRequest req,
                                     HttpServletRequest http) {
        return ddl.submitAlter(req.projectId(), database, table, req, http);
    }

    @PostMapping("/{database}/{table}/rename")
    @RequireAtom("table:ddl")
    public Map<String, Object> rename(@PathVariable String database,
                                      @PathVariable String table,
                                      @RequestBody TableDdlService.RenameRequest req,
                                      HttpServletRequest http) {
        return ddl.submitRename(req.projectId(), database, table, req, http);
    }

    @PostMapping("/{database}/{table}/truncate")
    @RequireAtom("table:ddl")
    public Map<String, Object> truncate(@PathVariable String database,
                                        @PathVariable String table,
                                        @RequestBody TableDdlService.TruncateRequest req,
                                        HttpServletRequest http) {
        return ddl.submitTruncate(req.projectId(), database, table, req, http);
    }

    // ---------------------------------------------------------------- 工单

    /**
     * 工单列表（D19）。不带 page/size 返回原数组（兼容既有前端/脚本）；
     * 带分页返回 {@code {items,total,page,size}} 并回 {@code X-Total-Count} 响应头，
     * 非法分页参数 400（page 从 1、size 1–500），不再静默忽略。
     * status 过滤沿用 D29 口径：非法状态 400 + 合法值清单。
     * 写法参照 udf/UdfController.resources（同样的「Map 带 total 才补头」判断）。
     */
    @GetMapping("/ddl-jobs")
    @RequireAtom("table:ddl")
    public Object jobs(@RequestParam(required = false) String database,
                       @RequestParam(required = false) String table,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "100") int limit,
                       @RequestParam(required = false) Integer page,
                       @RequestParam(required = false) Integer size,
                       jakarta.servlet.http.HttpServletResponse response) {
        Object out = ddl.jobs(database, table, status, limit, page, size);
        if (out instanceof Map<?, ?> map && map.get("total") != null) {
            response.setHeader("X-Total-Count", String.valueOf(map.get("total")));
        }
        return out;
    }

    @GetMapping("/ddl-jobs/{id}")
    @RequireAtom("table:ddl")
    public Map<String, Object> job(@PathVariable long id) {
        return ddl.job(id);
    }

    @GetMapping("/ddl-jobs/{id}/events")
    @RequireAtom("table:ddl")
    public List<Map<String, Object>> events(@PathVariable long id) {
        return ddl.events(id);
    }

    @PostMapping("/ddl-jobs/{id}/approve")
    @RequireAtom("table:ddl")
    public Map<String, Object> approve(@PathVariable long id,
                                       @RequestBody(required = false) TableDdlService.JobRequest req,
                                       HttpServletRequest http) {
        return ddl.approve(id, req == null ? null : req.comment(), http);
    }

    @PostMapping("/ddl-jobs/{id}/reject")
    @RequireAtom("table:ddl")
    public Map<String, Object> reject(@PathVariable long id,
                                      @RequestBody TableDdlService.JobRequest req,
                                      HttpServletRequest http) {
        return ddl.reject(id, req == null ? null : req.comment(), http);
    }

    @PostMapping("/ddl-jobs/{id}/cancel")
    @RequireAtom("table:ddl")
    public Map<String, Object> cancel(@PathVariable long id,
                                      @RequestBody(required = false) TableDdlService.JobRequest req,
                                      HttpServletRequest http) {
        return ddl.cancel(id, req == null ? null : req.comment(), http);
    }
}
