package com.lab.dataworks.lake;

import com.lab.dataworks.auth.AuthInterceptor;
import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.security.DataGuardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Iceberg 湖（Doris 外部 Catalog）接口（V100）。
 *
 * <p>契约见 {@code docs/数据湖-Iceberg接入-20261025.md}。三类能力：</p>
 * <ul>
 *   <li>运维：provision / refresh / test / 删除 Catalog（confirm=Catalog 名）；</li>
 *   <li>浏览：库 / 表 / 字段 / 分区 / 位置 / 行数（拿不到时为 null，并在 warnings 说明）；</li>
 *   <li>建表：列定义 + identity / bucket / day 分区；DDL 不含 DISTRIBUTED / BUCKETS / 副本。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/lake")
public class LakeController {

    private final LakeCatalogService lake;
    private final DataGuardService guard;
    private final SessionService sessions;

    public LakeController(LakeCatalogService lake, DataGuardService guard,
                          SessionService sessions) {
        this.lake = lake;
        this.guard = guard;
        this.sessions = sessions;
    }

    public record ColumnInput(String name, String type, Boolean nullable, String comment) {
    }

    public record PartitionInput(String kind, String column, Integer buckets) {
    }

    public record CreateTableInput(String table, String comment, List<ColumnInput> columns,
                                   PartitionInput partition, Integer formatVersion) {
    }

    public record CreateDatabaseInput(String database, String comment) {
    }

    public record RegisterInput(String ownerUser, String dataLevel) {
    }

    // ---------------------------------------------------------------- 模板

    /** 配置模板 + 分区类型 + 错误码文案（前端渲染与验收共用）。 */
    @GetMapping("/template")
    public Map<String, Object> template() {
        return LakeCatalogTemplate.describe();
    }

    // ---------------------------------------------------------------- 运维

    @PostMapping("/datasources/{id}/provision")
    @RequireAtom("datasource:write")
    public Map<String, Object> provision(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> result = lake.provision(id);
        audit(http, "LAKE_CATALOG_PROVISION", String.valueOf(result.get("catalog")),
                String.valueOf(result.get("status")), "available".equals(result.get("status")));
        return result;
    }

    @PostMapping("/datasources/{id}/refresh")
    @RequireAtom("datasource:write")
    public Map<String, Object> refresh(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> result = lake.provision(id);
        audit(http, "LAKE_CATALOG_REFRESH", String.valueOf(result.get("catalog")),
                String.valueOf(result.get("status")), "available".equals(result.get("status")));
        return result;
    }

    @PostMapping("/datasources/{id}/test")
    @RequireAtom("datasource:write")
    public Map<String, Object> test(@PathVariable long id, HttpServletRequest http) {
        Map<String, Object> result = lake.test(id);
        audit(http, "LAKE_CATALOG_TEST", String.valueOf(result.get("catalog")),
                String.valueOf(result.get("error") == null ? "ok" : result.get("error")),
                Boolean.TRUE.equals(result.get("ok")));
        return result;
    }

    @DeleteMapping("/datasources/{id}/catalog")
    @RequireAtom("datasource:delete")
    public Map<String, Object> dropCatalog(@PathVariable long id,
                                           @RequestParam String confirm,
                                           HttpServletRequest http) {
        // 服务层按租户过滤（load/requireOwned），这里显式带租户写审计，便于追溯
        String tenant = TenantScope.current();
        Map<String, Object> result = lake.dropCatalog(id, confirm);
        audit(http, "LAKE_CATALOG_DROP", String.valueOf(result.get("catalog")),
                "tenant=" + tenant + "；湖数据保留，仅删除 Doris Catalog", true);
        return result;
    }

    // ---------------------------------------------------------------- 浏览

    @GetMapping("/catalogs")
    @RequireAtom("query:run")
    public List<Map<String, Object>> catalogs() {
        return lake.list();
    }

    @GetMapping("/catalogs/{catalog}/databases")
    @RequireAtom("query:run")
    public Map<String, Object> databases(@PathVariable String catalog) {
        return lake.databases(catalog);
    }

    @GetMapping("/catalogs/{catalog}/databases/{database}/tables")
    @RequireAtom("query:run")
    public Map<String, Object> tables(@PathVariable String catalog,
                                      @PathVariable String database,
                                      @RequestParam(defaultValue = "false") boolean count) {
        return lake.tables(catalog, database, count);
    }

    @GetMapping("/catalogs/{catalog}/databases/{database}/tables/{table}")
    @RequireAtom("query:run")
    public Map<String, Object> table(@PathVariable String catalog,
                                     @PathVariable String database,
                                     @PathVariable String table) {
        return lake.tableDetail(catalog, database, table);
    }

    // ---------------------------------------------------------------- DDL

    @PostMapping("/catalogs/{catalog}/databases")
    @RequireAtom("table:ddl")
    public Map<String, Object> createDatabase(@PathVariable String catalog,
                                              @RequestBody CreateDatabaseInput input,
                                              HttpServletRequest http) {
        if (input == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Map<String, Object> result = lake.createDatabase(catalog, input.database(),
                input.comment(), user(http));
        audit(http, "LAKE_DATABASE_CREATE", catalog + "." + input.database(), null, true);
        return result;
    }

    @PostMapping("/catalogs/{catalog}/databases/{database}/tables/preview")
    @RequireAtom("table:ddl")
    public Map<String, Object> previewTable(@PathVariable String catalog,
                                            @PathVariable String database,
                                            @RequestBody CreateTableInput input) {
        if (input == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        return lake.previewTable(catalog, database, input.table(), input.comment(),
                columns(input.columns()), partition(input.partition()), input.formatVersion());
    }

    @PostMapping("/catalogs/{catalog}/databases/{database}/tables")
    @RequireAtom("table:ddl")
    public Map<String, Object> createTable(@PathVariable String catalog,
                                           @PathVariable String database,
                                           @RequestBody CreateTableInput input,
                                           HttpServletRequest http) {
        if (input == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        Map<String, Object> result = lake.createTable(catalog, database, input.table(),
                input.comment(), columns(input.columns()), partition(input.partition()),
                input.formatVersion(), user(http));
        // 建表成功即纳管（L2 内部），否则默认 ENFORCE 会因「未纳管按 L3」拒绝查询；
        // 之后可在数据分级里把敏感列升到 L3/L4 并配置 MASK/TOKEN。
        Map<String, Object> managed = null;
        try {
            Map<String, Object> registered = guard.register(catalog, database, input.table(),
                    user(http), "L2", user(http));
            result.put("managedTableId", registered.get("id"));
            result.put("dataLevel", registered.get("dataLevel"));
        } catch (ApiException e) {
            // 表已经建成功：纳管失败只降级为警告，不能让调用方误以为建表失败。
            // 409 = 该表此前已纳管：仍然查出 id 回传，不能因为「已存在」就没有 managedTableId。
            managed = lake.managedTable(catalog, database, input.table());
            result.put("managedTableId", managed == null ? null : managed.get("id"));
            result.put("dataLevel", managed == null ? null : managed.get("dataLevel"));
            result.put("warnings", mergeWarning(result.get("warnings"),
                    e.getStatus() == 409
                            ? (managed == null
                                    ? "该表此前已纳管，沿用原分级配置"
                                    : "该表此前已纳管，沿用原分级配置（id="
                                            + managed.get("id") + "）")
                            : "自动纳管失败，请到表管理页手工「登记纳管」：" + e.getMessage()));
        }
        if (result.get("managedTableId") == null) {
            // 兜底：register 返回体异常时按唯一键再查一次，保证「已纳管」就一定有 id
            managed = managed == null ? lake.managedTable(catalog, database, input.table()) : managed;
            if (managed != null) {
                result.put("managedTableId", managed.get("id"));
                result.put("dataLevel", managed.get("dataLevel"));
            }
        }
        audit(http, "LAKE_TABLE_CREATE", catalog + "." + database + "." + input.table(),
                String.valueOf(result.get("ddl")), true);
        return result;
    }

    @DeleteMapping("/catalogs/{catalog}/databases/{database}/tables/{table}")
    @RequireAtom("table:ddl")
    public Map<String, Object> dropTable(@PathVariable String catalog,
                                         @PathVariable String database,
                                         @PathVariable String table,
                                         @RequestParam String confirm,
                                         HttpServletRequest http) {
        String tenant = TenantScope.current();
        // S12 + S13：LAKE_TABLE_DROP 原来先做后记——lake.dropTable 一抛异常（confirm 不匹配
        // 400 / 未纳管 409 / DDL 失败）就一条审计都不留，detail 也只有一个 tenant=。
        // 改成先记 PENDING 再执行，失败回填 FAILED；detail 带全表归属（租户+Catalog+库+表）。
        String full = catalog + "." + database + "." + table;
        return sessions.auditAround(user(http), "LAKE_TABLE_DROP", full,
                "tenant=" + tenant + " catalog=" + catalog + " database=" + database
                        + " table=" + table,
                AuthInterceptor.clientIp(http), () -> {
                    lake.dropTable(catalog, database, table, confirm);
                    return Map.of("ok", true, "catalog", catalog, "database", database,
                            "table", table);
                });
    }

    /** 湖表登记到数据分级（catalog = 湖 Catalog 名）。 */
    @PostMapping("/catalogs/{catalog}/databases/{database}/tables/{table}/register")
    @RequireAtom("data:classify")
    public Map<String, Object> register(@PathVariable String catalog,
                                        @PathVariable String database,
                                        @PathVariable String table,
                                        @RequestBody(required = false) RegisterInput input,
                                        HttpServletRequest http) {
        String owner = input == null ? null : input.ownerUser();
        String level = input == null ? null : input.dataLevel();
        Map<String, Object> result = guard.register(catalog, database, table,
                owner, level == null ? "L2" : level, user(http));
        audit(http, "LAKE_TABLE_REGISTER", catalog + "." + database + "." + table,
                "dataLevel=" + result.get("dataLevel"), true);
        return result;
    }

    // ---------------------------------------------------------------- 辅助

    private static List<LakeDdl.Column> columns(List<ColumnInput> input) {
        if (input == null) {
            return List.of();
        }
        List<LakeDdl.Column> out = new ArrayList<>(input.size());
        for (ColumnInput column : input) {
            if (column == null) {
                continue;
            }
            out.add(new LakeDdl.Column(column.name(), column.type(), column.nullable(),
                    column.comment()));
        }
        return out;
    }

    private static LakeDdl.Partition partition(PartitionInput input) {
        return input == null ? null
                : new LakeDdl.Partition(input.kind(), input.column(), input.buckets());
    }

    private static List<String> mergeWarning(Object existing, String warning) {
        List<String> out = new ArrayList<>();
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        out.add(warning);
        return out;
    }

    private String user(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute("username");
        return value == null ? "unknown" : String.valueOf(value);
    }

    private void audit(HttpServletRequest request, String action, String target,
                       String detail, boolean ok) {
        sessions.audit(user(request), action, target, detail, ok ? "SUCCESS" : "FAILED",
                AuthInterceptor.clientIp(request));
    }
}
