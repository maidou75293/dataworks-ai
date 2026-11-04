package com.lab.dataworks.workflow;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.prefect.PrefectPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 工作流写路径的唯一入口：版本分配、发布、删除、项目删除都从这里拿锁。
 *
 * <p>以前每个 Controller 各写一套：发布只锁自己、项目删除完全不清理 Prefect、
 * 删除工作流逐版本重启 worker。根因是「数据库 / Prefect / ConfigMap 三方资产」
 * 没有统一生命周期。集中到这一个服务后，所有入口遵守同一套顺序：
 * 拿锁 → 清理外部资产 → 写库。</p>
 *
 * <p>后端当前是单副本，进程内锁足够；多副本部署时需要换成数据库 advisory lock。</p>
 */
@Service
public class WorkflowMutationService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMutationService.class);

    private final JdbcTemplate jdbc;
    private final PrefectPublisher publisher;
    private final TransactionTemplate transactions;
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Object> projectLocks = new ConcurrentHashMap<>();

    public WorkflowMutationService(JdbcTemplate jdbc, PrefectPublisher publisher,
                                   TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.transactions = transactions;
    }

    public <T> T withLock(long workflowId, Supplier<T> action) {
        synchronized (locks.computeIfAbsent(workflowId, k -> new Object())) {
            return action.get();
        }
    }

    /**
     * 同一工作流 + 同一业务日期是否已有未结束的实例。
     *
     * <p>触发/补数（WorkflowController）与重跑（OpsController）必须用同一份判重 SQL，
     * 并且都放在 {@link #withLock(long, Supplier)} 里：两边各自「查冲突 → 调 Prefect → 落库」时，
     * 并发请求会都查到空闲，同一业务日期起两个 run 同时写同一批目标表。</p>
     *
     * <p>参数口径保留各入口原来的容忍度：{@code deploymentId} 为空表示不限部署
     * （重跑按工作流判重），非空只认该部署（触发按 deployment 判重，DEV/PROD 可并行）；
     * {@code freshMinutes} 只认最近同步过的行 —— Prefect 里早就不存在的僵尸 queued
     * 不能永久占住业务日期。{@code excludeInstanceId <= 0} 表示不排除任何行。</p>
     */
    public Map<String, Object> activeRun(long workflowId, String bizDate, String deploymentId,
                                         long excludeInstanceId, int freshMinutes) {
        StringBuilder sql = new StringBuilder("""
                select id, state from platform.wf_instance
                 where workflow_id = ? and biz_date is not distinct from ?::date
                   and lower(state) in ('queued', 'running', 'pending', 'scheduled', 'late')
                   and updated_at > now() - ?::interval
                """);
        List<Object> args = new ArrayList<>();
        args.add(workflowId);
        args.add(bizDate);
        args.add(freshMinutes + " minutes");
        if (excludeInstanceId > 0) {
            sql.append(" and id <> ?");
            args.add(excludeInstanceId);
        }
        if (deploymentId != null && !deploymentId.isBlank()) {
            sql.append(" and dag_id = ?");
            args.add(deploymentId);
        }
        sql.append(" order by created_at desc limit 1");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 项目级锁：删除项目与新建工作流互斥，避免「刚建好就被级联删掉」。 */
    public <T> T withProjectLock(long projectId, Supplier<T> action) {
        synchronized (projectLocks.computeIfAbsent(projectId, k -> new Object())) {
            return action.get();
        }
    }

    /**
     * 版本号分配必须在锁内完成：max+1 不是原子操作，并发保存会撞唯一约束。
     * 重试保留给未来多副本部署。
     */
    public int nextVersion(long workflowId, String modelJson, String username) {
        return withLock(workflowId, () -> {
            for (int attempt = 1; attempt <= 5; attempt++) {
                int next = jdbc.queryForObject(
                        "select coalesce(max(version_no), 0) + 1 from platform.workflow_version where workflow_id = ?",
                        Integer.class, workflowId);
                try {
                    jdbc.update("""
                            insert into platform.workflow_version
                                (workflow_id, version_no, model_json, status, created_by, tenant_id)
                            values (?, ?, ?::jsonb, 'DRAFT', ?,
                                    (select tenant_id from platform.workflow where id = ?))
                            """, workflowId, next, modelJson, username, workflowId);
                    return next;
                } catch (DuplicateKeyException e) {
                    log.warn("version race workflow={} attempt={} version={}", workflowId, attempt, next);
                }
            }
            throw ApiException.conflict("版本号分配冲突，请稍后重试");
        });
    }

    /** 删除工作流：锁内清理 Prefect 部署/flow 文件，再级联删库。 */
    public void deleteWorkflow(long workflowId) {
        withLock(workflowId, () -> {
            // 租户条件不能省：v447 前这里只按主键判断，跨租户删除会真实生效。
            if (jdbc.queryForObject("""
                    select count(*) from platform.workflow where id = ? and tenant_id = ?
                    """, Integer.class, workflowId,
                    com.lab.dataworks.auth.TenantScope.current()) == 0) {
                throw ApiException.notFound("工作流不存在: " + workflowId);
            }
            publisher.deleteAll(assetsOfWorkflow(workflowId));
            jdbc.update("delete from platform.workflow where id = ?", workflowId);
            return null;
        });
    }

    /**
     * 删除项目：按 id 升序拿所有子工作流的锁（避免与并发发布互相踩），
     * 再清 Prefect 资产、删项目（workflow / schedule / lineage / 实例由外键级联）。
     */
    public void deleteProject(long projectId) {
        withProjectLock(projectId, () -> {
            // 快照必须在项目锁内取：否则并发新建的工作流不在快照里，
            // 项目删掉后它刚创建的部署会成为孤儿。
            List<Long> workflowIds = jdbc.queryForList(
                    "select id from platform.workflow where project_id = ? order by id",
                    Long.class, projectId);
            withLocks(workflowIds, 0, () -> {
                List<PrefectPublisher.FlowAsset> assets = assetsOfProject(projectId);
                // data_service.datasource_id 是 ON DELETE RESTRICT，而 datasource
                // 又随项目级联删除；不先删服务，项目删除必然被外键拒绝。
                transactions.executeWithoutResult(status -> {
                    jdbc.update("delete from platform.data_service where project_id = ?", projectId);
                    jdbc.update("delete from platform.project where id = ?", projectId);
                });
                // DB 是提交点：确认删除成功后再清理外部资产。外部清理失败只留下
                // 孤儿部署（可重试清理），不会出现「项目还在但调度已被删」。
                publisher.deleteAll(assets);
                return null;
            });
            return null;
        });
    }

    private void withLocks(List<Long> ids, int index, Supplier<Void> action) {
        if (index >= ids.size()) {
            action.get();
            return;
        }
        synchronized (locks.computeIfAbsent(ids.get(index), k -> new Object())) {
            withLocks(ids, index + 1, action);
        }
    }

    /**
     * 版本行上的指针是历史兼容，deployment 表才是每个环境当前的部署；
     * 两边都要清，否则旧环境的部署/Flow 文件会成为孤儿。
     */
    private List<PrefectPublisher.FlowAsset> assetsOfWorkflow(long workflowId) {
        List<PrefectPublisher.FlowAsset> assets = new java.util.ArrayList<>();
        assets.addAll(jdbc.queryForList("""
                        select prefect_deployment_id, prefect_flow_file
                        from platform.workflow_version where workflow_id = ?
                        """, workflowId).stream().map(this::asset).toList());
        assets.addAll(jdbc.queryForList("""
                        select prefect_deployment_id, prefect_flow_file
                        from platform.workflow_deployment where workflow_id = ?
                        """, workflowId).stream().map(this::asset).toList());
        return assets.stream()
                .filter(a -> (a.filename() != null && !a.filename().isBlank())
                        || (a.deploymentId() != null && !a.deploymentId().isBlank()))
                .distinct()
                .toList();
    }

    private List<PrefectPublisher.FlowAsset> assetsOfProject(long projectId) {
        List<PrefectPublisher.FlowAsset> assets = new java.util.ArrayList<>();
        assets.addAll(jdbc.queryForList("""
                        select v.prefect_deployment_id, v.prefect_flow_file
                        from platform.workflow_version v
                        join platform.workflow w on w.id = v.workflow_id
                        where w.project_id = ?
                        """, projectId).stream().map(this::asset).toList());
        assets.addAll(jdbc.queryForList("""
                        select d.prefect_deployment_id, d.prefect_flow_file
                        from platform.workflow_deployment d
                        join platform.workflow w on w.id = d.workflow_id
                        where w.project_id = ?
                        """, projectId).stream().map(this::asset).toList());
        return assets.stream()
                .filter(a -> (a.filename() != null && !a.filename().isBlank())
                        || (a.deploymentId() != null && !a.deploymentId().isBlank()))
                .distinct()
                .toList();
    }

    private PrefectPublisher.FlowAsset asset(java.util.Map<String, Object> row) {
        return new PrefectPublisher.FlowAsset(
                row.get("prefect_flow_file") == null ? null : String.valueOf(row.get("prefect_flow_file")),
                row.get("prefect_deployment_id") == null ? null
                        : String.valueOf(row.get("prefect_deployment_id")));
    }
}
