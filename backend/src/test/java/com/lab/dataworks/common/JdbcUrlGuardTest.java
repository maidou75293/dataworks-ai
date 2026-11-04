package com.lab.dataworks.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcUrlGuardTest {

    @Test
    void acceptsNormalUrls() {
        assertEquals("jdbc:postgresql://db:5432/x",
                JdbcUrlGuard.validate("jdbc:postgresql://db:5432/x"));
        assertEquals("jdbc:mysql://db:3306/x?useUnicode=true",
                JdbcUrlGuard.validate("jdbc:mysql://db:3306/x?useUnicode=true"));
        assertEquals("org.postgresql.Driver",
                JdbcUrlGuard.driverFor("jdbc:postgresql://db:5432/x"));
    }

    @Test
    void rejectsDangerousMysqlProperties() {
        for (String param : new String[]{
                "autoDeserialize=true",
                "allowLoadLocalInfile=true",
                "allowUrlInLocalInfile=true",
                "queryInterceptors=com.evil.X",
                "statementInterceptors=com.evil.X",
                "detectCustomCollations=true",
                "allowMultiQueries=true"}) {
            assertThrows(ApiException.class,
                    () -> JdbcUrlGuard.validate("jdbc:mysql://db/x?" + param),
                    param);
        }
    }

    @Test
    void rejectsEncodedDangerousProperty() {
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:mysql://db/x?autoDeserialize%3Dtrue"));
    }

    @Test
    void rejectsEmbeddedCredentials() {
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:mysql://user:pass@db/x"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:postgresql://db/x?password=secret"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:postgresql://db/x?pwd=secret"));
    }

    @Test
    void rejectsMetadataAndLoopbackHosts() {
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:mysql://169.254.169.254/latest"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:mysql://100.100.100.200/x"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:mysql://localhost/x"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:mysql://localhost./x"));
        for (String host : new String[]{"127.0.0.1", "127.0.0.2", "0.0.0.0",
                "[::1]", "[::]", "127.1.2.3"}) {
            assertThrows(ApiException.class,
                    () -> JdbcUrlGuard.validate("jdbc:postgresql://" + host + ":5432/db"),
                    host);
        }
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:oracle:thin:@127.0.0.1:1521:xe"));
    }

    @Test
    void rejectsClassLoadingParameters() {
        for (String param : new String[]{
                "socketFactory=java.lang.Runtime",
                "sslFactory=com.evil.X",
                "connectionLifecycleInterceptors=com.evil.X",
                "allowLoadLocalInfileInPath=/",
                "propertiesTransform=com.evil.X"}) {
            assertThrows(ApiException.class,
                    () -> JdbcUrlGuard.validate("jdbc:mysql://db/x?" + param), param);
        }
    }

    @Test
    void rejectsUnknownSchemeAndControlChars() {
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:sqlite:/tmp/x.db"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:postgresql://db/x\nmalicious"));
    }

    // ---------------------------------------------------------------- S2（2026-10-26 扫描 P1）

    /**
     * S2 的核心绕过：MySQL 的 {@code address=(host=…)(port=…)} 写法。
     *
     * <p>旧解析只认 {@code //host:port}，把 {@code address=(host=127.0.0.1)(port=8080)}
     * 整串当域名交给 DNS，解析失败又被显式放过，于是守卫放行、驱动照连——实测
     * {@code /api/datasources/test} 对 {@code 127.0.0.1}、{@code 169.254.169.254}、
     * Pod 网段都回了 200，{@code elapsedMs} 5005ms（超时）与 1ms（拒绝）的差值
     * 就是内网端口扫描读数。现在每个目标都单独走 blocked 判定。</p>
     */
    @Test
    void rejectsMysqlAddressSyntaxTargets() {
        for (String url : new String[]{
                "jdbc:mysql://address=(host=127.0.0.1)(port=8080)/test",
                "jdbc:mysql://address=(host=127.0.0.1)(port=9)/test",
                "jdbc:mysql://address=(host=169.254.169.254)(port=80)/test",
                "jdbc:mysql://address=(host=10.42.1.58)(port=8080)/test",
                "jdbc:mysql://address=(host=10.42.2.24)(port=5432)/test",
                "jdbc:mysql://address=(host=10.43.0.1)(port=443)/test",
                "jdbc:mysql://(address=(host=192.168.1.1)(port=3306))/test",
                // 带 protocol 修饰的完整写法
                "jdbc:mysql://address=(protocol=tcp)(host=10.99.99.11)(port=30432)/test"}) {
            assertThrows(ApiException.class, () -> JdbcUrlGuard.validate(url), url);
        }
    }

    /** 同一个语法指向公网/合法内网白名单地址时必须放行，否则就是「为了安全把语法删了」。 */
    @Test
    void stillAcceptsAddressSyntaxForLegitimateTargets() {
        assertEquals("jdbc:mysql://address=(host=db.example.com)(port=3306)/shop",
                JdbcUrlGuard.validate("jdbc:mysql://address=(host=db.example.com)(port=3306)/shop"));
        assertEquals("jdbc:mysql://address=(host=mysql84.dataworks-test-mysql.svc.cluster.local)(port=3306)/shop",
                JdbcUrlGuard.validate("jdbc:mysql://address=(host=mysql84.dataworks-test-mysql"
                        + ".svc.cluster.local)(port=3306)/shop"));
    }

    /**
     * 十进制 / 短写 / 十六进制 / IPv4-mapped 这些「看起来不是点分四段」的内网写法。
     *
     * <p>旧实现要求四段都是 0–255 的十进制才算字面 IP，其余走域名分支，
     * 于是 {@code 2130706433}、{@code 127.1}、{@code [::ffff:127.0.0.1]} 在**正常写法**下
     * 就能过守卫（扫描实测 C2-DS09/10/11/12 全部 200）。现在按驱动的
     * {@code InetAddress} 口径先归一再判段。</p>
     */
    @Test
    void rejectsLooseIpSpellingsOfPrivateAddresses() {
        for (String url : new String[]{
                "jdbc:mysql://2130706433:3306/test",          // = 127.0.0.1
                "jdbc:mysql://127.1:3306/test",               // = 127.0.0.1
                "jdbc:mysql://10.1:3306/test",                // = 10.0.0.1
                "jdbc:mysql://0x7f000001:3306/test",          // 十六进制写法，Java 解析不了 → 直接拒
                "jdbc:mysql://0:3306/test",                   // = 0.0.0.0
                "jdbc:postgresql://[::1]:5432/db",
                "jdbc:postgresql://[::ffff:127.0.0.1]:5432/db",
                "jdbc:postgresql://[::ffff:10.42.0.1]:5432/db",
                "jdbc:postgresql://[::ffff:a9fe:a9fe]:5432/db",   // 169.254.169.254
                "jdbc:mysql://1.2.3.4.5:3306/test",               // 五段：不是合法 IP，也不许冒充域名
                "jdbc:mysql://256.1.1.1:3306/test"}) {
            assertThrows(ApiException.class, () -> JdbcUrlGuard.validate(url), url);
        }
    }

    /** 公网/白名单地址不受影响；同一条 URL 里多主机只要有一段是内网就整条拒。 */
    @Test
    void rejectsAnyPrivateHostInMultiHostUrls() {
        assertEquals("jdbc:postgresql://a.example.com,b.example.com/db",
                JdbcUrlGuard.validate("jdbc:postgresql://a.example.com,b.example.com/db"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:postgresql://a.example.com,127.0.0.1:5432/db"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:postgresql://10.42.0.1:5432,a.example.com/db"));
    }

    /** 主机位含括号/等号/空格/百分号编码：驱动认、守卫解析不了 → 拒绝而不是放过。 */
    @Test
    void rejectsUnparsableHostPositions() {
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:mysql://address%3d(host%3d127.0.0.1)/test"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:mysql://address=(host=(nested))(port=3306)/test"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validate("jdbc:mysql://host name:3306/test"));
        // 完全没有主机位
        assertThrows(ApiException.class, () -> JdbcUrlGuard.validate("jdbc:mysql:///test"));
    }

    /** targetsOf 是解析口径本身：两种写法都要还原成同一组 host/port/db。 */
    @Test
    void parsesAllTargetForms() {
        assertEquals(List.of(new JdbcUrlGuard.Target("mysql", "10.42.1.58", 8080, "test")),
                JdbcUrlGuard.targetsOf("mysql", "//address=(host=10.42.1.58)(port=8080)/test"));
        List<JdbcUrlGuard.Target> multi =
                JdbcUrlGuard.targetsOf("postgresql", "//h1:5432,h2/db?currentSchema=x");
        assertEquals(2, multi.size());
        assertEquals("h1", multi.get(0).host());
        assertEquals(5432, multi.get(0).port());
        assertEquals("db", multi.get(0).database());
        assertEquals("h2", multi.get(1).host());
        assertEquals(-1, multi.get(1).port());
        assertEquals(List.of(new JdbcUrlGuard.Target("oracle", "db-host", 1521, "xe")),
                JdbcUrlGuard.targetsOf("oracle", "thin:@db-host:1521:xe"));
    }

    /** 白名单仍然生效（数据源链路要用内置库），但端口必须对上。 */
    @Test
    void allowlistStillAppliesToJdbcTargets() {
        assertDoesNotThrow(() -> JdbcUrlGuard.validate(
                "jdbc:postgresql://postgres.dataworks-test-postgres.svc.cluster.local:5432/sync_test"));
        assertThrows(ApiException.class, () -> JdbcUrlGuard.validateDestination(
                "postgres.dataworks-test-postgres.svc.cluster.local", 6666));
        // HTTP 出口不吃数据源白名单（S4）
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validateHttpTarget(
                        "http://minio.dw-dev.svc.cluster.local:9000/minio/health/live"));
        assertThrows(ApiException.class,
                () -> JdbcUrlGuard.validateHttpTarget("http://10.43.0.1:30432/"));
    }
}
