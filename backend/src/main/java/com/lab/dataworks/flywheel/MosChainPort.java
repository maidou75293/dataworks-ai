package com.lab.dataworks.flywheel;

import java.util.List;
import java.util.Map;

/**
 * 编排器对 mos 原生链的「发起」端口（REQ-FW-4 口径：只发起，不重写链内步骤）。
 *
 * <p>每一步都落在对方现成 API 上（锚点见实现注释），链内自动化仍由对方
 * {@code runner._auto_chain}（train→export→quant）+ webhook/对账完成；
 * 中台不在这些阶段之间插状态。</p>
 */
public interface MosChainPort {

    /** POST /api/datasets（datasets.py:192）——FW-1 createDataset 的等价口，供 RELABEL 子圈之外首圈建集。 */
    String createDataset(String operator, String name);

    /** POST /api/datasets/{id}/freeze → version 字符串（datasets.py:1216 起，响应 {ok,version,lines…}）。 */
    String freezeDataset(String operator, String dsId);

    /**
     * POST /api/jobs（jobs.py:11/_spawn）发起训练。
     * body={method, dataset_id, params{base,chain_export,chain_quant,…}, force:true}。
     */
    String submitTrainingJob(String operator, String datasetId, String method, Map<String, Object> params);

    /** POST /api/evals（evals.py EvalBody{name,dataset_id,candidates[],judge,limit}）→ ev_xxx。 */
    String submitEval(String operator, String name, String datasetId,
                      List<Map<String, String>> candidates, int limit);

    /** POST /api/evals/{id}/run（后台线程跑；中台只经 FW-2 轮询读结果）。 */
    void runEval(String operator, String evalId);

    /** POST /api/deployments（deploy.py:303 {model_id,…}）→ dep_xxx。 */
    String registerDeployment(String operator, String modelExtId, String notes);

    /** POST /api/deployments/{id}/start（deploy.py:331，真实起子进程；失败 4xx 原样上抛）。 */
    void startDeployment(String operator, String depId);

    /** POST /api/deployments/{id}/activate（deploy.py:401，平台 LLM 指向本实例——闭环的「上岗」动作）。 */
    void activateDeployment(String operator, String depId);

    /** 通道是否可用（口令未配置时编排器不硬试，503 文案要能说明原因）。 */
    boolean enabled();

    String disabledReason();
}
