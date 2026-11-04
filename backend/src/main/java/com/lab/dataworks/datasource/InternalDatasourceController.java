package com.lab.dataworks.datasource;

import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群内部接口：给 Prefect Flow 在运行时取数据源明文连接信息。
 *
 * <p>数据库里存的是 AES-GCM 密文，Flow 不能直接查表使用；由后端解密后通过
 * 共享的内部 Token 提供。只允许集群内调用，不参与登录会话鉴权。</p>
 */
@RestController
@RequestMapping("/api/internal")
public class InternalDatasourceController {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final com.lab.dataworks.auth.InternalTokenService internalTokens;

    public InternalDatasourceController(JdbcTemplate jdbc, SecretCipher cipher,
                                        com.lab.dataworks.auth.InternalTokenService internalTokens) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.internalTokens = internalTokens;
    }

    @GetMapping("/datasources/{id}")
    public Map<String, Object> datasource(@PathVariable long id,
                                          @RequestHeader(value = "X-Internal-Token", required = false)
                                          String token,
                                          HttpServletResponse response) {
        if (!internalTokens.configured()) {
            throw new ApiException(503, "内部接口未启用（PLATFORM_INTERNAL_TOKEN 未配置）");
        }
        if (!internalTokens.matches(token)) {
            throw new ApiException(401, "内部凭证无效");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, name, ds_type, jdbc_url, username, password
                from platform.datasource where id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("数据源不存在: " + id);
        }
        Map<String, Object> row = rows.get(0);
        // S1（2026-10-26 扫描 P0）：存量数据里可能已经有指向平台元数据库的数据源
        // （历史遗留的 postgres-platform），运行时也不能把它的凭证发给 Flow。
        String url = row.get("jdbc_url") == null ? "" : String.valueOf(row.get("jdbc_url"));
        String reason = com.lab.dataworks.common.PlatformInternalDb.urlViolation(url);
        if (reason != null) {
            throw new ApiException(403, "该数据源指向平台内部元数据库，运行时不下发凭证：" + reason);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("name", row.get("name"));
        out.put("dsType", row.get("ds_type"));
        out.put("jdbcUrl", row.get("jdbc_url"));
        out.put("username", row.get("username"));
        out.put("password", cipher.decrypt(String.valueOf(row.get("password"))));
        response.setHeader("Cache-Control", "no-store");
        return out;
    }

    private static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(
                (a == null ? "" : a).getBytes(StandardCharsets.UTF_8),
                (b == null ? "" : b).getBytes(StandardCharsets.UTF_8));
    }
}
