package com.lab.dataworks.project;

import com.lab.dataworks.auth.TenantScope;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.secret.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 解析数仓连接：项目只存 id，真正连接前在这里取端点与解密后的密码。
 * 连接必须属于当前租户且类型匹配。
 */
@Service
public class WarehouseConnectionService {

    public record ConnectionInfo(long id, String type, String provider, String endpoint,
                                 String username, String password, String bucketOrPath,
                                 String region) {
    }

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public WarehouseConnectionService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public ConnectionInfo get(long id, String type) {
        ConnectionInfo info = jdbc.query("""
                select id, type, provider, endpoint, username, encrypted_password,
                       bucket_or_path, region
                  from platform.warehouse_connection
                 where id = ? and tenant_id = ? and type = ?
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            String stored = rs.getString("encrypted_password");
            return new ConnectionInfo(rs.getLong("id"), rs.getString("type"),
                    rs.getString("provider"), rs.getString("endpoint"), rs.getString("username"),
                    stored == null ? "" : cipher.decrypt(stored),
                    rs.getString("bucket_or_path"), rs.getString("region"));
        }, id, TenantScope.current(), type);
        if (info == null) {
            throw ApiException.badRequest("数仓连接不存在或类型不匹配：" + id + " / " + type);
        }
        return info;
    }

    /** 项目可选：有连接用连接，没连接用平台内置。 */
    public ConnectionInfo getOrDefault(Long id, String type) {
        return id == null ? null : get(id, type);
    }
}
