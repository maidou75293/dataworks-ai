package com.lab.dataworks.project;

import com.lab.dataworks.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 解析「这次操作该连哪一个 Doris」。
 *
 * <p>V98 起项目引用 {@code platform.datasource}（type=DORIS），这里统一委托
 * {@link WarehouseResolver}：内置数据源 → 平台内置（查询仍走 MCP），
 * 外部数据源 → 按端点解析到 {@code warehouse_connection}，后端 JDBC 直连。
 * 查询页、质量规则、节点试跑都从这里拿目标，避免各写一份判断。
 */
@Service
public class DorisTargetService {

    /** 执行目标；external=false 时只有展示用的 label，凭据由 MCP 侧持有。 */
    public record Target(boolean external, String label, String jdbcUrl,
                         String username, String password) {
    }

    private final WarehouseConnectionService connections;
    private final WarehouseResolver resolver;
    private final String builtinHost;
    private final int builtinPort;

    public DorisTargetService(WarehouseConnectionService connections, WarehouseResolver resolver,
                              @Value("${doris.host:127.0.0.1}") String builtinHost,
                              @Value("${doris.port:9030}") int builtinPort) {
        this.connections = connections;
        this.resolver = resolver;
        this.builtinHost = builtinHost;
        this.builtinPort = builtinPort;
    }

    public Target builtin() {
        return new Target(false, "内置 Doris（" + builtinHost + ":" + builtinPort + "）",
                null, null, null);
    }

    /** projectId 为空 = 平台内置；项目不存在或不属于当前租户按 404 处理。 */
    public Target forProject(Long projectId) {
        if (projectId == null) {
            return builtin();
        }
        // 统一解析：项目 -> DORIS 数据源 -> warehouse_connection（内置为 null）
        Long connectionId = resolver.connectionIdForProject(projectId);
        if (connectionId == null) {
            return builtin();
        }
        return fromConnection(connections.get(connectionId, "doris"));
    }

    public Target fromConnection(WarehouseConnectionService.ConnectionInfo c) {
        String url = c.endpoint() == null ? "" : c.endpoint().trim();
        if (url.isEmpty()) {
            throw ApiException.badRequest("Doris 连接缺少端点：" + c.id());
        }
        if (!url.startsWith("jdbc:")) {
            url = "jdbc:mysql://" + url;
        }
        if (!url.contains("?") && !url.endsWith("/")) {
            url += "/";
        }
        if (!url.contains("?")) {
            url += "?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=5000&socketTimeout=60000";
        }
        // 端点是用户填的：走与数据源相同的入口校验（危险连接参数 / 内嵌口令 / 元数据地址）
        com.lab.dataworks.common.JdbcUrlGuard.validate(url);
        String label = "外部 Doris（" + c.endpoint() + "）";
        return new Target(true, label, url,
                c.username() == null ? "" : c.username(),
                c.password() == null ? "" : c.password());
    }
}
