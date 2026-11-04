package com.lab.dataworks.flink;

import com.lab.dataworks.auth.RequireAtom;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Flink 集群概览：流开发页与系统监控共用。 */
@RestController
@RequestMapping("/api/flink/cluster")
public class FlinkClusterController {

    private final FlinkSqlService flink;

    public FlinkClusterController(FlinkSqlService flink) {
        this.flink = flink;
    }

    @GetMapping
    // v449 P2：集群槽位/作业数是平台基础设施画像，之前 query:run 就能看（VIEWER 与
    // 其他租户都能读）。与 /api/system/overview 一起收口到平台级权限点。
    @RequireAtom("system:manage")
    public Map<String, Object> cluster() {
        return flink.cluster();
    }

    /**
     * 流开发页的最小可读面（BUG-TC6-12）：只回答「现在能不能提交」。
     *
     * <p>挂 {@code flink:run} 而不是新造 {@code flink:read}：权限点要先在
     * permission_catalog 注册（新建要点迁移号段），而「能提交流任务」的角色
     * （DEVELOPER / ADMIN）本来就该先知道集群能不能接活；VIEWER 没有 flink:run，
     * 仍然拿不到任何集群信息。平台画像继续只走上面的 system:manage 接口。</p>
     */
    @GetMapping("/status")
    @RequireAtom("flink:run")
    public Map<String, Object> status() {
        return flink.clusterStatus();
    }
}
