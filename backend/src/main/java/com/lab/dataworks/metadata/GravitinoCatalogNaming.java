package com.lab.dataworks.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * DORIS 数据源 → Gravitino Catalog 的命名规则（V101）。
 *
 * <p>公式：{@code <tenant>_<slug(name)>}。Catalog 名会出现在 REST URL、Doris 的
 * {@code catalog.db.table} 与 SQL 标识符里，因此只允许 {@code [a-z0-9_]}，
 * 且必须以字母或下划线开头、总长不超过 64（Doris 标识符上限）。</p>
 *
 * <p>slug 规则：转小写 → 非 {@code [a-z0-9]} 连续段折叠成 {@code _} →
 * 去掉首尾下划线。数字开头补 {@code ds_}。非 ASCII 名称（例如纯中文）清洗后
 * 为空，此时用 {@code ds_<sha256 前 8 位>} 兜底，保证同名稳定且不碰撞；
 * 超长名称保留前缀并用 {@code _<hash8>} 收尾。规则是确定性的：同一个租户
 * 与数据源名永远生成同一个 Catalog 名，重复保存才能走 ALTER 而不是重复 CREATE。</p>
 */
public final class GravitinoCatalogNaming {

    /** Doris / Gravitino 标识符上限（取更保守的 64）。 */
    public static final int MAX_LENGTH = 64;

    /** 租户前缀最多占用的长度，给数据源名留足空间。 */
    private static final int TENANT_BUDGET = 24;
    private static final int HASH_LENGTH = 8;
    private static final Pattern NON_IDENTIFIER = Pattern.compile("[^a-z0-9]+");
    private static final Pattern REPEATED_UNDERSCORE = Pattern.compile("_+");

    private GravitinoCatalogNaming() {
    }

    public static String catalogName(String tenant, String datasourceName) {
        String prefix = tenantPrefix(tenant);
        String namePart = slug(datasourceName, "");
        if (namePart.isEmpty()) {
            namePart = datasourceName == null || datasourceName.isBlank()
                    ? "ds" : "ds_" + hash(datasourceName);
        }

        int room = MAX_LENGTH - prefix.length();
        if (namePart.length() > room) {
            int keep = Math.max(1, room - HASH_LENGTH - 1);
            String head = trimUnderscore(namePart.substring(0, keep));
            if (head.isEmpty()) {
                head = "ds";
            }
            namePart = head + "_" + hash(datasourceName);
        }
        if (namePart.length() > room) {
            namePart = namePart.substring(0, room);
        }
        return prefix + namePart;
    }

    /**
     * 租户命名空间前缀（{@code <slug(tenant)>_}）。
     *
     * <p>运维清理能力用它划定「当前租户能确定归属的 Catalog」：前缀之外的一律视为
     * 平台级/其他租户资源，不参与清理。规则与 {@link #catalogName} 完全一致，
     * 避免出现「生成用一套规则、清理用另一套规则」的漏删/误删。</p>
     */
    public static String tenantPrefix(String tenant) {
        String tenantPart = slug(tenant, "default");
        if (tenantPart.length() > TENANT_BUDGET) {
            tenantPart = tenantPart.substring(0, TENANT_BUDGET - HASH_LENGTH - 1)
                    + "_" + hash(tenant);
        }
        return tenantPart + "_";
    }

    /** 把任意文本清洗成小写标识符片段；清洗后为空时返回 fallback。 */
    static String slug(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        value = NON_IDENTIFIER.matcher(value).replaceAll("_");
        value = REPEATED_UNDERSCORE.matcher(value).replaceAll("_");
        value = trimUnderscore(value);
        if (value.isEmpty()) {
            return fallback;
        }
        if (Character.isDigit(value.charAt(0))) {
            value = "ds_" + value;
        }
        return value;
    }

    private static String trimUnderscore(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }

    private static String hash(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(HASH_LENGTH);
            for (int i = 0; i < HASH_LENGTH / 2; i++) {
                out.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法；真缺了也不能让保存流程静默写入错误名字
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
