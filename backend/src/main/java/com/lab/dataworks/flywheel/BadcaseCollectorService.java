package com.lab.dataworks.flywheel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 采集段（REQ-FW-3①②）：从 {@code platform.usage_query_event} 按规则捞低质事件入
 * {@code platform.badcase_pool}，带去重。
 *
 * <p>三条规则（阈值走 {@link FlywheelSettings}，默认不做死）：
 * 无答案=result_rows=0；点踩=feedback='DOWN'；低置信度=confidence &lt; 阈值。
 * 信号列在 V180 之前不存在，老事件 question=NULL——这类行计入 skipped 并推进游标，
 * 不静默当 0 条处理；写入方（部署服务/助手回写）从 V180 起补齐字段才会真正命中。</p>
 *
 * <p>去重两层：同事件重放被 {@code uq_badcase_pool_event} 挡住；同租户同轮
 * （status='NEW' 未封批窗口）同 query 被 {@code uq_badcase_pool_open_query} 挡住——
 * 均为 {@code on conflict do nothing}，insert 返回 0 记 deduped。</p>
 */
@Service
public class BadcaseCollectorService {

    private static final Logger log = LoggerFactory.getLogger(BadcaseCollectorService.class);

    /** 单圈扫描上限；badcase 是低频旁路，超出留给下一圈，不做大事务。 */
    static final int SCAN_LIMIT = 1000;

    static final String SCAN_SQL = """
            select id, tenant_id, project_id, result_rows, feedback, confidence, answer_model, question
              from platform.usage_query_event
             where id > ?
               and (result_rows = 0 or lower(feedback) = 'down'
                    or (confidence is not null and confidence < ?))
             order by id
            """ + " limit " + SCAN_LIMIT; // BUG-VER-V555-1：text block 会吞行尾空格，limit 必须同行带显式空格拼接

    static final String INSERT_SQL = """
            insert into platform.badcase_pool
                (tenant_id, project_id, source_event_id, question, question_hash, signal, confidence, answer_model)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict do nothing""";

    private final JdbcTemplate jdbc;
    private final FlywheelSettings settings;

    public BadcaseCollectorService(JdbcTemplate jdbc, FlywheelSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** 采集结果统计（单测断言面；也进 info 日志）。 */
    record CollectStats(long scanned, int inserted, int deduped, int skippedNoQuestion, long cursor) {
    }

    public CollectStats collectOnce() {
        double threshold = settings.confidenceThreshold();
        long cursor = settings.cursorId();

        List<Map<String, Object>> rows = jdbc.queryForList(SCAN_SQL, cursor, threshold);
        long maxId = cursor;
        int inserted = 0, deduped = 0, skipped = 0;
        for (Map<String, Object> row : rows) {
            long id = num(row.get("id")).longValue();
            maxId = Math.max(maxId, id);
            String question = (String) row.get("question");
            if (QueryHasher.normalize(question) == null) {
                skipped++;
                continue;
            }
            String feedback = (String) row.get("feedback");
            if (!BadcaseRules.knownFeedback(feedback)) {
                log.warn("事件 {} 的 feedback 取值非法（{}），不参与判定；请核对回写方词表 DOWN/UP", id, feedback);
                continue;
            }
            Double confidence = row.get("confidence") == null ? null : num(row.get("confidence")).doubleValue();
            long resultRows = row.get("result_rows") == null ? 0L : num(row.get("result_rows")).longValue();
            BadcaseSignal signal = BadcaseRules.match(resultRows, feedback, confidence, threshold);
            if (signal == null) {
                continue; // SQL 粗过滤命中而精判不中（如 confidence 恰好压线），正常跳过
            }
            int n = jdbc.update(INSERT_SQL,
                    (String) row.get("tenant_id"),
                    row.get("project_id") == null ? null : num(row.get("project_id")).longValue(),
                    id,
                    question.trim(),
                    QueryHasher.hash(question),
                    signal.name(),
                    confidence,
                    (String) row.get("answer_model"));
            if (n > 0) {
                inserted++;
            } else {
                deduped++;
            }
        }
        if (maxId != cursor) {
            settings.setCursorId(maxId);
        }
        CollectStats stats = new CollectStats(rows.size(), inserted, deduped, skipped, maxId);
        if (rows.size() > 0) {
            log.info("badcase 采集一圈：扫描 {}，入池 {}，去重 {}，无问题文本跳过 {}，游标 → {}",
                    stats.scanned(), stats.inserted(), stats.deduped(), stats.skippedNoQuestion(), stats.cursor());
        }
        return stats;
    }

    /** numeric(6,4) 读出来是 BigDecimal——统一按 Number 收口（不许静默：类型不对就炸给日志）。 */
    private static Number num(Object v) {
        if (v instanceof Number n) {
            return n;
        }
        throw new IllegalStateException("usage_query_event 数值列出现非数字类型：" + v);
    }
}
