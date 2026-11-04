package com.lab.dataworks.project;

import com.lab.dataworks.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 项目新建时创建对应的 Doris 库（业务_dev / 业务_prd）。
 *
 * <p>只做「建库」这一件事：库名由后端按规则生成并通过白名单校验，
 * 不接受前端传入 SQL。删除项目不会删库（库里有数据，删库不可恢复）。
 */
@Service
public class DorisAdminService {

    private static final Logger log = LoggerFactory.getLogger(DorisAdminService.class);
    /** Doris 库名：小写字母开头，只含小写字母、数字、下划线。 */
    private static final Pattern DB_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    private final String url;
    private final String user;
    private final String password;
    /** 管理语句（Resource / Storage Policy）需要 Admin_priv，业务账号没有。 */
    private final String adminUser;
    private final String adminPassword;
    private final WarehouseConnectionService connections;

    public DorisAdminService(@Value("${doris.host:127.0.0.1}") String host,
                             @Value("${doris.port:9030}") int port,
                             @Value("${doris.user:dataworks}") String user,
                             @Value("${doris.password:}") String password,
                             @Value("${doris.admin-user:root}") String adminUser,
                             @Value("${doris.admin-password:}") String adminPassword,
                             WarehouseConnectionService connections) {
        this.connections = connections;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.url = "jdbc:mysql://" + host + ":" + port
                + "/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&connectTimeout=5000&socketTimeout=30000";
        this.user = user;
        this.password = password;
    }

    /**
     * 确保 Doris 库存在。
     *
     * <p>已存在且为空库时直接复用：上一次建项目中断（库建好、项目没落库）后可以重试。
     * 已存在但已有表时拒绝，避免把别人的库挂到当前项目上。
     */
    public void ensureDatabase(String name) {
        String db = requireName(name);
        try (Connection conn = open()) {
            ensureDatabase(conn, db);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "创建 Doris 库 " + db + " 失败: " + detail(e));
        }
    }

    /** 项目选的外部 Doris：用该连接的端点与账号建库；id 为空时走平台内置。 */
    public void ensureDatabase(Long connectionId, String name) {
        if (connectionId == null) {
            ensureDatabase(name);
            return;
        }
        String db = requireName(name);
        var c = connections.get(connectionId, "doris");
        try (Connection conn = openExternal(c)) {
            ensureDatabase(conn, db);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "创建 Doris 库 " + db + " 失败（外部连接 " + c.endpoint() + "）: " + detail(e));
        }
    }

    private Connection openExternal(WarehouseConnectionService.ConnectionInfo c) throws Exception {
        String endpoint = c.endpoint().startsWith("jdbc:") ? c.endpoint()
                : "jdbc:mysql://" + c.endpoint() + "/";
        if (!endpoint.contains("?")) {
            endpoint += "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000";
        }
        Properties props = new Properties();
        props.setProperty("user", c.username() == null ? "" : c.username());
        props.setProperty("password", c.password() == null ? "" : c.password());
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(endpoint, props);
    }

    private void ensureDatabase(Connection conn, String db) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            ResultSet existing = stmt.executeQuery("SHOW DATABASES LIKE '" + db + "'");
            if (existing.next()) {
                try (ResultSet tables = stmt.executeQuery("SHOW TABLES FROM `" + db + "`")) {
                    if (tables.next()) {
                        throw ApiException.conflict(
                                "Doris 库 " + db + " 已存在且不是空库，请换一个业务英文名，或先确认该库归属");
                    }
                }
                log.info("Doris 库已存在且为空，直接复用: {}", db);
                return;
            }
            stmt.execute("CREATE DATABASE IF NOT EXISTS `" + db + "`");
            log.info("Doris 库已创建: {}", db);
        }
    }

    public boolean databaseExists(String name) {
        return databaseExists(null, name);
    }

    /** 外部 Doris 项目要探测自己的实例：connectionId 为空时走平台内置。 */
    public boolean databaseExists(Long connectionId, String name) {
        String db = requireName(name);
        Connection conn = null;
        try {
            if (connectionId == null) {
                conn = open();
            } else {
                conn = openExternal(connections.get(connectionId, "doris"));
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW DATABASES LIKE '" + db + "'")) {
                return rs.next();
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "连接 Doris 失败: " + detail(e));
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响判定结果
                }
            }
        }
    }

    /** 按项目连接执行 DDL：connectionId 为空走平台内置。 */
    public void exec(Long connectionId, String sql) {
        if (connectionId == null) {
            exec(sql);
            return;
        }
        var c = connections.get(connectionId, "doris");
        try (Connection conn = openExternal(c); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "Doris 执行失败（外部连接 " + c.endpoint() + "）: " + detail(e));
        }
    }

    /** 按项目连接执行查询（SHOW / DESC / information_schema），返回列名到值的 Map。 */
    public java.util.List<java.util.Map<String, Object>> queryAs(Long connectionId, String sql) {
        Connection conn = null;
        try {
            conn = connectionId == null ? open() : openExternal(connections.get(connectionId, "doris"));
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int count = meta.getColumnCount();
                java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
                while (rs.next()) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= count; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "Doris 查询失败: " + detail(e));
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // 连接已失效时忽略
                }
            }
        }
    }

    /** 执行一条需要 Admin_priv 的 Doris 管理语句（Resource / Storage Policy）。 */
    public void execAdmin(String sql) {
        execAdmin(null, sql);
    }

    /**
     * 在指定数仓连接上执行管理语句（V100）。
     *
     * <p>connectionId 为空 = 平台内置 Doris；外部连接只借用它的端点，
     * 账号仍用 {@code doris.admin-user}——平台业务账号没有 CREATE CATALOG / GRANT 权限。</p>
     */
    public void execAdmin(Long connectionId, String sql) {
        try (Connection conn = openAdmin(connectionId); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "Doris 执行失败: " + detail(e));
        }
    }

    /** 以管理员身份执行查询（SHOW RESOURCES / SHOW STORAGE POLICY）。 */
    public java.util.List<java.util.Map<String, Object>> queryAdmin(String sql) {
        return queryAdmin(null, sql);
    }

    /** 在指定数仓连接上以管理员身份查询；连接为空 = 平台内置。 */
    public java.util.List<java.util.Map<String, Object>> queryAdmin(Long connectionId, String sql) {
        try (Connection conn = openAdmin(connectionId); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            int count = meta.getColumnCount();
            java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (int i = 1; i <= count; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "Doris 查询失败: " + detail(e));
        }
    }

    /** 执行一条 Doris 管理语句（DDL / 资源 / 策略）。 */
    public void exec(String sql) {
        try (Connection conn = open(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new ApiException(502, "Doris 执行失败: " + detail(e));
        }
    }

    /** 执行查询并把结果按列名转成 Map（管理面展示用）。 */
    public java.util.List<java.util.Map<String, Object>> query(String sql) {
        return queryWith(user, password, sql);
    }

    private java.util.List<java.util.Map<String, Object>> queryWith(String account,
                                                                    String secret, String sql) {
        try (Connection conn = open(account, secret); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            int count = meta.getColumnCount();
            java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (int i = 1; i <= count; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        } catch (Exception e) {
            throw new ApiException(502, "Doris 查询失败: " + detail(e));
        }
    }

    private Connection open() throws Exception {
        return open(user, password);
    }

    private Connection openAdmin() throws Exception {
        return open(adminUser, adminPassword);
    }

    private Connection openAdmin(Long connectionId) throws Exception {
        if (connectionId == null) {
            return openAdmin();
        }
        var c = connections.get(connectionId, "doris");
        String endpoint = c.endpoint() == null ? "" : c.endpoint().trim();
        if (!endpoint.startsWith("jdbc:")) {
            endpoint = "jdbc:mysql://" + endpoint;
        }
        if (!endpoint.contains("?")) {
            endpoint += (endpoint.endsWith("/") ? "" : "/")
                    + "?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=5000&socketTimeout=30000";
        }
        Properties props = new Properties();
        props.setProperty("user", adminUser);
        props.setProperty("password", adminPassword == null ? "" : adminPassword);
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(endpoint, props);
    }

    private Connection open(String account, String secret) throws Exception {
        Properties props = new Properties();
        props.setProperty("user", account);
        props.setProperty("password", secret == null ? "" : secret);
        DriverManager.setLoginTimeout(5);
        return DriverManager.getConnection(url, props);
    }

    private String requireName(String name) {
        if (name == null || !DB_NAME.matcher(name).matches()) {
            throw ApiException.badRequest("Doris 库名不合法: " + name);
        }
        return name;
    }

    private String detail(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
