package com.lab.dataworks.mosbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * V170 三张表的 Jdbc 实现。
 *
 * <p>写事件失败不伤主流程（与 integration/RunEventService 同构）；其余方法把异常如实抛给
 * 轮询器——那里会转成 markFailure + 事件，不静默。</p>
 */
@Repository
public class JdbcMosFlywheelDao implements MosFlywheelDao {

    private static final Logger log = LoggerFactory.getLogger(JdbcMosFlywheelDao.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcMosFlywheelDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WatchRow insertWatch(String kind, String extId, String payloadJson,
                                String tenantId, Long projectId, String createdBy) {
        List<Map<String, Object>> existing = findWatch(kind, extId);
        if (!existing.isEmpty()) {
            return rowToWatch(existing.get(0));
        }
        try {
            jdbc.update("""
                        insert into platform.mos_job_watch
                            (kind, ext_id, payload, tenant_id, project_id, created_by)
                        values (?, ?, ?::json, ?, ?, ?)
                        """, kind, extId, payloadJson, tenantId, projectId, createdBy);
        } catch (Exception e) {
            // 并发重复登记：unique (kind, ext_id) 撞了就读回原行，保持幂等
            List<Map<String, Object>> raced = findWatch(kind, extId);
            if (!raced.isEmpty()) {
                return rowToWatch(raced.get(0));
            }
            throw e;
        }
        List<Map<String, Object>> created = findWatch(kind, extId);
        return created.isEmpty() ? null : rowToWatch(created.get(0));
    }

    private List<Map<String, Object>> findWatch(String kind, String extId) {
        return jdbc.queryForList("""
                select id, kind, ext_id as "extId", status, attempts, payload::text as "payload",
                       tenant_id as "tenantId", project_id as "projectId", created_by as "createdBy"
                from platform.mos_job_watch where kind = ? and ext_id = ?
                """, kind, extId);
    }

    private static WatchRow rowToWatch(Map<String, Object> r) {
        Object attempts = r.get("attempts");
        Object projectId = r.get("projectId");
        return new WatchRow(((Number) r.get("id")).longValue(), (String) r.get("kind"),
                (String) r.get("extId"), (String) r.get("status"),
                attempts == null ? 0 : ((Number) attempts).intValue(),
                (String) r.get("payload"), (String) r.get("tenantId"),
                projectId == null ? null : ((Number) projectId).longValue(),
                (String) r.get("createdBy"));
    }

    @Override
    public List<WatchRow> dueWatches(int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, kind, ext_id as "extId", status, attempts, payload::text as "payload",
                       tenant_id as "tenantId", project_id as "projectId", created_by as "createdBy"
                from platform.mos_job_watch
                where terminal = false and next_poll_at <= now()
                order by next_poll_at
                limit ?
                """, limit);
        return rows.stream().map(JdbcMosFlywheelDao::rowToWatch).toList();
    }

    @Override
    public void scheduleNext(long watchId, String status, String lastSeen, Instant nextAt) {
        jdbc.update("""
                update platform.mos_job_watch
                set status = ?, last_seen_at = ?, attempts = 0, last_error = null,
                    next_poll_at = ?, updated_at = now()
                where id = ?
                """, status, lastSeen, Timestamp.from(nextAt), watchId);
    }

    @Override
    public void markFailure(long watchId, String error, Instant nextAt) {
        jdbc.update("""
                update platform.mos_job_watch
                set attempts = attempts + 1, last_error = ?, next_poll_at = ?, updated_at = now()
                where id = ?
                """, abbreviate(error), Timestamp.from(nextAt), watchId);
    }

    @Override
    public void markTerminal(long watchId, String status) {
        jdbc.update("""
                update platform.mos_job_watch
                set terminal = true, status = ?, next_poll_at = now(), updated_at = now()
                where id = ? and terminal = false
                """, status, watchId);
    }

    @Override
    public void emitEvent(long watchId, String extId, String phase, String type,
                          String message, Map<String, Object> metrics) {
        try {
            jdbc.update("""
                    insert into platform.mos_run_event (watch_id, ext_id, phase, type, message, metrics)
                    values (?, ?, ?, ?, ?, ?::json)
                    """, watchId, extId, phase, type == null ? "INFO" : type,
                    message, metrics == null || metrics.isEmpty()
                            ? null : json.writeValueAsString(metrics));
        } catch (Exception e) {
            log.warn("emit mos run event failed watch={} phase={}: {}", watchId, phase, e.getMessage());
        }
    }

    @Override
    public void upsertLedger(LedgerRow row) {
        jdbc.update("""
                insert into platform.mos_model_ledger
                    (ext_id, name, base, method, dataset_ref, job_ext_id, artifact_type,
                     manifest_digest, size_gb, mos_status, path_or_key, register_source,
                     tenant_id, project_id, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (ext_id) do update set
                    name = excluded.name,
                    base = excluded.base,
                    method = excluded.method,
                    dataset_ref = excluded.dataset_ref,
                    job_ext_id = excluded.job_ext_id,
                    artifact_type = excluded.artifact_type,
                    manifest_digest = excluded.manifest_digest,
                    size_gb = excluded.size_gb,
                    mos_status = excluded.mos_status,
                    path_or_key = excluded.path_or_key,
                    tenant_id = excluded.tenant_id,
                    project_id = excluded.project_id,
                    updated_at = now()
                """, row.extId(), row.name(), row.base(), row.method(), row.datasetRef(),
                row.jobExtId(), row.artifactType(), row.manifestDigest(), row.sizeGb(),
                row.mosStatus(), row.pathOrKey(), row.registerSource(),
                row.tenantId(), row.projectId(), row.createdBy());
    }

    @Override
    public boolean writeEvalScore(String extId, String evalExtId, Double score, String evalStatus) {
        return jdbc.update("""
                update platform.mos_model_ledger
                set eval_ext_id = ?, eval_score = ?, eval_status = ?, updated_at = now()
                where ext_id = ?
                """, evalExtId, score, evalStatus, extId) > 0;
    }

    @Override
    public String findLedgerExtIdByName(String name, String tenantId) {
        List<String> ids = jdbc.queryForList("""
                select ext_id from platform.mos_model_ledger
                where name = ? and tenant_id = ?
                order by first_seen_at desc limit 1
                """, String.class, name, tenantId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Override
    public List<Map<String, Object>> listLedger(String tenantId) {
        return jdbc.queryForList("""
                select ext_id as "extId", name, base, method, dataset_ref as "datasetRef",
                       job_ext_id as "jobExtId", artifact_type as "artifactType",
                       manifest_digest as "manifestDigest", size_gb as "sizeGb",
                       mos_status as "mosStatus", path_or_key as "pathOrKey",
                       register_source as "registerSource", eval_ext_id as "evalExtId",
                       eval_score as "evalScore", eval_status as "evalStatus",
                       tenant_id as "tenantId", project_id as "projectId",
                       created_by as "createdBy",
                       (extract(epoch from first_seen_at) * 1000)::bigint as "firstSeenMs"
                from platform.mos_model_ledger
                where tenant_id = ?
                order by first_seen_at desc
                """, tenantId);
    }

    @Override
    public List<Map<String, Object>> listWatches(String tenantId, boolean openOnly) {
        String where = openOnly ? " and terminal = false" : "";
        return jdbc.queryForList("""
                select id, kind, ext_id as "extId", status, terminal, last_seen_at as "lastSeenAt",
                       attempts, last_error as "lastError", payload::text as payload,
                       (extract(epoch from next_poll_at) * 1000)::bigint as "nextPollMs",
                       tenant_id as "tenantId", project_id as "projectId", created_by as "createdBy"
                from platform.mos_job_watch
                where tenant_id = ?
                """ + where + " order by id desc limit 200", tenantId);
    }

    @Override
    public List<Map<String, Object>> events(long watchId, String tenantId) {
        return jdbc.queryForList("""
                select e.id, e.ext_id as "extId", e.phase, e.type, e.message,
                       e.metrics::text as "metrics",
                       (extract(epoch from e.ts) * 1000)::bigint as "tsMs"
                from platform.mos_run_event e
                join platform.mos_job_watch w on w.id = e.watch_id
                where e.watch_id = ? and w.tenant_id = ?
                order by e.id
                """, watchId, tenantId);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
