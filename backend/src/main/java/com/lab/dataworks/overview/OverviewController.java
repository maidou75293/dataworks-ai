package com.lab.dataworks.overview;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.TenantGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首页系统概览：平台资产与运行情况的实时计数。
 *
 * <p>v467 G-P1-7：所有计数按当前租户过滤，只有**平台管理员**（默认租户 + ADMIN）
 * 看全量。之前这里没有任何租户条件，tenant_b 的普通用户能看到平台全部项目/工作流/
 * 告警数量，属于信息泄露，也是外部审计点名的一条。</p>
 */
@RestController
@RequestMapping("/api")
public class OverviewController {

    private final JdbcTemplate jdbc;

    public OverviewController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        boolean platformAdmin = TenantGuard.platform()
                && "ADMIN".equalsIgnoreCase(String.valueOf(request.getAttribute("role")));
        String tenant = TenantScope.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("env", "DEV");
        out.put("version", "0.1.0");
        out.put("tenant", tenant);
        out.put("scope", platformAdmin ? "PLATFORM" : "TENANT");
        out.put("projects", tenantCount("""
                select count(*) from platform.project
                where (? or tenant_id = ?)
                """, platformAdmin, tenant));
        out.put("workflows", tenantCount("""
                select count(*) from platform.workflow
                where (? or tenant_id = ?)
                """, platformAdmin, tenant));
        out.put("dataSources", tenantCount("""
                select count(*) from platform.datasource
                where (? or tenant_id = ?)
                """, platformAdmin, tenant));
        out.put("publishedServices", tenantCount("""
                select count(*) from platform.data_service
                where status = 'PUBLISHED' and (? or tenant_id = ?)
                """, platformAdmin, tenant));
        out.put("qualityRules", tenantCount("""
                select count(*) from platform.quality_rule
                where (? or tenant_id = ?)
                """, platformAdmin, tenant));
        // 告警表本身没有 tenant_id，归属走 project_id → project.tenant_id；
        // project_id 为空的系统级告警只对平台管理员可见。
        out.put("openAlerts", tenantCount("""
                select count(*) from platform.alert_event ae
                where ae.status = 'OPEN'
                  and (? or exists (select 1 from platform.project p
                                     where p.id = ae.project_id and p.tenant_id = ?))
                """, platformAdmin, tenant));
        out.put("runningInstances", tenantCount("""
                select count(*) from platform.wf_instance
                where state in ('queued', 'running') and (? or tenant_id = ?)
                """, platformAdmin, tenant));
        // 数据库时区是 UTC，而业务口径是北京时间；“今日”必须按 Asia/Shanghai 切日
        out.put("todayInstances", tenantCount("""
                select count(*) from platform.wf_instance
                where created_at >= (date_trunc('day', now() at time zone 'Asia/Shanghai')
                                     at time zone 'Asia/Shanghai')
                  and (? or tenant_id = ?)
                """, platformAdmin, tenant));
        out.put("lineageEdges", tenantCount("""
                select count(*) from platform.lineage_edge
                where (? or tenant_id = ?)
                """, platformAdmin, tenant));
        out.put("syncTasks", tenantCount("""
                select count(*) from platform.sync_task
                where (? or tenant_id = ?)
                """, platformAdmin, tenant));
        return out;
    }

    private long tenantCount(String sql, boolean platformAdmin, String tenant) {
        Long value = jdbc.queryForObject(sql, Long.class, platformAdmin, tenant);
        return value == null ? 0L : value;
    }
}
