package com.lab.dataworks.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.baseline.BaselineService.BaselineRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D33 回归：基线更新的写入边界（改主表 → 清成员 → 重插成员 必须同生共死）。
 *
 * <p>立案描述是「类里没有 {@code @Transactional} → 成员可能被删空后写入失败」。
 * 核对代码后的事实：关键字确实没有，但边界一直存在——
 * {@link BaselineService#update} 用的是编程式事务 {@code transactions.execute(...)}
 * （{@code BaselineService.java:189-200}，自 V107 首提交 bec10fd 起即如此），
 * 所以现网不会出现「成员为空」的静默损坏。</p>
 *
 * <p>本卡真正缺的是**锁定这个边界的测试**：一旦有人把先删后插挪出 lambda，
 * 损坏会静默复发，CI 不会红。</p>
 *
 * <p>测试形状：本机连不上 DEV PG（{@code 10.99.99.11:30432} refused，无本地实例/docker），
 * 所以只假造最底层的 JDBC 连接——事务开始时打快照、rollback 恢复快照、commit 丢弃快照，
 * 上面跑**真的** {@link JdbcTemplate} + {@link DataSourceTransactionManager}
 * + {@link TransactionTemplate}：服务的提交/回滚走 Spring 真实事务路径，
 * 「成员保持更新前集合」是被真 rollback 还原出来的，不是断言出来的。
 * （早期版本 mock 掉了 JdbcTemplate，SQL 绕过连接层直接落库，等于没测事务，已废弃该写法。）</p>
 *
 * <p>红探针（记忆「守卫要验过它红得起来」）：
 * {@link #update_withoutTransactionBoundary_isTheCorruption()} 在
 * {@code PROPAGATION_NEVER}（= 没有边界）下重放同一段写序列，断言它**确实**写出
 * 「旧成员删空 + 新成员只插进一半 + 改名无从回退」的半集状态。
 * 撤掉 {@code update()} 的 lambda 包装，
 * {@link #update_memberInsertFails_rollsBackWholeUpdate()} 必红。</p>
 */
class BaselineUpdateTransactionTest {

    private static final String TENANT = "tenant_a";
    private static final long BASELINE_ID = 1L;
    private static final long WF_A = 101L;
    private static final long WF_B = 102L;
    private static final long WF_NEW = 103L;
    private static final String OLD_NAME = "旧基线名";
    private static final String NEW_NAME = "新基线名";

    private final FakeStore store = new FakeStore();
    private final DataSource dataSource = new FakeJdbc(store).dataSource();
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private BaselineService service;

    @BeforeEach
    void setUp() {
        TenantScope.set(TENANT);
        store.seedBaseline(BASELINE_ID, TENANT, OLD_NAME);
        store.seedMember(BASELINE_ID, WF_A);
        store.seedMember(BASELINE_ID, WF_B);
        service = new BaselineService(jdbc, new ObjectMapper(), transactions(false));
    }

    @AfterEach
    void tearDown() {
        TenantScope.clear();
    }

    private TransactionTemplate transactions(boolean neverPropagation) {
        TransactionTemplate template =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        if (neverPropagation) {
            // 等价于「写序列没有事务边界」：不 begin、不 rollback，每条语句各自生效
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NEVER);
        }
        return template;
    }

    // ---------------------------------------------------------------- 正常路径

    @Test
    void update_commitsNewMembershipAndHeader() {
        Map<String, Object> out = service.update(BASELINE_ID,
                request(NEW_NAME, List.of(WF_B, WF_NEW)), "alice");

        assertEquals(NEW_NAME, String.valueOf(out.get("name")));
        assertEquals(2, ((Number) out.get("memberCount")).intValue());
        // 落库事实：主表改名 + 成员集合换成 {102,103}
        assertEquals(NEW_NAME, store.baselineName(BASELINE_ID));
        assertEquals(List.of(WF_B, WF_NEW), store.visibleMembers(BASELINE_ID));
    }

    // ---------------------------------------------------------------- 失败回滚

    @Test
    void update_memberInsertFails_rollsBackWholeUpdate() {
        // 注入：第 2 条成员 INSERT 失败（模拟约束冲突/连接中断/磁盘故障）
        store.failOnNthMemberInsert = 2;

        assertThrows(RuntimeException.class, () -> service.update(BASELINE_ID,
                request(NEW_NAME, List.of(WF_B, WF_NEW)), "alice"));

        // 卡的核心期望：任一步失败整体回滚——成员保持更新前的集合，改名也一起回退
        assertEquals(List.of(WF_A, WF_B), store.visibleMembers(BASELINE_ID),
                "D33：成员插入失败后基线成员必须保持更新前集合，不能为空/半集");
        assertEquals(OLD_NAME, store.baselineName(BASELINE_ID),
                "D33：主表改名必须在同一事务里一起回滚");
    }

    @Test
    void update_withoutTransactionBoundary_isTheCorruption() {
        // 红探针：同一份写序列在没有事务边界时重放（= 有人把先删后插挪出 lambda 的后果）。
        // 这条断言的是「损坏形状」本身；若哪天它不成立，说明重放已经不再等价于无边界执行。
        store.failOnNthMemberInsert = 2;

        assertThrows(RuntimeException.class, () -> transactions(true).execute(status -> {
            jdbc.update("update platform.baseline set name = ?, updated_at = now()"
                    + " where id = ? and tenant_id = ?", NEW_NAME, BASELINE_ID, TENANT);
            jdbc.update("delete from platform.baseline_member where baseline_id = ?", BASELINE_ID);
            jdbc.update("insert into platform.baseline_member (baseline_id, workflow_id)"
                    + " values (?, ?) on conflict do nothing", BASELINE_ID, WF_B);
            jdbc.update("insert into platform.baseline_member (baseline_id, workflow_id)"
                    + " values (?, ?) on conflict do nothing", BASELINE_ID, WF_NEW);
            return null;
        }));

        assertEquals(List.of(WF_B), store.visibleMembers(BASELINE_ID),
                "红探针自检：无边界时旧成员已被删空、新成员只插进一半（D33 立案的损坏形状）");
        assertEquals(NEW_NAME, store.baselineName(BASELINE_ID),
                "红探针自检：无边界时改名已生效且无从回退");
    }

    @Test
    void create_memberInsertFails_leavesNoPartialBaseline() {
        // 同一边界也保护创建：成员插入失败不得留下半提交的基线主表行（孤儿基线）。
        store.failOnNthMemberInsert = 2;

        assertThrows(RuntimeException.class, () -> service.create(
                new BaselineRequest(9L, "新基线", "23:00", 30, "P2", "alice", true,
                        List.of(WF_A, WF_NEW)), "alice"));

        // 种子里本来就有一条基线（BASELINE_ID）；创建失败不得再多出任何基线行
        assertEquals(List.of(BASELINE_ID), store.baselineIds(),
                "D33：创建失败不得留下半提交的基线主表行（孤儿基线）");
        assertEquals(List.of(WF_A, WF_B), store.visibleMembers(BASELINE_ID),
                "D33：创建失败不得影响既有基线的成员集合");
    }

    // ---------------------------------------------------------------- 工具

    private static BaselineRequest request(String name, List<Long> workflowIds) {
        return new BaselineRequest(null, name, null, null, null, null, null, workflowIds);
    }

    // ---------------------------------------------------------------- 假连接层

    /**
     * 最小 JDBC 替身：语句即时写入工作区；事务开始（{@code setAutoCommit(false)}）打快照，
     * {@code rollback} 恢复快照、{@code commit} 丢弃快照。
     * 只需覆盖 {@link JdbcTemplate} 与 {@link DataSourceTransactionManager} 实际调用的方法面。
     */
    private static final class FakeJdbc {

        private final FakeStore store;
        private final DataSource dataSource;

        FakeJdbc(FakeStore store) {
            this.store = store;
            this.dataSource = proxy(DataSource.class, (p, m, args) ->
                    "getConnection".equals(m.getName()) ? newConnection() : defaultValue(m));
        }

        DataSource dataSource() {
            return dataSource;
        }

        private Connection newConnection() {
            ConnHandler handler = new ConnHandler();
            Connection connection = proxy(Connection.class, handler);
            handler.connection = connection;
            return connection;
        }

        private final class ConnHandler implements InvocationHandler {
            private boolean autoCommit = true;
            private boolean closed;
            private Object[] snapshot;   // null = 不在事务中
            private Connection connection;

            @Override
            public Object invoke(Object p, Method m, Object[] args) {
                return switch (m.getName()) {
                    case "prepareStatement" -> newStatement((String) args[0]);
                    case "getMetaData" -> proxy(java.sql.DatabaseMetaData.class, (mp, mm, ma) ->
                            switch (mm.getName()) {
                                case "getDatabaseProductName" -> "PostgreSQL";
                                case "getDriverName" -> "PostgreSQL JDBC Driver";
                                case "getDatabaseProductVersion", "getDriverVersion" -> "42.7.0";
                                default -> defaultValue(mm);
                            });
                    case "setAutoCommit" -> {
                        boolean value = (Boolean) args[0];
                        if (autoCommit && !value) {
                            snapshot = store.snapshot();   // 进入事务
                        }
                        if (!autoCommit && value) {
                            snapshot = null;               // 退出事务（commit/rollback 已处理）
                        }
                        autoCommit = value;
                        yield null;
                    }
                    case "getAutoCommit" -> autoCommit;
                    case "commit" -> {
                        snapshot = null;   // 工作区即最终状态
                        yield null;
                    }
                    case "rollback" -> {
                        if (snapshot != null) {
                            store.restore(snapshot);   // 整段写序列凭空消失
                            snapshot = null;
                        }
                        yield null;
                    }
                    case "close" -> {
                        closed = true;
                        yield null;
                    }
                    case "isClosed" -> closed;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    default -> defaultValue(m);
                };
            }

            private PreparedStatement newStatement(String sql) {
                StatementHandler handler = new StatementHandler(sql);
                PreparedStatement statement = proxy(PreparedStatement.class, handler);
                handler.statement = statement;
                return statement;
            }

            private final class StatementHandler implements InvocationHandler {
                private final String sql;
                private final Map<Integer, Object> params = new LinkedHashMap<>();
                private PreparedStatement statement;

                StatementHandler(String sql) {
                    this.sql = sql;
                }

                @Override
                public Object invoke(Object p, Method m, Object[] args) {
                    if ("getConnection".equals(m.getName())) {
                        return connection;
                    }
                    return switch (m.getName()) {
                        // Spring 的 StatementCreatorUtils 按值类型挑具体 setter（String→setString、
                        // Long→setObject…），必须全部当成绑定参数收进来，否则参数表缺一位
                        // → 谓词错位，比没有断言更危险。
                        case "setObject", "setString", "setNString", "setLong", "setInt",
                             "setBoolean", "setTimestamp", "setDate", "setTime",
                             "setBigDecimal", "setBytes" -> {
                            params.put((Integer) args[0], args[1]);
                            yield null;
                        }
                        case "setNull" -> {
                            params.put((Integer) args[0], null);
                            yield null;
                        }
                        case "clearParameters" -> {
                            params.clear();
                            yield null;
                        }
                        case "executeUpdate" -> store.run(sql, orderedParams()).changed();
                        case "executeQuery" -> newResultSet(store.run(sql, orderedParams()).rows());
                        default -> defaultValue(m);
                    };
                }

                private List<Object> orderedParams() {
                    List<Object> out = new ArrayList<>();
                    for (int i = 1; params.containsKey(i); i++) {
                        out.add(params.get(i));
                    }
                    return out;
                }
            }
        }
    }

    /** 结果集替身：只覆盖 JdbcTemplate 用到的游标与列遍历。 */
    private static final class FakeResultSet implements InvocationHandler {
        private final List<String> columns;
        private final List<Object[]> rows;
        private int cursor = -1;

        FakeResultSet(List<Map<String, Object>> data) {
            Set<String> keys = new LinkedHashSet<>();
            for (Map<String, Object> row : data) {
                keys.addAll(row.keySet());
            }
            this.columns = new ArrayList<>(keys);
            this.rows = new ArrayList<>();
            for (Map<String, Object> row : data) {
                Object[] values = new Object[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    values[i] = row.get(columns.get(i));
                }
                rows.add(values);
            }
        }

        @Override
        public Object invoke(Object p, Method m, Object[] args) {
            return switch (m.getName()) {
                case "next" -> ++cursor < rows.size();
                case "getMetaData" -> proxy(ResultSetMetaData.class, (mp, mm, ma) -> switch (mm.getName()) {
                    case "getColumnCount" -> columns.size();
                    case "getColumnLabel", "getColumnName" -> columns.get((Integer) ma[0] - 1);
                    default -> defaultValue(mm);
                });
                case "getObject" -> {
                    int index = args[0] instanceof Integer idx
                            ? idx - 1
                            : columns.indexOf(String.valueOf(args[0]));
                    yield rows.get(cursor)[index];
                }
                default -> defaultValue(m);
            };
        }
    }

    private static ResultSet newResultSet(List<Map<String, Object>> data) {
        return proxy(ResultSet.class, new FakeResultSet(data));
    }

    // ---------------------------------------------------------------- 内存库

    /**
     * platform.baseline / platform.baseline_member 的内存替身。
     * SQL 语义按 {@link BaselineService} 实际用到的谓词实现，覆盖不到的语句**直接抛错**
     * （静默返回空集会让测试假绿）。{@code failOnNthMemberInsert} 是失败注入口。
     */
    private static final class FakeStore {

        private static final Pattern MEMBER_INSERT = Pattern.compile(
                "insert into platform\\.baseline_member\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
        private static final Pattern BASELINE_INSERT = Pattern.compile(
                "insert into platform\\.baseline\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

        final Map<Long, Map<String, Object>> baselines = new LinkedHashMap<>();
        final List<long[]> members = new ArrayList<>();
        final Set<Long> validWorkflows = new LinkedHashSet<>(List.of(101L, 102L, 103L));
        int failOnNthMemberInsert;
        private int memberInserts;
        private long nextBaselineId = 1000L;

        /** 结果：写语句带影响行数，读语句带行集。 */
        private record Outcome(int changed, List<Map<String, Object>> rows) {
            static Outcome update(int changed) {
                return new Outcome(changed, List.of());
            }

            static Outcome rows(List<Map<String, Object>> rows) {
                return new Outcome(0, rows);
            }
        }

        void seedBaseline(long id, String tenant, String name) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("tenant_id", tenant);
            row.put("project_id", 9L);
            row.put("name", name);
            row.put("deadline_time", "23:00");
            row.put("warn_before_min", 30);
            row.put("level", "P2");
            row.put("owner", "alice");
            row.put("enabled", true);
            row.put("project_code", "demo");
            row.put("project_name", "演示项目");
            baselines.put(id, row);
        }

        void seedMember(long baselineId, long workflowId) {
            members.add(new long[]{baselineId, workflowId});
        }

        List<Long> visibleMembers(long baselineId) {
            return members.stream().filter(m -> m[0] == baselineId)
                    .map(m -> m[1]).sorted().toList();
        }

        String baselineName(long id) {
            return String.valueOf(baselines.get(id).get("name"));
        }

        List<Long> baselineIds() {
            return new ArrayList<>(baselines.keySet());
        }

        // -------------------------------------------------- 事务快照

        Object[] snapshot() {
            Map<Long, Map<String, Object>> copy = new LinkedHashMap<>();
            baselines.forEach((id, row) -> copy.put(id, new LinkedHashMap<>(row)));
            List<long[]> memberCopy = new ArrayList<>();
            for (long[] pair : members) {
                memberCopy.add(pair.clone());
            }
            return new Object[]{copy, memberCopy, memberInserts, nextBaselineId};
        }

        @SuppressWarnings("unchecked")
        void restore(Object[] snapshot) {
            baselines.clear();
            baselines.putAll((Map<Long, Map<String, Object>>) snapshot[0]);
            members.clear();
            for (long[] pair : (List<long[]>) snapshot[1]) {
                members.add(pair.clone());
            }
            memberInserts = (Integer) snapshot[2];
            nextBaselineId = (Long) snapshot[3];
        }

        // -------------------------------------------------- 语句执行

        Outcome run(String sql, List<Object> argList) {
            Object[] args = argList.toArray();
            String lower = sql.toLowerCase(Locale.ROOT);
            if (lower.startsWith("update ")) {
                return Outcome.update(applyUpdate(lower, args));
            }
            if (lower.startsWith("insert ")) {
                return applyInsert(lower, args);
            }
            if (lower.startsWith("delete ")) {
                return Outcome.update(applyDelete(lower, args));
            }
            if (lower.startsWith("select ")) {
                return Outcome.rows(select(lower, args));
            }
            throw new IllegalStateException("D33 假 JDBC 未覆盖的语句: " + sql);
        }

        private int applyUpdate(String lower, Object[] args) {
            if (!lower.startsWith("update platform.baseline")) {
                throw new IllegalStateException("D33 假 JDBC 未覆盖的 UPDATE: " + lower);
            }
            String setClause = lower.substring(lower.indexOf("set ") + 4, lower.indexOf(" where"));
            String where = lower.substring(lower.indexOf(" where"));
            int cursor = 0;
            Map<String, Object> values = new LinkedHashMap<>();
            boolean bumpUpdatedAt = false;
            for (String assign : setClause.split(",")) {
                String column = assign.substring(0, assign.indexOf('=')).trim();
                String rhs = assign.substring(assign.indexOf('=') + 1).trim();
                if (rhs.equals("now()")) {
                    bumpUpdatedAt = true;   // 测试不校验时间戳真值
                    continue;
                }
                if (rhs.startsWith("?")) {  // ? 或 ?::time
                    values.put(column, args[cursor++]);
                } else {
                    values.put(column, rhs.replaceAll("^'|'$", ""));
                }
            }
            boolean idBound = where.contains("id = ?");
            boolean tenantBound = where.contains("tenant_id = ?");
            long idArg = idBound ? ((Number) args[cursor++]).longValue() : -1;
            String tenantArg = tenantBound ? String.valueOf(args[cursor++]) : null;
            int changed = 0;
            for (Map<String, Object> row : baselines.values()) {
                if (idBound && ((Number) row.get("id")).longValue() != idArg) {
                    continue;
                }
                if (tenantBound && !String.valueOf(row.get("tenant_id")).equals(tenantArg)) {
                    continue;
                }
                row.putAll(values);
                if (bumpUpdatedAt) {
                    row.put("updated_at", "now()");
                }
                changed++;
            }
            return changed;
        }

        private Outcome applyInsert(String lower, Object[] args) {
            if (lower.contains("platform.baseline_member")) {
                insertMember(lower, args);
                return Outcome.update(1);
            }
            if (BASELINE_INSERT.matcher(lower).find()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", nextBaselineId++);
                row.put("tenant_id", args[0]);
                row.put("project_id", args[1]);
                row.put("name", args[2]);
                row.put("deadline_time", args[3]);
                row.put("warn_before_min", args[4]);
                row.put("level", args[5]);
                row.put("owner", args[6]);
                row.put("enabled", args[7]);
                row.put("project_code", "demo");
                row.put("project_name", "演示项目");
                baselines.put((Long) row.get("id"), row);
                if (lower.contains("returning id")) {   // create 用 insert ... returning id
                    return Outcome.rows(List.of(Map.of("id", row.get("id"))));
                }
                return Outcome.update(1);
            }
            throw new IllegalStateException("D33 假 JDBC 未覆盖的 INSERT: " + lower);
        }

        private void insertMember(String lower, Object[] args) {
            Matcher matcher = MEMBER_INSERT.matcher(lower);
            if (!matcher.find()) {
                throw new IllegalStateException("D33 假 JDBC 解析不到成员 INSERT 列名: " + lower);
            }
            String[] columns = matcher.group(1).split(",");
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < columns.length; i++) {
                values.put(columns[i].trim(), args[i]);
            }
            memberInserts++;
            if (memberInserts == failOnNthMemberInsert) {
                throw new RuntimeException("D33 注入故障：第 " + memberInserts
                        + " 条成员 INSERT 失败（模拟约束冲突/连接中断）");
            }
            long baselineId = ((Number) values.get("baseline_id")).longValue();
            long workflowId = ((Number) values.get("workflow_id")).longValue();
            boolean exists = members.stream().anyMatch(
                    m -> m[0] == baselineId && m[1] == workflowId);
            if (!exists) {   // on conflict do nothing
                members.add(new long[]{baselineId, workflowId});
            }
        }

        private int applyDelete(String lower, Object[] args) {
            if (lower.contains("platform.baseline_member")) {
                long baselineId = ((Number) args[0]).longValue();
                int before = members.size();
                members.removeIf(m -> m[0] == baselineId);
                return before - members.size();
            }
            if (lower.contains("from platform.baseline")) {
                long id = ((Number) args[0]).longValue();
                String tenant = args.length > 1 ? String.valueOf(args[1]) : null;
                Map<String, Object> row = baselines.get(id);
                if (row == null
                        || (tenant != null && !String.valueOf(row.get("tenant_id")).equals(tenant))) {
                    return 0;
                }
                baselines.remove(id);
                members.removeIf(m -> m[0] == id);   // 真实库 on delete cascade（V107）
                return 1;
            }
            throw new IllegalStateException("D33 假 JDBC 未覆盖的 DELETE: " + lower);
        }

        private List<Map<String, Object>> select(String lower, Object[] args) {
            if (lower.contains("from platform.baseline_member m")
                    && lower.contains("join platform.workflow")) {
                // predict()：取基线成员对应的工作流（model 为空 → 按单任务估算）
                long baselineId = ((Number) args[0]).longValue();
                List<Map<String, Object>> out = new ArrayList<>();
                for (long[] pair : visibleMembersOf(baselineId)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", pair[1]);
                    row.put("code", "wf_" + pair[1]);
                    row.put("name", "工作流 " + pair[1]);
                    row.put("model", null);
                    out.add(row);
                }
                return out;
            }
            if (lower.contains("from platform.baseline_member")) {
                long baselineId = ((Number) args[0]).longValue();
                List<Map<String, Object>> out = new ArrayList<>();
                for (long[] pair : visibleMembersOf(baselineId)) {
                    out.add(Map.of("workflow_id", pair[1]));
                }
                return out;
            }
            if (lower.contains("from platform.workflow")) {
                // validateMembers：只认属于本项目/租户且存在的工作流
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object arg : args) {
                    if (arg instanceof Number n && validWorkflows.contains(n.longValue())) {
                        out.add(Map.of("id", n.longValue()));
                    }
                }
                return out;
            }
            if (lower.contains("from platform.project")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", args.length > 0 ? args[0] : 9L);
                row.put("code", "demo");
                return List.of(row);
            }
            if (lower.contains("from platform.wf_instance")
                    || lower.contains("from platform.task_instance")) {
                return List.of();   // 今日未运行：预测走「按完整关键路径估算」分支
            }
            if (lower.contains("from platform.baseline")) {
                boolean idBound = lower.contains("b.id = ?");
                long idFilter = idBound ? ((Number) args[0]).longValue() : -1;
                boolean tenantBound = lower.contains("b.tenant_id = ?")
                        || lower.matches("(?s).*where tenant_id = \\?.*");
                String tenant = tenantBound
                        ? String.valueOf(args[idBound ? 1 : 0]) : null;
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : baselines.values()) {
                    if (idBound && ((Number) row.get("id")).longValue() != idFilter) {
                        continue;
                    }
                    if (tenantBound
                            && !String.valueOf(row.get("tenant_id")).equals(tenant)) {
                        continue;
                    }
                    out.add(new LinkedHashMap<>(row));
                }
                return out;
            }
            throw new IllegalStateException("D33 假 JDBC 未覆盖的查询: " + lower);
        }

        private List<long[]> visibleMembersOf(long baselineId) {
            List<long[]> out = new ArrayList<>();
            for (long[] pair : members) {
                if (pair[0] == baselineId) {
                    out.add(pair);
                }
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- 工具

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static Object defaultValue(Method m) {
        Class<?> type = m.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
