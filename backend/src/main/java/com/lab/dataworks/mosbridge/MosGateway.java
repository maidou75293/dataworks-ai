package com.lab.dataworks.mosbridge;

import java.util.List;
import java.util.Map;

/**
 * 只读取 mos（ModelOneStation）平台状态的端口。
 *
 * <p>REQ-FW-2 的轮询器只依赖这个接口：契约 §5 明确对方没有任何 outbound 回调，
 * 中台一律走 GET 轮询。生产实现是 {@link HttpMosGateway}；单测用内存桩，
 * 这样「超时 / 失败 / 幂等」三态不需要真实网络就能判定。</p>
 */
public interface MosGateway {

    /** 配置齐全（base-url + 服务账号口令）才允许出站；缺配置时轮询器空转并如实报原因。 */
    boolean enabled();

    /** 缺配置时给调度日志用的一句话原因。 */
    String disabledReason();

    /** GET /api/jobs/{id}：行含 id/type/method/status/progress/updated_at/out_dir/own_dir（jobs.py:1267）。 */
    Map<String, Object> getJob(String jobId) throws Exception;

    /** GET /api/models：全量台账行，训练产物按 job_id 反查（models.py:93）。 */
    List<Map<String, Object>> listModels() throws Exception;

    /** GET /api/models/{mid}（models.py:188）。 */
    Map<String, Object> getModel(String modelId) throws Exception;

    /**
     * GET /api/models/{mid}/manifest 的**原始响应文本**（models.py:484）。
     * 台账要按契约 §4.2 对「内容」做 sha256，解析后再序列化会因键序产生不同摘要，所以这里不解析。
     */
    String getManifestRaw(String modelId) throws Exception;

    /** GET /api/evals/{eid}：result/error 已在对方侧解成对象（evals.py:117）。 */
    Map<String, Object> getEval(String evalId) throws Exception;
}
