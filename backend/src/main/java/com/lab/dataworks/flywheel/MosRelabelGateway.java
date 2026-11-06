package com.lab.dataworks.flywheel;

/**
 * 飞轮回环的出环端口（REQ-FW-3③）：把一批 badcase 变成 mos 侧的标注任务。
 *
 * <p>实现归 FW-1 的 mosbridge 推送器（复用其桥接 client，卡面红线=禁止在本包复制第二份）。
 * 落 mos 既有口（契约 §3.2）：manual 数据集 +
 * {@code POST /api/datasets/{ds_id}/items/upload?allow_empty_a=1}
 * （{@code mos/platform/backend/api/datasets.py:1040}，按归一化问题去重），
 * 终态=<b>数据集条目</b>，<b>不会</b>自动进标注工作台队列（旧注释「进原有队列」失实：
 * 数据集→LS 任务无自动通路，队列可见现网唯一口=mos 手动
 * {@code POST /api/label-studio/projects/{pid}/import}，终态待评审确认见 BUG-CHAIN-2）
 * ——REST 同步、不引 MQ、不建第二套队列。
 * 注：旧注释「自动认 messages 字段」系误记——messages 轨迹同样要求 q/a 双非空
 * （{@code _qa_of_obj}，datasets.py:966），只带 user 轮的 jsonl 必被 400 拒；
 * 空答案载荷走 TSV+allow_empty_a 口径（BUG-CHAIN-1，见 {@link RelabelJsonl}）。</p>
 *
 * <p>幂等：实现方<b>重试不得重造数据集</b>——同 ledgerKey 已建则查回复用
 * （BUG-CHAIN-1：旧实现每次调用先 POST /api/datasets，upload 失败不回滚不重用，
 * FlywheelJob 每圈重试 = 每圈多一个同名空集，现网 120s 观察窗 +1 坐实）。</p>
 *
 * <p>当前构建里没有该 bean 时，{@link RelabelTriggerService} 把批次停在 READY 态并
 * 每圈重试探测，不伪造成功。</p>
 */
public interface MosRelabelGateway {

    /**
     * 创建（或复用重试时已建的）一个重标注任务并把池内容推进去。
     *
     * @param ledgerKey 中台台账键（badcase:&lt;tenant&gt;:&lt;round&gt;，实现方按它做幂等）
     * @param description 任务描述（含目标模型名，见 {@link RelabelJsonl#taskDescription}）
     * @param poolTsv   池内容载荷：每行 {@code 问题<TAB>} 的 TSV，答案空=待标注（BUG-CHAIN-1）
     * @return mos 侧任务/数据集引用（如 {@code ds_a1b2c3d4@v1}），回写台账 mos_task_ref
     */
    String createRelabelTask(String ledgerKey, String description, String poolTsv);
}
