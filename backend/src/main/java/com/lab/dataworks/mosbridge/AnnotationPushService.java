package com.lab.dataworks.mosbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.query.DorisQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REQ-FW-1 · 数据飞轮回流①：中台表/查询结果 → messages jsonl → 对方标注数据集。
 *
 * <p>断点接线（卡面：中台有数据、mos 有接收端、中间没人接线）：本类只做
 * 「表/查询结果 → jsonl → 调对方现成进环口」这一个接头，下游四环（标注→冻结→训练→评测）
 * 不改一行（PM 03:03 取证口径）。进环口=对方 upload（{@code mos/platform/backend/api/datasets.py:1034}
 * {@code POST /api/datasets/{ds_id}/items/upload}，multipart 字段 {@code file}，按归一化 q 自动去重）。</p>
 *
 * <p>字段映射按契约 dabce8a §3.1：question→{@code messages[0]} {"role":"user"}、
 * answer→{@code messages[1]} {"role":"assistant"}、行主键→{@code source_ref}；
 * 对方解析锚点 {@code _qa_of_obj}（datasets.py:945-968）。对方对 q/a 缺值的行是<b>静默跳过</b>
 * （datasets.py:1092-1094 empty++），中台侧不许静默——映射前逐行校验，缺值即 400 并报行号。</p>
 *
 * <p>台账：推成功后登记 {@code ds_id@version}（契约 §3.3：单一事实源在对方，中台不二次落数据文件），
 * 表 {@code platform.annotation_push_ledger}（V160）。防重推靠 UNIQUE(ds_id,version)+ON CONFLICT 覆盖，
 * <b>同版本重复推不增行</b>；对方 version 在 freeze 前为 NULL（db.py:43 TEXT），归一化为 {@code "0"}
 * ——PG 唯一索引里 NULL 互不相等，不归一化幂等就破防。</p>
 *
 * <p>通道：UNIFY-1 桥接未完（其 MosClient 在 wm6 分支未合入），按卡面「开发态直连 :8090」，
 * 本类自带最小服务账号登录态（口令 Cookie 模式按契约 §6.2，auth.py:33/77 锚点见 MosClient 同款注释）；
 * 配置键沿用 {@code platform.mos.*}，UNIFY-1 合并后可整体换用其 MosClient 代持会话。
 * 出网白名单与 UNIFY-1 同源：{@code EgressPolicy} 启动时把 base-url 的 host[:port] 追加进 JdbcUrlGuard，
 * 本类目标即同一 base-url，不另开访问面。口令不落代码：未配置时 {@link #configured()}=false，
 * 调用直接 503 明确文案（凭据边界，PROTOCOL 2026-10-26）。</p>
 *
 * <p>HTTP/1.1 钉死（REQ-UNIFY-1 真实取证踩坑）：java.net.http 默认 h2c 升级前言会让 uvicorn
 * 吞 POST body → 422；对方是 uvicorn，必须显式 1.1。</p>
 */
@Component
public class AnnotationPushService {

    private static final Logger log = LoggerFactory.getLogger(AnnotationPushService.class);

    /** 对方会话 Cookie 名（mos/platform/backend/auth.py:33）。 */
    static final String SESSION_COOKIE = "modelonestation_session";

    /** 推送结果（数字全部来自对方 _insert_pairs 返回，datasets.py:1114）。 */
    public record PushReport(String dsId, String version, int inserted, int duplicated,
                             int empty, int total) {
    }

    private final String baseUrl;
    private final String serviceUser;
    private final String servicePassword;
    private final long timeoutMs;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc;

    /** 服务账号会话（本通道按批推数据，共用服务账号身份即可；「谁点的」由控制层进审计）。 */
    private volatile String session;
    private final AtomicLong loginCalls = new AtomicLong();

    public AnnotationPushService(
            @Value("${platform.mos.base-url:http://127.0.0.1:30095}") String baseUrl,
            @Value("${platform.mos.username:svc-dataworks}") String serviceUser,
            @Value("${platform.mos.password:}") String servicePassword,
            @Value("${platform.mos.timeout-ms:15000}") long timeoutMs,
            JdbcTemplate jdbc) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceUser = serviceUser == null ? "" : serviceUser.trim();
        this.servicePassword = servicePassword == null ? "" : servicePassword;
        this.timeoutMs = timeoutMs <= 0 ? 15000 : timeoutMs;
        this.jdbc = jdbc;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 服务账号是否配置齐全（未配置=通道 503，不拿空口令硬试）。 */
    public boolean configured() {
        return !servicePassword.isBlank() && !serviceUser.isBlank();
    }

    /** 登录次数：供单测钉住「会话复用、401 恰好重登一次」。 */
    public long loginCalls() {
        return loginCalls.get();
    }

    /**
     * 建对方数据集（契约 §3.2「建数据集 POST /api/datasets」，datasets.py:192）。
     * 只做进环前置的最小组合；产品入口归 FW-4 编排。
     */
    public String createDataset(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("数据集名称不能为空");
        }
        if (name.length() > 200) {
            throw ApiException.badRequest("数据集名称最长 200 个字符（与对方上限一致，datasets.py:198）");
        }
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("name", name.trim()));
            JsonNode node = call("POST", "/api/datasets", body, "application/json");
            String id = node.path("id").asText("");
            if (id.isBlank()) {
                throw new ApiException(502, "对方建数据集响应缺少 id：" + node);
            }
            return id;
        } catch (IOException e) {
            throw new ApiException(500, "建数据集请求体构造失败：" + e.getMessage());
        }
    }

    /**
     * 主入口：查询结果 → jsonl → upload → 读回对方 version → 台账 upsert。
     *
     * @param sourceDesc 来源描述（本批的 SQL/表名，进台账留痕；截 500 字符）
     */
    public PushReport push(String tenantCode, String operator, String sourceDesc,
                           String dsId, DorisQueryService.Result result,
                           String qColumn, String aColumn, String refColumn) {
        if (dsId == null || dsId.isBlank()) {
            throw ApiException.badRequest("dsId 必填（对方主键是字符串 ds_xxx，契约 §10 坑 2）");
        }
        if (result == null) {
            throw ApiException.badRequest("查询结果为空对象，无法推送");
        }
        if (!configured()) {
            throw new ApiException(503, "ModelOneStation 服务账号未配置（platform.mos.password 为空）");
        }
        String jsonl = toMessagesJsonl(result.columns(), result.rows(), qColumn, aColumn, refColumn);
        byte[] multipart = multipart("push-" + dsId.trim() + ".jsonl",
                jsonl.getBytes(StandardCharsets.UTF_8));
        JsonNode up = call("POST", "/api/datasets/" + dsId.trim() + "/items/upload",
                multipart, "multipart/form-data; boundary=" + MULTIPART_BOUNDARY);
        int inserted = up.path("inserted").asInt(-1);
        int duplicated = up.path("duplicated").asInt(-1);
        int empty = up.path("empty").asInt(-1);
        int total = up.path("total").asInt(-1);
        if (inserted < 0 || total < 0) {
            // 不许静默：对方响应形态变了要显式炸，不拿默认 0 假装成功
            throw new ApiException(502, "对方 upload 响应缺少 inserted/total 字段：" + brief(up));
        }
        // 版本从对方读回（单一事实源在对方，契约 §3.3）；freeze 前 version NULL → '0'
        String version = fetchVersion(dsId.trim());
        upsertLedger(tenantCode, operator, sourceDesc, dsId.trim(), version,
                qColumn, aColumn, refColumn, total);
        log.info("数据入环 ds={} version={} inserted={} duplicated={} by={}",
                dsId, version, inserted, duplicated, operator);
        return new PushReport(dsId.trim(), version, inserted, duplicated, empty, total);
    }

    /** 数据集当前条目数（GET /api/datasets/{id} 裸行含 n_items，datasets.py:301）。 */
    public int itemCount(String dsId) {
        JsonNode node = call("GET", "/api/datasets/" + dsId.trim(), null, null);
        return node.path("n_items").asInt(-1);
    }

    private String fetchVersion(String dsId) {
        JsonNode node = call("GET", "/api/datasets/" + dsId, null, null);
        JsonNode v = node.get("version");
        if (v == null || v.isNull() || v.asText().isBlank()) {
            return "0";
        }
        return v.asText();
    }

    /**
     * §3.1 映射：列名大小写不敏感；q/a 任一缺值的行先收集、后一次性 400
     * （对方对缺值行静默跳过，中台不许——否则台账 row_count 和 mos 实际条目永远对不上还没人知道）。
     */
    static String toMessagesJsonl(List<String> columns, List<List<String>> rows,
                                  String qColumn, String aColumn, String refColumn) {
        if (columns == null || columns.isEmpty()) {
            throw ApiException.badRequest("查询结果没有列，无法映射");
        }
        if (qColumn == null || qColumn.isBlank() || aColumn == null || aColumn.isBlank()) {
            throw ApiException.badRequest("qColumn / aColumn 必填（问题列/答案列，契约 §3.1）");
        }
        int qi = requireColumnIndex(columns, qColumn, "qColumn");
        int ai = requireColumnIndex(columns, aColumn, "aColumn");
        int ri = -1;
        if (refColumn != null && !refColumn.isBlank()) {
            ri = requireColumnIndex(columns, refColumn, "refColumn");
        }
        if (rows == null || rows.isEmpty()) {
            throw ApiException.badRequest("查询结果没有行，没有可推送的数据");
        }
        List<String> errors = new ArrayList<>();
        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String q = cell(row, qi);
            String a = cell(row, ai);
            if (q.isBlank() || a.isBlank()) {
                errors.add("第" + (i + 1) + "行缺" + (q.isBlank() ? "问题列 " + qColumn : "")
                        + (q.isBlank() && a.isBlank() ? "与" : "")
                        + (a.isBlank() ? "答案列 " + aColumn : ""));
                continue;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("messages", List.of(
                    Map.of("role", "user", "content", q),
                    Map.of("role", "assistant", "content", a)));
            if (ri >= 0) {
                String ref = cell(row, ri);
                if (!ref.isBlank()) {
                    line.put("source_ref", ref);
                }
            }
            try {
                jsonl.append(new ObjectMapper().writeValueAsString(line)).append('\n');
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new ApiException(500, "第" + (i + 1) + "行序列化为 jsonl 失败：" + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            String head = errors.subList(0, Math.min(errors.size(), 20)).toString();
            String tail = errors.size() > 20 ? "……（共 " + errors.size() + " 行，仅列前 20）" : "";
            throw ApiException.badRequest("存在缺问题/答案的行，已拒绝整批推送（不静默丢行）：" + head + tail);
        }
        return jsonl.toString();
    }

    /** 列名大小写不敏感解析；找不到就 400 报实际列清单（不猜「最接近的列」）。 */
    private static int requireColumnIndex(List<String> columns, String wanted, String param) {
        String needle = wanted.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i) != null && columns.get(i).trim().toLowerCase(Locale.ROOT).equals(needle)) {
                return i;
            }
        }
        throw ApiException.badRequest(param + "「" + wanted + "」不在查询结果列中（实际列：" + columns + "）");
    }

    private static String cell(List<String> row, int idx) {
        if (row == null || idx < 0 || idx >= row.size() || row.get(idx) == null) {
            return "";
        }
        return row.get(idx).trim();
    }

    // ---------------- 对方 HTTP（开发态直连；模式与 MosClient 同源，键复用 platform.mos.*） ----------------

    private static final String MULTIPART_BOUNDARY = "----mosFw1" + UUID.randomUUID().toString().replace("-", "");

    private static byte[] multipart(String filename, byte[] fileBytes) {
        String head = "--" + MULTIPART_BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/x-ndjson\r\n\r\n";
        String tail = "\r\n--" + MULTIPART_BOUNDARY + "--\r\n";
        byte[] h = head.getBytes(StandardCharsets.UTF_8);
        byte[] t = tail.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + fileBytes.length + t.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(fileBytes, 0, out, h.length, fileBytes.length);
        System.arraycopy(t, 0, out, h.length + fileBytes.length, t.length);
        return out;
    }

    /** 带代持会话调用；401 恰好重登一次并重放（契约 §6.2-3），仍失败就显式 502 不吞。 */
    private JsonNode call(String method, String path, byte[] body, String contentType) {
        if (!configured()) {
            throw new ApiException(503, "ModelOneStation 服务账号未配置（platform.mos.password 为空）");
        }
        try {
            if (session == null) {
                login();
            }
            HttpResponse<byte[]> resp = send(method, path, body, contentType, session);
            if (resp.statusCode() == 401) {
                log.info("mos 会话失效，重登一次后重放 {} {}", method, path);
                login();
                resp = send(method, path, body, contentType, session);
            }
            int status = resp.statusCode();
            String text = new String(resp.body(), StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new ApiException(502, "ModelOneStation " + method + " " + path
                        + " 返回 HTTP " + status + "：" + brief(text));
            }
            try {
                return mapper.readTree(text);
            } catch (IOException parse) {
                throw new ApiException(502, "ModelOneStation 响应不是 JSON（" + method + " " + path
                        + "）：" + brief(text));
            }
        } catch (IOException e) {
            throw new ApiException(502, "无法连接 ModelOneStation（" + baseUrl + "）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "调用 ModelOneStation 被中断：" + e.getMessage());
        }
    }

    private String login() throws IOException, InterruptedException {
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of("username", serviceUser, "password", servicePassword));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("服务账号登录体构造失败", e);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        loginCalls.incrementAndGet();
        if (resp.statusCode() != 200) {
            session = null;
            throw new ApiException(502, "ModelOneStation 服务账号 " + serviceUser + " 登录失败：HTTP "
                    + resp.statusCode() + "（口令未投/账号不存在时报这个，不静默重试）");
        }
        String token = null;
        for (String header : resp.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0].trim();
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(SESSION_COOKIE)) {
                token = pair.substring(eq + 1).trim();
                break;
            }
        }
        if (token == null || token.isBlank()) {
            session = null;
            throw new ApiException(502, "ModelOneStation 登录响应没有 " + SESSION_COOKIE + " Cookie");
        }
        session = token;
        return token;
    }

    private HttpResponse<byte[]> send(String method, String path, byte[] body,
                                      String contentType, String token)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = (body == null || body.length == 0)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Cookie", SESSION_COOKIE + "=" + token);
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String brief(Object nodeOrText) {
        String s = String.valueOf(nodeOrText);
        return s.length() <= 200 ? s : s.substring(0, 200) + "……";
    }

    // ---------------- 台账（V160，幂等 upsert） ----------------

    /** 同 (ds_id, version) 重复推 = 覆盖不增行；version 已归一化（NULL→'0'），唯一约束真实生效。 */
    private void upsertLedger(String tenantCode, String operator, String sourceDesc,
                              String dsId, String version, String qColumn, String aColumn,
                              String refColumn, int rowCount) {
        String desc = sourceDesc == null ? "" : sourceDesc.trim();
        if (desc.length() > 500) {
            desc = desc.substring(0, 500) + "…";
        }
        jdbc.update("INSERT INTO platform.annotation_push_ledger"
                        + " (tenant_code, ds_id, version, source_desc, q_column, a_column, ref_column,"
                        + "  row_count, pushed_by, pushed_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,now(),now())"
                        + " ON CONFLICT (ds_id, version) DO UPDATE SET"
                        + "   tenant_code = EXCLUDED.tenant_code,"
                        + "   source_desc = EXCLUDED.source_desc,"
                        + "   q_column = EXCLUDED.q_column,"
                        + "   a_column = EXCLUDED.a_column,"
                        + "   ref_column = EXCLUDED.ref_column,"
                        + "   row_count = EXCLUDED.row_count,"
                        + "   pushed_by = EXCLUDED.pushed_by,"
                        + "   updated_at = now()",
                blankToNull(tenantCode), dsId, version, desc,
                qColumn == null ? "" : qColumn.trim(),
                aColumn == null ? "" : aColumn.trim(),
                blankToNull(refColumn),
                rowCount, blankToNull(operator));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
