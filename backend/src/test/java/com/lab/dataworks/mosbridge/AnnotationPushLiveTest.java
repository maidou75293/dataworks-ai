package com.lab.dataworks.mosbridge;

import com.lab.dataworks.query.DorisQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-FW-1 · 卡面验收「一次真推（小表样数据 10 行）」的可重复用例。
 *
 * <p>开关：环境变量 {@code FW1_LIVE=1} 才执行（缺省 SKIP，不炸常规构建——凭据边界：
 * 口令由运行者从环境注入，代码/仓内不存）。所需环境（开发态直连，卡面口径）：</p>
 * <pre>
 *   FW1_LIVE=1
 *   MOS_BASE_URL=http://127.0.0.1:8090          # 开发态 mos（上线后改走 UNIFY-1 桥接，键不变）
 *   MOS_USER / MOS_PASS                          # mos 服务账号（Worker 不内置口令）
 *   FW1_LEDGER_URL=... FW1_LEDGER_USER=... FW1_LEDGER_PASS=...   # 中台台账库（V160 已应用）
 * </pre>
 * <p>跑法见 logs/fw1-live-*.log 头部；证据三行（items 计数=10 / 台账有行 / 再推计数不变）打
 * {@code [REQ-FW-1][EVIDENCE]} 前缀。</p>
 */
@EnabledIfEnvironmentVariable(named = "FW1_LIVE", matches = "1")
class AnnotationPushLiveTest {

    @Test
    void realPushTenRowsIsIdempotentAtSameVersion() {
        String baseUrl = require("MOS_BASE_URL");
        String mosUser = require("MOS_USER");
        String mosPass = require("MOS_PASS");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                require("FW1_LEDGER_URL"), require("FW1_LEDGER_USER"), require("FW1_LEDGER_PASS")));

        AnnotationPushService service =
                new AnnotationPushService(baseUrl, mosUser, mosPass, 20000, jdbc);

        String name = "REQ-FW1-中台真推-" + System.currentTimeMillis();
        String dsId = service.createDataset(name);
        assertThat(dsId).as("对方主键是字符串 ds_xxx（契约 §10 坑 2）").startsWith("ds_");

        List<String> columns = List.of("question", "answer", "row_key");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rows.add(List.of("中台真推问题" + i, "中台样例答案" + i, "row-" + i));
        }
        DorisQueryService.Result tenRows =
                new DorisQueryService.Result(columns, rows, rows.size(), 10, 1L, "live");

        AnnotationPushService.PushReport first =
                service.push("live", "fw1-test", "10 行小表样数据（live 用例构造）",
                        dsId, tenRows, "question", "answer", "row_key");
        assertThat(first.inserted()).as("首推 10 行应全部入库").isEqualTo(10);

        int items = service.itemCount(dsId);
        Integer ledgerRows = jdbc.queryForObject(
                "SELECT count(*) FROM platform.annotation_push_ledger WHERE ds_id = ?",
                Integer.class, dsId);
        String version = jdbc.queryForObject(
                "SELECT version FROM platform.annotation_push_ledger WHERE ds_id = ?",
                String.class, dsId);

        // —— 卡面三行证据 ——
        System.out.println("[REQ-FW-1][EVIDENCE] mos items 计数=" + items + "（ds_id=" + dsId + "）");
        System.out.println("[REQ-FW-1][EVIDENCE] 台账有行：version='" + version + "' 行数=" + ledgerRows);

        assertThat(items).as("mos 侧 items 计数=10（卡面验收 1）").isEqualTo(10);
        assertThat(ledgerRows).as("台账有行（卡面验收 2）").isEqualTo(1);

        AnnotationPushService.PushReport second =
                service.push("live", "fw1-test", "10 行小表样数据（同版本重推）",
                        dsId, tenRows, "question", "answer", "row_key");
        int itemsAfter = service.itemCount(dsId);
        Integer ledgerRowsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM platform.annotation_push_ledger WHERE ds_id = ?",
                Integer.class, dsId);
        System.out.println("[REQ-FW-1][EVIDENCE] 再推同版本：inserted=" + second.inserted()
                + " duplicated=" + second.duplicated()
                + " items=" + itemsAfter + "（不变） 台账行数=" + ledgerRowsAfter + "（不增）");

        assertThat(second.inserted()).as("幂等：再推 0 新增").isZero();
        assertThat(second.duplicated()).as("幂等：10 条全判重").isEqualTo(10);
        assertThat(itemsAfter).as("幂等：mos 侧计数不变").isEqualTo(10);
        assertThat(ledgerRowsAfter).as("幂等：台账同版本覆盖不增行").isEqualTo(1);
        System.out.println("[REQ-FW-1][EVIDENCE] dataset 保留可查：" + dsId + "（" + name + "）");
    }

    private static String require(String env) {
        String v = System.getenv(env);
        assertThat(v).as("缺少环境变量 " + env).isNotBlank();
        return v;
    }
}
