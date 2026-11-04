package com.lab.dataworks.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("time", OffsetDateTime.now().toString());
        try {
            Integer one = jdbcTemplate.queryForObject("select 1", Integer.class);
            // platform_meta.schema_version 从 V1 起就不再更新，实际版本以 Flyway 为准
            // 取「最大版本号」而不是「最后安装的一条」：并行泳道各自加迁移（V113/V114/V115），
            // V114 因乱序在 V115 之后应用，按 installed_rank 取会倒退显示 114（D55）。
            // 必须带 version is not null：flyway_schema_history 里有一行
            // `<< Flyway Schema Creation >>` 的伪记录 version 为 NULL，而 PG 的 ORDER BY ... DESC
            // 默认 NULLS FIRST —— 不加这个条件，本查询返回 null，health 的 schemaVersion 变空。
            // 实测三种写法的结果见 docs/evidence/v537-d55-sql-probe.log。
            String schemaVersion = jdbcTemplate.queryForObject("""
                    select version from platform.flyway_schema_history
                    where success and version is not null
                    order by regexp_replace(version, '[^0-9]', '', 'g')::int desc
                    limit 1
                    """, String.class);
            out.put("db", one != null && one == 1 ? "UP" : "DOWN");
            out.put("schemaVersion", schemaVersion);
        } catch (Exception e) {
            out.put("status", "DOWN");
            out.put("db", "DOWN");
            // 健康检查是公开接口，不回显数据库异常细节
            out.put("error", "数据库连接异常");
        }
        return out;
    }
}
