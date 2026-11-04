package com.lab.dataworks.flywheel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回环段（REQ-FW-3③）：池内同租户 NEW 样本达到批量阈值 N（默认 50，sys_config 可调）
 * → 封批写台账 {@code platform.flywheel_relabel_batch}（badcase→relabel 链）
 * → 经 {@link MosRelabelGateway} 在 mos 建标注任务。
 *
 * <p>桥接实现属于 FW-1 mosbridge（卡面红线：禁止复制第二份桥接 client）。当前构建没有
 * 该 bean 时批次停在 READY、每圈重试探测，不伪造成功；推送失败只记 last_error、
 * 状态仍 READY 等下一圈（旁路任务不能被一次网络抖动打成终态）。</p>
 *
 * <p>幂等：台账 ledger_key {@code badcase:<tenant>:<round>} 全局唯一——这只挡「重复封批」；
 * 「失败重试重复推送」由 {@link MosClientRelabelGateway} 的按名查回复用兜住
 * （BUG-CHAIN-1 根因 3：旧实现每圈重试每圈重造同名空集）。批次 PUSHED 后
 * 池行才置 PUSHED——同 query 于是允许进下一圈，FW-1 的 ds_id@version 台账接住版本推进。</p>
 */
@Service
public class RelabelTriggerService {

    private static final Logger log = LoggerFactory.getLogger(RelabelTriggerService.class);

    static final String READY_TENANTS_SQL = """
            select tenant_id, count(*) as new_count
              from platform.badcase_pool
             where status = 'NEW'
             group by tenant_id
            having count(*) >= ?""";

    static final String NEXT_ROUND_SQL = """
            select coalesce(max(round_no), 0) + 1 as next_round
              from platform.flywheel_relabel_batch
             where tenant_id = ?""";

    static final String NEW_ITEMS_SQL = """
            select id, question
              from platform.badcase_pool
             where tenant_id = ? and status = 'NEW'
             order by id""";

    static final String INSERT_BATCH_SQL = """
            insert into platform.flywheel_relabel_batch (tenant_id, ledger_key, round_no, item_count, status)
            values (?, ?, ?, ?, 'READY')
            returning id""";

    static final String MARK_BATCHED_SQL = """
            update platform.badcase_pool
               set status = 'BATCHED', batch_id = ?
             where tenant_id = ? and status = 'NEW'""";

    static final String PENDING_BATCHES_SQL = """
            select id, tenant_id, ledger_key, item_count
              from platform.flywheel_relabel_batch
             where status = 'READY'
             order by id""";

    static final String BATCH_ITEMS_SQL = """
            select question
              from platform.badcase_pool
             where batch_id = ?
             order by id""";

    static final String MARK_PUSHED_SQL = """
            update platform.flywheel_relabel_batch
               set status = 'PUSHED', mos_task_ref = ?, pushed_at = now(), last_error = null
             where id = ?""";

    static final String MARK_POOL_PUSHED_SQL = """
            update platform.badcase_pool
               set status = 'PUSHED'
             where batch_id = ? and status = 'BATCHED'""";

    static final String RECORD_ERROR_SQL = """
            update platform.flywheel_relabel_batch set last_error = ? where id = ?""";

    private final JdbcTemplate jdbc;
    private final FlywheelSettings settings;
    private final ObjectProvider<MosRelabelGateway> gatewayProvider;

    public RelabelTriggerService(JdbcTemplate jdbc, FlywheelSettings settings,
                                 ObjectProvider<MosRelabelGateway> gatewayProvider) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.gatewayProvider = gatewayProvider;
    }

    /**
     * 一圈回环：封批 + 推送所有可推的 READY 批次。
     *
     * @return 本圈新封批次数（不含推送动作数）
     */
    public int triggerOnce() {
        int batchSize = settings.batchSize();
        int created = 0;
        for (Map<String, Object> t : jdbc.queryForList(READY_TENANTS_SQL, batchSize)) {
            String tenant = (String) t.get("tenant_id");
            if (createBatch(tenant)) {
                created++;
            }
        }
        pushPending();
        return created;
    }

    private boolean createBatch(String tenant) {
        List<Map<String, Object>> items = jdbc.queryForList(NEW_ITEMS_SQL, tenant);
        if (items.isEmpty()) {
            return false;
        }
        List<Map<String, Object>> roundRow = jdbc.queryForList(NEXT_ROUND_SQL, tenant);
        long round = roundRow.isEmpty() ? 1L : toNumber(roundRow.get(0).get("next_round")).longValue();
        String ledgerKey = "badcase:" + tenant + ":" + round;
        Long batchId = jdbc.queryForObject(INSERT_BATCH_SQL, Long.class,
                tenant, ledgerKey, round, items.size());
        jdbc.update(MARK_BATCHED_SQL, batchId, tenant);
        log.info("badcase 封批：{}（{} 条，batch_id={}）", ledgerKey, items.size(), batchId);
        return true;
    }

    /**
     * 推送所有 READY 批次。synchronized：定时圈（FlywheelJob）与手动 {@code /api/flywheel/tick}
     * （manualPump）可并发进这里——「按名查回复用」的查-建两步在单 JVM 内必须串行，
     * 否则竞态仍会重造同名集（BUG-CHAIN-1）。多副本部署时需升级为 DB 咨询锁（现单 pod，见卡备注）。
     */
    private synchronized void pushPending() {
        List<Map<String, Object>> pending = jdbc.queryForList(PENDING_BATCHES_SQL);
        if (pending.isEmpty()) {
            return;
        }
        MosRelabelGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            log.info("badcase 回环：{} 个 READY 批次待推——本构建无 MosRelabelGateway 实现"
                    + "（桥接客户端归 FW-1 mosbridge），保持 READY 等下一圈", pending.size());
            return;
        }
        String targetModel = settings.targetModel();
        for (Map<String, Object> batch : pending) {
            long batchId = toNumber(batch.get("id")).longValue();
            String ledgerKey = (String) batch.get("ledger_key");
            int itemCount = toNumber(batch.get("item_count")).intValue();
            try {
                List<String> questions = new ArrayList<>();
                for (Map<String, Object> item : jdbc.queryForList(BATCH_ITEMS_SQL, batchId)) {
                    questions.add((String) item.get("question"));
                }
                String ref = gateway.createRelabelTask(ledgerKey,
                        RelabelJsonl.taskDescription(ledgerKey, itemCount, targetModel),
                        RelabelJsonl.poolTsv(questions));
                jdbc.update(MARK_PUSHED_SQL, ref, batchId);
                jdbc.update(MARK_POOL_PUSHED_SQL, batchId);
                log.info("badcase 回环成功：{} → mos 任务 {}", ledgerKey, ref);
            } catch (Exception e) {
                log.warn("badcase 批次推送失败，保持 READY 待重试：{}", ledgerKey, e);
                jdbc.update(RECORD_ERROR_SQL, truncate(e.toString()), batchId);
            }
        }
    }

    private static String truncate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    private static Number toNumber(Object v) {
        if (v instanceof Number n) {
            return n;
        }
        throw new IllegalStateException("数值列出现非数字类型：" + v);
    }
}
