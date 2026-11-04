package com.lab.dataworks.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V200 两表的 Jdbc 实现（REQ-FW-4）。
 *
 * <p>事件写失败不伤主流程（{@link JdbcMosFlywheelDao} 同构口径）；其余方法把异常
 * 原样抛给编排器——那里落 last_error + ERROR 事件，下一圈重试，不静默。</p>
 */
@Repository
public class JdbcFlywheelRunDao implements FlywheelRunDao {

    private static final Logger log = LoggerFactory.getLogger(JdbcFlywheelRunDao.class);
    private static final int LIST_LIMIT = 200;

    private static final String RUN_COLUMNS = """
            id, tenant_id as "tenantId", project_id as "projectId",
            parent_run_id as "parentRunId", status, current_step as "currentStep",
            gate_wait as "gateWait", spec::text as "specJson", ds_id as "dsId",
            dataset_version as "datasetVersion", job_ext_id as "jobExtId",
            eval_ext_id as "evalExtId", model_ext_id as "modelExtId", model_name as "modelName",
            deploy_ref as "deployRef",
            (extract(epoch from deploy_ts) * 1000)::bigint as "deployTsMs",
            relabel_batch_id as "relabelBatchId", relabel_task_ref as "relabelTaskRef",
            child_run_id as "childRunId", last_error as "lastError", created_by as "createdBy",
            (extract(epoch from created_at) * 1000)::bigint as "createdMs"
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcFlywheelRunDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insertRun(String tenantId, Long projectId, Long parentRunId,
                          String specJson, String createdBy) {
        Long id = jdbc.queryForObject("""
                insert into platform.flywheel_run
                    (tenant_id, project_id, parent_run_id, status, current_step, spec, created_by)
                values (?, ?, ?, 'RUNNING', 'TABLE', ?::json, ?)
                returning id
                """, Long.class, tenantId, projectId, parentRunId, specJson, createdBy);
        if (id == null) {
            throw new IllegalStateException("flywheel_run 插入没有返回 id");
        }
        return id;
    }

    @Override
    public Run findRun(long runId, String tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select " + RUN_COLUMNS + " from platform.flywheel_run where id = ? and tenant_id = ?",
                runId, tenantId);
        return rows.isEmpty() ? null : toRun(rows.get(0));
    }

    @Override
    public List<Run> openRuns() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select " + RUN_COLUMNS + " from platform.flywheel_run where status = 'RUNNING' order by id");
        return rows.stream().map(JdbcFlywheelRunDao::toRun).toList();
    }

    @Override
    public void updateRun(long runId, RunPatch patch) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(sets, args, "ds_id", patch.dsId);
        add(sets, args, "dataset_version", patch.datasetVersion);
        add(sets, args, "job_ext_id", patch.jobExtId);
        add(sets, args, "eval_ext_id", patch.evalExtId);
        add(sets, args, "model_ext_id", patch.modelExtId);
        add(sets, args, "model_name", patch.modelName);
        add(sets, args, "deploy_ref", patch.deployRef);
        add(sets, args, "relabel_task_ref", patch.relabelTaskRef);
        if (patch.deployTs != null) {
            sets.add("deploy_ts = ?");
            args.add(Timestamp.from(patch.deployTs));
        }
        if (patch.relabelBatchId != null) {
            sets.add("relabel_batch_id = ?");
            args.add(patch.relabelBatchId);
        }
        if (patch.childRunId != null) {
            sets.add("child_run_id = ?");
            args.add(patch.childRunId);
        }
        if (patch.lastError != null) {
            sets.add("last_error = ?");
            args.add(abbreviate(patch.lastError));
        }
        if (sets.isEmpty()) {
            return;
        }
        sets.add("updated_at = now()");
        args.add(runId);
        jdbc.update("update platform.flywheel_run set " + String.join(", ", sets)
                + " where id = ?", args.toArray());
    }

    private static void add(List<String> sets, List<Object> args, String column, String value) {
        if (value != null) {
            sets.add(column + " = ?");
            args.add(value);
        }
    }

    @Override
    public void transition(long runId, String status, String step, String gateWait, String lastError) {
        jdbc.update("""
                update platform.flywheel_run
                set status = ?, current_step = ?, gate_wait = ?, last_error = ?, updated_at = now(),
                    finished_at = case when ? in ('CLOSED','FAILED') then now() else finished_at end
                where id = ?
                """, status, step, gateWait, lastError == null ? null : abbreviate(lastError),
                status, runId);
    }

    @Override
    public void emit(long runId, String step, String type, String message, Map<String, Object> metrics) {
        try {
            jdbc.update("""
                    insert into platform.flywheel_run_event (run_id, step, type, message, metrics)
                    values (?, ?, ?, ?, ?::json)
                    """, runId, step, type == null ? "INFO" : type, message,
                    metrics == null || metrics.isEmpty() ? null : json.writeValueAsString(metrics));
        } catch (Exception e) {
            log.warn("emit flywheel run event failed run={} step={}: {}", runId, step, e.toString());
        }
    }

    @Override
    public List<Map<String, Object>> events(long runId, String tenantId) {
        return jdbc.queryForList("""
                select e.id, e.step, e.type, e.message, e.metrics::text as "metrics",
                       (extract(epoch from e.ts) * 1000)::bigint as "tsMs"
                from platform.flywheel_run_event e
                join platform.flywheel_run r on r.id = e.run_id
                where e.run_id = ? and r.tenant_id = ?
                order by e.id
                """, runId, tenantId);
    }

    @Override
    public List<Map<String, Object>> listRuns(String tenantId, boolean openOnly, int limit) {
        String where = openOnly ? " and status in ('RUNNING','WAIT_GATE')" : "";
        int cap = Math.min(Math.max(1, limit), LIST_LIMIT);
        return jdbc.queryForList("select " + RUN_COLUMNS + """
                from platform.flywheel_run
                where tenant_id = ?
                """ + where + " order by id desc limit " + cap, tenantId);
    }

    @Override
    public int countBadcaseSince(String tenantId, Instant since) {
        Integer n = jdbc.queryForObject("""
                select count(*) from platform.badcase_pool
                where tenant_id = ? and created_at >= ?
                """, Integer.class, tenantId, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    @Override
    public RelabelBatch findRelabelBatchSince(String tenantId, Instant since) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, ledger_key as "ledgerKey", status, mos_task_ref as "mosTaskRef",
                       item_count as "itemCount"
                from platform.flywheel_relabel_batch
                where tenant_id = ? and created_at >= ?
                order by id desc limit 1
                """, tenantId, Timestamp.from(since));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        return new RelabelBatch(((Number) r.get("id")).longValue(), (String) r.get("ledgerKey"),
                (String) r.get("status"), (String) r.get("mosTaskRef"),
                ((Number) r.get("itemCount")).intValue());
    }

    private static Run toRun(Map<String, Object> r) {
        return new Run(((Number) r.get("id")).longValue(), (String) r.get("tenantId"),
                num(r.get("projectId")), num(r.get("parentRunId")), (String) r.get("status"),
                (String) r.get("currentStep"), (String) r.get("gateWait"), (String) r.get("specJson"),
                (String) r.get("dsId"), (String) r.get("datasetVersion"), (String) r.get("jobExtId"),
                (String) r.get("evalExtId"), (String) r.get("modelExtId"), (String) r.get("modelName"),
                (String) r.get("deployRef"),
                r.get("deployTsMs") == null ? null : Instant.ofEpochMilli(((Number) r.get("deployTsMs")).longValue()),
                num(r.get("relabelBatchId")), (String) r.get("relabelTaskRef"), num(r.get("childRunId")),
                (String) r.get("lastError"), (String) r.get("createdBy"),
                r.get("createdMs") == null ? null : Instant.ofEpochMilli(((Number) r.get("createdMs")).longValue()));
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static String abbreviate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
