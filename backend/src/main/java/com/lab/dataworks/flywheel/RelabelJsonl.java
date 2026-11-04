package com.lab.dataworks.flywheel;

import java.util.List;

/**
 * 批次 → mos 数据集载荷（REQ-FW-3③；口径修正=BUG-CHAIN-1）。
 *
 * <p>形态=<b>每行 {@code 问题<TAB>}</b> 的 TSV，答案留空=「待标注」——badcase 的意义就是
 * 没有可用答案，金标由标注员产出。为什么不是 {@code {"messages":[{"role":"user"}...]}}：
 * mos 侧 {@code _qa_of_obj}（{@code mos/platform/backend/api/datasets.py:966}）要求
 * q/a <b>双非空</b>才成对——「自动认 messages」系误记，只带 user 轮的 jsonl 必被
 * upload 400「没有解析到问答」（REQ-CHAIN-E2E-1 环6 现网实点，台账 last_error 原文）。
 * 现网探针（docs/evidence/chain1-probe-empty-a.txt）同时证明解析层吃尾随 Tab、
 * 入库闸拦空答案——mos 侧已按「TSV 尾随 Tab 行保留 + {@code allow_empty_a}（默认关）」
 * 收窄修复，本载荷与之配套。</p>
 *
 * <p>谁投递谁定型：HTTP/multipart 与桥接会话在 {@link MosRelabelGateway} 实现里
 * （FW-1 mosbridge 共享 MosClient），本包禁止出现第二份桥接 client。</p>
 */
final class RelabelJsonl {

    private RelabelJsonl() {
    }

    /**
     * 池内容 → TSV 载荷（一行一条；制表/换行是行形分隔符，问题文本里的净化成空格）。
     *
     * <p>空/纯空白问题<b>显式拒绝</b>——静默发过去会被 mos 解析丢行或按 empty 计数
     * 跳过，出环计数校验（{@link MosClientRelabelGateway}）只能整批炸错，报错点在
     * 池侧更早更准。以 <code>{</code> 开头的问题会撞 mos JSON 分支（datasets.py
     * 先认 {@code startswith("{")} 再看 Tab）被丢行——由出环计数校验炸出，不静默。</p>
     */
    static String poolTsv(List<String> questions) {
        StringBuilder sb = new StringBuilder();
        for (String q : questions) {
            String v = q == null ? "" : q.replaceAll("[\\t\\r\\n]+", " ").trim();
            if (v.isEmpty()) {
                throw new IllegalArgumentException("badcase 池行问题为空/纯空白，不可成出环载荷（禁静默丢行）");
            }
            sb.append(v).append('\t').append('\n');
        }
        return sb.toString();
    }

    /**
     * 任务描述：卡面要求「数据集=池内容+目标模型名写入任务描述」。
     * 台账链（ledgerKey）也进描述，mos 侧人工核对时能对回中台批次。
     */
    static String taskDescription(String ledgerKey, int itemCount, String targetModel) {
        String model = (targetModel == null || targetModel.isBlank()) ? "未配置" : targetModel.trim();
        return "badcase 回流重标注 " + ledgerKey + "：样本 " + itemCount + " 条（来源=低质采集池 badcase_pool），目标模型=" + model;
    }
}
