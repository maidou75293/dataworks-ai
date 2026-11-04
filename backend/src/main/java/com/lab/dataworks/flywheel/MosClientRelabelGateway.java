package com.lab.dataworks.flywheel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.mosbridge.MosClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * {@link MosRelabelGateway} 的实现（REQ-FW-4 补的 loop-closing glue；BUG-CHAIN-1 修口径+幂等）。
 *
 * <p>动作：在 mos 建 manual 数据集（名 {@code relabel-<ledgerKey>}）+
 * {@code POST /api/datasets/{ds_id}/items/upload?allow_empty_a=1} 推 TSV 空答案载荷
 * （{@code mos/platform/backend/api/datasets.py:1040}，按归一化 q 去重）。终态=
 * <b>数据集条目</b>，<b>不会</b>自动进标注工作台队列——旧注释「任务进工作台原有队列」
 * 失实（BUG-CHAIN-2）：曾误引的 {@code label_studio.py _reconcile_rows} 是「保存标注→
 * 数据集镜像」的反向去重，数据集条目→LS 任务无自动通路；现网队列可见的唯一口=mos 手动
 * {@code POST /api/label-studio/projects/{pid}/import}（{@code import_tasks}，平台侧暂无
 * 调用方），终态待评审确认。不建第二套队列、不复制桥接 client：出站全部复用
 * REQ-UNIFY-1 {@link MosClient} 代持会话。</p>
 *
 * <p>重试幂等（BUG-CHAIN-1 根因 3）：<b>先按台账名查回已有数据集复用，查无才建</b>。
 * 旧实现每次调用先 POST /api/datasets、upload 失败不回滚不重用，FlywheelJob 每圈重试
 * = 每圈多一个同名空集（现网 120s 观察窗 2→3 坐实，≈每天 +288 垃圾集）。
 * 复用路径下 upload 靠对方按 q 去重兜底：半途被杀后重推=全判重，不重不漏。
 * mos 侧无 name 唯一约束、{@code RelabelTriggerService.pushPending} 的台账唯一键只挡
 * 重封批不挡重推送——幂等必须在<b>这里</b>补。</p>
 *
 * <p>计数校验（不许静默）：解析行数 total、空答案丢弃 empty、inserted+duplicated
 * 三项都对不上载荷行数就 502 炸错、批次留 READY——防对方解析/闸口径再变
 * （以 <code>{</code> 开头的问题撞 JSON 分支丢行即在此现形，而非静默进空集）。</p>
 */
@Component
public class MosClientRelabelGateway implements MosRelabelGateway {

    private static final Logger log = LoggerFactory.getLogger(MosClientRelabelGateway.class);
    private static final String OPERATOR = "flywheel-relabel";

    private final MosClient client;
    private final ObjectMapper json = new ObjectMapper();

    public MosClientRelabelGateway(MosClient client) {
        this.client = client;
    }

    @Override
    public String createRelabelTask(String ledgerKey, String description, String poolTsv) {
        if (!client.configured()) {
            throw new ApiException(503, "ModelOneStation 服务账号未配置（platform.mos.password 为空），"
                    + "badcase 回环无法出环");
        }
        String name = "relabel-" + ledgerKey;
        String dsId = findDatasetByName(name);
        boolean reused = dsId != null;
        if (!reused) {
            dsId = text(call("POST", "/api/datasets",
                    Map.of("name", name), null), "id", "建重标注数据集");
        }
        int expected = countLines(poolTsv);
        byte[] file = (poolTsv == null ? "" : poolTsv).getBytes(StandardCharsets.UTF_8);
        // multipart 构造只改 filename/载荷形态（BUG-CHAIN-1：TSV 空答案 + allow_empty_a 口径）
        JsonNode up = call("POST", "/api/datasets/" + dsId + "/items/upload?allow_empty_a=1",
                multipart("relabel-" + ledgerKey + ".tsv", file),
                "multipart/form-data; boundary=" + BOUNDARY);
        verifyCounts(ledgerKey, expected, up);
        String version = text(call("GET", "/api/datasets/" + dsId, null, null), "version", "读回版本");
        if (version.isBlank() || "null".equals(version)) {
            version = "0";   // freeze 前对方 version=NULL（db.py:43），归一化口径同 FW-1
        }
        String ref = dsId + "@" + version;
        log.info("badcase 出环成功：{}（{} 条，数据集{}）→ mos {}", ledgerKey, expected,
                reused ? "复用" : "新建", ref);
        return ref;
    }

    /** 按台账名查回已有数据集（BUG-CHAIN-1 重试幂等锚）；查无返回 null。列表按 created_at 倒序，取最新一条。 */
    private String findDatasetByName(String name) {
        JsonNode arr = call("GET", "/api/datasets", null, null);
        if (!arr.isArray()) {
            throw new ApiException(502, "对方数据集列表响应形态不是数组，无法按名复用：" + brief(arr));
        }
        for (JsonNode d : arr) {
            if (name.equals(d.path("name").asText(""))) {
                String id = d.path("id").asText("");
                if (!id.isBlank()) {
                    return id;
                }
            }
        }
        return null;
    }

    /** 载荷行数 vs 对方计数：total/empty/inserted+duplicated 任一不吻合就显式炸错。 */
    private static void verifyCounts(String ledgerKey, int expected, JsonNode up) {
        int inserted = up.path("inserted").asInt(-1);
        int total = up.path("total").asInt(-1);
        if (inserted < 0 || total < 0) {
            throw new ApiException(502, "对方 upload 响应缺少 inserted/total 字段：" + brief(up));
        }
        if (total != expected) {
            throw new ApiException(502, "badcase 出环 " + ledgerKey + " 行数不吻合：载荷 " + expected
                    + " 行、对方解析 total=" + total + "（问题以 { 开头会撞对方 JSON 分支被丢——不静默）");
        }
        int empty = up.path("empty").asInt(0);
        if (empty > 0) {
            throw new ApiException(502, "badcase 出环 " + ledgerKey + " 被对方丢弃空答案行 empty=" + empty
                    + "（allow_empty_a 未生效？对方 datasets.py 闸口径已变）");
        }
        int duplicated = up.path("duplicated").asInt(0);
        if (inserted + duplicated != total) {
            throw new ApiException(502, "badcase 出环 " + ledgerKey + " 计数不闭合：inserted=" + inserted
                    + " duplicated=" + duplicated + " total=" + total + "：" + brief(up));
        }
    }

    private static int countLines(String tsv) {
        if (tsv == null || tsv.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String line : tsv.split("\n")) {
            if (!line.isBlank()) {
                n++;
            }
        }
        return n;
    }

    // ---------- 通道（与 FW-1 的差别只在这：直接用共享 MosClient，不自备登录态） ----------

    private static final String BOUNDARY = "----fwRelabel" + UUID.randomUUID().toString().replace("-", "");

    private JsonNode call(String method, String path, Object body, String contentType) {
        try {
            byte[] payload;
            if (body instanceof byte[] raw) {
                payload = raw;
            } else if (body == null) {
                payload = null;
            } else {
                payload = json.writeValueAsBytes(body);
                if (contentType == null) {
                    contentType = "application/json";
                }
            }
            MosClient.Upstream up = client.exchange(OPERATOR, method, path, payload, contentType);
            String text = up.body() == null ? "" : new String(up.body(), StandardCharsets.UTF_8);
            if (up.status() < 200 || up.status() >= 300) {
                throw new ApiException(502, "ModelOneStation " + method + " " + path
                        + " 返回 HTTP " + up.status() + "：" + brief(text));
            }
            return json.readTree(text);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "调用 ModelOneStation 失败（" + method + " " + path + "）：" + e);
        }
    }

    /** multipart 构造（字段名 file，通道口径同 FW-1 AnnotationPushService.multipart / 对方 datasets.py:1040；
     * part 类型随载荷改为 TSV——对方按内容嗅探，此头仅为如实描述）。 */
    private static byte[] multipart(String filename, byte[] fileBytes) {
        String head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/tab-separated-values\r\n\r\n";
        String tail = "\r\n--" + BOUNDARY + "--\r\n";
        byte[] h = head.getBytes(StandardCharsets.UTF_8);
        byte[] t = tail.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + fileBytes.length + t.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(fileBytes, 0, out, h.length, fileBytes.length);
        System.arraycopy(t, 0, out, h.length + fileBytes.length, t.length);
        return out;
    }

    private static String text(JsonNode node, String field, String action) {
        String v = node.path(field).asText("");
        if (v.isBlank() && node.get(field) != null && !node.get(field).isNull()) {
            v = node.get(field).asText();
        }
        if (v.isBlank() && !"version".equals(field)) {
            throw new ApiException(502, "对方「" + action + "」响应缺少 " + field + "：" + brief(node));
        }
        return v;
    }

    private static String brief(Object o) {
        String s = String.valueOf(o);
        return s.length() <= 200 ? s : s.substring(0, 200) + "……";
    }
}
