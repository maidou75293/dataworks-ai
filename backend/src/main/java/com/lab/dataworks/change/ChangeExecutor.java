package com.lab.dataworks.change;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.JdbcTimeouts;
import com.lab.dataworks.common.JdbcUrlGuard;
import com.lab.dataworks.secret.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 变更单执行器：审批通过后真正执行 SQL，并在 DROP / TRUNCATE 单表前自动备份。
 *
 * <p>通道（inbox/007 第六节）：</p>
 * <ul>
 *   <li>DORIS：内置 Doris 用平台配置的账号口令（数据源行里的内置账号口令是空的），
 *       外部 Doris 用数据源自己的连接；</li>
 *   <li>SPARK：数据源自己的 HiveServer2 连接（kyuubi-hive-jdbc）。</li>
 * </ul>
 *
 * <p>备份失败一律**不执行原语句**；执行失败保留备份表，人工用备份恢复
 * （007 明确 v1 不做一键回滚）。</p>
 */
@Service
public class ChangeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChangeExecutor.class);

    /** 单条语句的执行超时（秒）：DDL 可能比查询慢，给足 10 分钟。 */
    private static final int TIMEOUT_SECONDS = 600;

    /** 写进 exec_result 的错误信息上限，避免把整段 JDBC 报文塞进数据库列。 */
    private static final int DETAIL_MAX = 1000;

    private final SecretCipher cipher;
    private final String dorisHost;
    private final int dorisPort;
    private final String dorisUser;
    private final String dorisPassword;

    public ChangeExecutor(SecretCipher cipher,
                          @Value("${doris.host:127.0.0.1}") String dorisHost,
                          @Value("${doris.port:9030}") int dorisPort,
                          @Value("${doris.user:dataworks}") String dorisUser,
                          @Value("${doris.password:}") String dorisPassword) {
        this.cipher = cipher;
        this.dorisHost = dorisHost;
        this.dorisPort = dorisPort;
        this.dorisUser = dorisUser;
        this.dorisPassword = dorisPassword;
    }

    /**
     * 执行结果。
     *
     * @param ok          是否全部语句执行成功
     * @param backupTable 本次新建的备份表（多张用逗号；没有备份时为 null）
     * @param detail      写进 change_request.exec_result 的可读说明
     */
    public record Outcome(boolean ok, String backupTable, String detail) {
    }

    /** 解析后的执行目标；label 用于结果里的「连的是谁」。 */
    private record Target(String jdbcUrl, String username, String password, String label) {
    }

    /**
     * 执行变更单 SQL。任何失败都返回 {@code ok=false}，不抛异常——
     * 审批流程要把失败原因原样写进 exec_result，让状态收敛到 FAILED。
     */
    public Outcome execute(String sql, Map<String, Object> datasource) {
        List<String> statements = ChangeRules.statements(sql);
        if (statements.isEmpty()) {
            return new Outcome(false, null, "SQL 为空，未执行");
        }
        String dsType = upper(datasource.get("ds_type"));
        if (!ChangeRules.executable(dsType)) {
            return new Outcome(false, null,
                    "变更执行只支持 Doris / Spark 数据源，当前是 " + dsType);
        }
        Target target;
        try {
            target = resolve(datasource, dsType);
        } catch (ApiException e) {
            return new Outcome(false, null, "数据源不可用：" + e.getMessage());
        }

        List<String> backups = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        long start = System.currentTimeMillis();
        detail.append("目标：").append(target.label()).append("；");
        try {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("user", target.username());
            props.setProperty("password", target.password());
            JdbcTimeouts.apply(props, target.jdbcUrl(), 10, TIMEOUT_SECONDS);
            String driver = JdbcUrlGuard.driverFor(target.jdbcUrl());
            if (driver != null) {
                Class.forName(driver);
            }
            DriverManager.setLoginTimeout(10);
            try (Connection conn = DriverManager.getConnection(target.jdbcUrl(), props)) {
                for (int i = 0; i < statements.size(); i++) {
                    String stmt = statements.get(i);
                    ChangeRules.BackupPlan plan = ChangeRules.backupPlan(stmt);
                    if (plan.kind() == ChangeRules.BackupKind.UNSUPPORTED) {
                        return new Outcome(false, joined(backups),
                                finish(detail, start) + "；未执行：" + plan.message());
                    }
                    if (plan.kind() == ChangeRules.BackupKind.REQUIRED) {
                        String backup;
                        try {
                            backup = ChangeRules.backupName(plan.table(), LocalDateTime.now());
                            run(conn, "CREATE TABLE " + backup + " AS SELECT * FROM " + plan.table());
                        } catch (SQLException | IllegalArgumentException e) {
                            return new Outcome(false, joined(backups),
                                    finish(detail, start) + "；备份失败，未执行原语句："
                                            + cut(e.getMessage()));
                        }
                        backups.add(backup);
                        detail.append("备份 ").append(plan.table()).append(" → ")
                                .append(backup).append("；");
                    }
                    try {
                        StatementResult result = run(conn, stmt);
                        detail.append("第 ").append(i + 1).append(" 条")
                                .append(result.resultSet() ? "返回结果集（已关闭）"
                                        : "影响 " + result.updateCount() + " 行")
                                .append("；");
                    } catch (SQLException e) {
                        log.warn("change execute failed at statement {}: {}", i + 1,
                                cut(e.getMessage()));
                        return new Outcome(false, joined(backups),
                                finish(detail, start) + "；第 " + (i + 1) + " 条执行失败："
                                        + cut(e.getMessage())
                                        + (backups.isEmpty() ? ""
                                           : "；备份表保留：" + joined(backups)));
                    }
                }
            }
        } catch (ApiException e) {
            return new Outcome(false, joined(backups), finish(detail, start) + "；" + e.getMessage());
        } catch (ClassNotFoundException e) {
            return new Outcome(false, joined(backups),
                    finish(detail, start) + "；缺少 JDBC 驱动：" + cut(e.getMessage()));
        } catch (Exception e) {
            log.warn("change execute connection failed: {}", cut(e.getMessage()));
            return new Outcome(false, joined(backups),
                    finish(detail, start) + "；连接或执行失败：" + cut(e.getMessage()));
        }
        return new Outcome(true, joined(backups), finish(detail, start) + "；全部执行成功");
    }

    // ------------------------------------------------------------------ D53：备份表管理

    /**
     * 内置 Doris information_schema 里查表大小（data_length，字节）。
     *
     * <p>只覆盖**内置 Doris**：外部数据源的备份表在各自实例里，v1 不聚合，
     * 查不到/连不上一律返回能查到的部分，不让备份列表接口因 Doris 抖动而失败。</p>
     *
     * @param qualifiedTables 形如 {@code db.t_bak_20261026153000} 的全限定表名
     */
    public Map<String, Long> builtinTableSizes(Collection<String> qualifiedTables) {
        Map<String, Long> out = new LinkedHashMap<>();
        List<String[]> pairs = new ArrayList<>();
        for (String name : qualifiedTables) {
            int dot = name == null ? -1 : name.indexOf('.');
            if (dot <= 0 || dot == name.length() - 1) {
                continue; // 无库名前缀的备份表查不了 information_schema，跳过
            }
            pairs.add(new String[]{name.substring(0, dot), name.substring(dot + 1)});
        }
        if (pairs.isEmpty()) {
            return out;
        }
        try (Connection conn = builtinConnection()) {
            for (String[] pair : pairs) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "select data_length from information_schema.tables"
                                + " where table_schema = ? and table_name = ?")) {
                    ps.setString(1, pair[0]);
                    ps.setString(2, pair[1]);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out.put(pair[0] + "." + pair[1], rs.getLong(1));
                        }
                    }
                } catch (SQLException e) {
                    log.debug("backup size query failed for {}.{}: {}",
                            pair[0], pair[1], cut(e.getMessage()));
                }
            }
        } catch (Exception e) {
            log.warn("builtin doris unavailable for backup sizes: {}", cut(e.getMessage()));
        }
        return out;
    }

    /**
     * 在内置 Doris 上 DROP 一张平台备份表（备份清理接口专用）。
     *
     * <p>表名必须是 {@link ChangeRules#isBackupName} 认可的 {@code *_bak_<14位时间戳>}
     * 形态——这个接口能直达 Doris 执行 DROP，形态校验是防止它被当成任意删表通道。</p>
     */
    public void dropBuiltinTable(String qualifiedTable) throws SQLException {
        if (!ChangeRules.isBackupName(qualifiedTable)) {
            throw new IllegalArgumentException("不是平台备份表名，拒绝删除: " + qualifiedTable);
        }
        try (Connection conn = builtinConnection(); Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(TIMEOUT_SECONDS);
            stmt.execute("DROP TABLE IF EXISTS " + qualifiedTable);
        } catch (ClassNotFoundException e) {
            throw new SQLException("缺少 JDBC 驱动: " + e.getMessage(), e);
        }
    }

    /** 内置 Doris 连接（平台配置账号；与 resolve() 的 builtin 分支同一口径）。 */
    private Connection builtinConnection() throws SQLException, ClassNotFoundException {
        String url = JdbcUrlGuard.validate("jdbc:mysql://" + dorisHost + ":" + dorisPort
                + "/?useSSL=false&allowPublicKeyRetrieval=true");
        String driver = JdbcUrlGuard.driverFor(url);
        if (driver != null) {
            Class.forName(driver);
        }
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", dorisUser);
        props.setProperty("password", dorisPassword == null ? "" : dorisPassword);
        JdbcTimeouts.apply(props, url, 10, TIMEOUT_SECONDS);
        DriverManager.setLoginTimeout(10);
        return DriverManager.getConnection(url, props);
    }

    // ------------------------------------------------------------------ 目标解析

    private Target resolve(Map<String, Object> datasource, String dsType) {
        boolean builtin = "true".equalsIgnoreCase(String.valueOf(datasource.get("builtin")));
        String url;
        String user;
        String password;
        String label;
        if (builtin && "DORIS".equals(dsType)) {
            // 内置 Doris 的口令在平台配置里（数据源行的 password 是历史空值）
            String raw = text(datasource.get("jdbc_url"));
            url = raw.isBlank()
                    ? "jdbc:mysql://" + dorisHost + ":" + dorisPort
                      + "/?useSSL=false&allowPublicKeyRetrieval=true"
                    : raw;
            user = dorisUser;
            password = dorisPassword == null ? "" : dorisPassword;
            label = "内置 Doris（" + dorisHost + ":" + dorisPort + "）";
        } else {
            url = text(datasource.get("jdbc_url"));
            user = text(datasource.get("username"));
            password = cipher.decrypt(text(datasource.get("password")));
            label = "数据源 " + datasource.get("name") + "（" + dsType + "）";
        }
        if (url.isBlank()) {
            throw ApiException.badRequest("数据源缺少 JDBC 地址");
        }
        return new Target(JdbcUrlGuard.validate(url), user, password, label);
    }

    // ------------------------------------------------------------------ JDBC

    private record StatementResult(boolean resultSet, long updateCount) {
    }

    private StatementResult run(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(TIMEOUT_SECONDS);
            boolean hasResult = stmt.execute(sql);
            if (hasResult) {
                try (ResultSet rs = stmt.getResultSet()) {
                    // 变更语句正常不返回结果集；出现时关闭它，避免连接被占用
                    if (rs != null) {
                        rs.getMetaData();
                    }
                }
                return new StatementResult(true, 0);
            }
            long count = stmt.getUpdateCount();
            return new StatementResult(false, Math.max(count, 0));
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String finish(StringBuilder detail, long start) {
        return detail + "耗时 " + (System.currentTimeMillis() - start) + "ms";
    }

    private static String joined(List<String> backups) {
        return backups.isEmpty() ? null : String.join(",", backups);
    }

    private static String cut(String value) {
        if (value == null) {
            return "未知错误";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= DETAIL_MAX ? oneLine : oneLine.substring(0, DETAIL_MAX) + "…";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String upper(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
    }
}
