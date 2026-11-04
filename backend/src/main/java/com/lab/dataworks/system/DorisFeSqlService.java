package com.lab.dataworks.system;

import com.lab.dataworks.common.ApiException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Doris 管理 SQL 通道：进 FE Pod，用 127.0.0.1 的 root 执行。
 *
 * <p>{@code root@'%'} 有密码且平台不掌握，但 Doris FE 允许来自本机回环的 root 免密登录，
 * 这和运维手工登录 FE 是同一个口子——不需要新建账号，也不需要把密码写进 Secret。</p>
 *
 * <p>凡是需要 {@code Admin_priv} 的语句（CREATE RESOURCE / STORAGE POLICY、
 * ALTER SYSTEM 扩缩容）都走这里；普通查询继续用 {@link com.lab.dataworks.project.DorisAdminService}
 * 的业务账号 JDBC。</p>
 */
@Service
public class DorisFeSqlService {

    private static final Logger log = LoggerFactory.getLogger(DorisFeSqlService.class);
    private static final String NAMESPACE = "dw-dev";
    private static final String FE_LABEL = "doris-fe";

    /** 执行 SQL 并返回标准输出（mysql --batch 的制表符分隔文本）。 */
    public String run(String sql) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Pod fe = null;
            for (Pod p : client.pods().inNamespace(NAMESPACE).withLabel("app", FE_LABEL).list().getItems()) {
                if (p.getStatus() != null && "Running".equals(p.getStatus().getPhase())) {
                    fe = p;
                    break;
                }
            }
            if (fe == null) {
                throw new ApiException(503, "Doris FE 未就绪，暂时无法执行管理语句");
            }
            String pod = fe.getMetadata().getName();
            String container = fe.getSpec() == null || fe.getSpec().getContainers().isEmpty()
                    ? null : fe.getSpec().getContainers().get(0).getName();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try (ExecWatch exec = client.pods().inNamespace(NAMESPACE).withName(pod)
                    .inContainer(container)
                    .writingOutput(out)
                    .writingError(err)
                    .exec("mysql", "-h", "127.0.0.1", "-P", "9030", "-u", "root",
                            "--batch", "--raw", "-e", sql)) {
                Integer code = exec.exitCode().get(60, TimeUnit.SECONDS);
                if (code == null || code != 0) {
                    String message = err.toString(StandardCharsets.UTF_8).trim();
                    throw new ApiException(502, "Doris 执行失败: " + sanitize(message));
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(502, "连接 Doris FE 失败: " + e.getMessage());
        }
    }

    /** 查询并把结果按列名转成字符串 Map。 */
    public List<Map<String, String>> query(String sql) {
        String text = run(sql);
        List<Map<String, String>> rows = new ArrayList<>();
        String[] lines = text.split("\n");
        if (lines.length < 2) {
            return rows;
        }
        String[] header = lines[0].split("\t", -1);
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] cells = lines[i].split("\t", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.length && c < cells.length; c++) {
                row.put(header[c], cells[c]);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 一次执行多条同类语句，按结果块拆开。
     *
     * <p>mysql --batch 把多个结果集首尾相接输出，每块第一行都是表头；
     * 这里用「首列等于 headerFirstCell」来切块（如 SHOW TABLETS 的 TabletId）。
     * 一条 exec 的往返比 N 条省得多：19 张表的 tablet 明细从 19 次降到 1 次。</p>
     */
    public List<List<Map<String, String>>> queryBlocks(String sql, String headerFirstCell) {
        String text = run(sql);
        List<List<Map<String, String>>> blocks = new ArrayList<>();
        String[] header = null;
        List<Map<String, String>> current = null;
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            if (cells.length > 0 && headerFirstCell.equals(cells[0])) {
                if (current != null) {
                    blocks.add(current);
                }
                header = cells;
                current = new ArrayList<>();
                continue;
            }
            if (current == null || header == null) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.length && c < cells.length; c++) {
                row.put(header[c], cells[c]);
            }
            current.add(row);
        }
        if (current != null) {
            blocks.add(current);
        }
        return blocks;
    }

    /**
     * 多行结果块：SHOW CREATE TABLE 的 DDL 单元格里带换行，按行切会把 DDL 打散，
     * 这里按「首列等于 headerFirstCell」切块，块内原样保留换行。
     */
    public List<String> queryTextBlocks(String sql, String headerFirstCell) {
        String text = run(sql);
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;
        for (String line : text.split("\n")) {
            if (line.startsWith(headerFirstCell + "\t")) {
                if (current != null) {
                    blocks.add(current.toString());
                }
                current = new StringBuilder(line);
                continue;
            }
            if (current != null) {
                current.append('\n').append(line);
            }
        }
        if (current != null) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    /** 执行一条管理语句（不需要返回值）。 */
    public void exec(String sql) {
        log.info("doris admin sql: {}", sql.length() > 120 ? sql.substring(0, 120) + "…" : sql);
        run(sql);
    }

    /** 取某一列（找不到返回空串）。 */
    public static String column(Map<String, String> row, String name) {
        String value = row.get(name);
        return value == null ? "" : value.trim();
    }

    /**
     * 错误信息脱敏。
     *
     * <p>mysql 客户端执行失败时会把整条语句回显到 stderr，CREATE RESOURCE 里带着
     * S3 的 AK/SK——原样透传就会把密钥写进接口响应和日志。
     */
    static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        return message
                .replaceAll("(?i)(secret[_-]?key|access[_-]?key|password)(\"?\\s*[=:]\\s*\"?)[^\"\\s,)]+",
                        "$1$2***")
                .replaceAll("(?i)(s3\\.secret_key\"\\s*=\\s*\")[^\"]+", "$1***")
                .replaceAll("(?i)(s3\\.access_key\"\\s*=\\s*\")[^\"]+", "$1***");
    }
}
