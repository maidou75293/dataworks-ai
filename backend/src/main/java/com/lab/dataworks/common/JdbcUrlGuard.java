package com.lab.dataworks.common;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC URL / 出网地址的入口校验。
 *
 * <p>数据平台本来就要连数据库，所以不能简单按「内网/外网」封禁；这里防的是
 * 与连接地址无关的提权面：</p>
 * <ul>
 *   <li>MySQL Connector/J 的危险连接属性（autoDeserialize / queryInterceptors /
 *       allowLoadLocalInfile 等），历史上可被恶意服务端配合利用；</li>
 *   <li>调用方自带 driver 类名（Class.forName 加载任意类）；</li>
 *   <li>云元数据地址与 loopback（从后端 Pod 探测本机/云凭据）。</li>
 * </ul>
 *
 * <p><b>出网守卫（v448，第二轮审计 P1-2）</b>：黑名单只管 loopback/元数据，
 * 实测 10.43.0.1（k8s API ClusterIP）、kubernetes.default.svc、NodePort 都能连；
 * Kafka bootstrap 更是完全没走守卫。现在统一改为「默认拒绝内网 + 显式白名单」：
 * 私有网段（10/8、172.16/12、192.168/16、100.64/10）、集群 Service/Pod 网段、
 * 链路本地与云元数据、{@code *.svc} / {@code kubernetes.default} 一律拒绝，
 * 平台自己的基础设施（演示库、Kafka、Doris FE）通过 {@code platform.egress.allow}
 * 按 {@code host} 或 {@code host:port} 放行；域名会先解析再按解析出的 IP 判定。</p>
 *
 * <p><b>S2（2026-10-26 泳道 3 扫描）</b>：解析改成「取全部目标主机」而不是只取
 * {@code //host:port}。MySQL Connector/J 认 {@code address=(host=…)(port=…)} 与
 * 多主机写法，旧解析把整串当域名交给 DNS，解析失败又被放过，于是内网地址绕过守卫
 * 真实建连（实测 {@code elapsedMs} 5005ms vs 1ms 就是内网端口扫描读数）。现在：
 * 一个 URL 里的每个目标都单独走 blocked/白名单判定；主机位含 {@code ( ) = % , 空格 @ \}
 * 等非法字符、或写得出不可能成立的地址，一律 400 而不是放行；十进制/八进制/十六进制/
 * 短写（{@code 2130706433}、{@code 127.1}、{@code 0x7f000001}、{@code 0177.0.0.1}）
 * 先归一成点分十进制再判网段，避免和驱动的 {@code InetAddress} 口径不一致。</p>
 *
 * <p>只用于用户可提交的地址；后端内部自己拼的 URL 不需要经过这里。</p>
 */
public final class JdbcUrlGuard {

    private static final Set<String> SCHEMES = Set.of(
            "mysql", "mariadb", "tidb",
            "postgresql", "oracle", "sqlserver", "clickhouse",
            "db2", "hive2", "presto", "trino");

    /** 参数名大小写不敏感，且 URL 里可能做过百分号编码。 */
    private static final Pattern DANGEROUS_PARAM = Pattern.compile(
            "(?i)(?:^|[?&;])("
                    + "autodeserialize|allowloadlocalinfile|allowloadlocalinfileinpath|"
                    + "allowurlinlocalinfile|"
                    + "queryinterceptors|statementinterceptors|connectionlifecycleinterceptors|"
                    + "socketfactory|sslfactory|propertiestransform|"
                    + "detectcustomcollations|allowmultiqueries|allowlocalinfile|uselocalinfile"
                    + ")=");

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "169.254.169.254", "100.100.100.200", "metadata", "metadata.google.internal",
            "fd00:ec2::254", "0.0.0.0", "::", "[::]", "localhost");

    /** 集群内部地址的关键字：Service DNS、k8s API、云厂商元数据主机名。 */
    private static final Set<String> BLOCKED_SUFFIXES = Set.of(
            ".svc", ".svc.cluster.local", ".local", ".internal");
    private static final Set<String> BLOCKED_NAMES = Set.of(
            "kubernetes", "kubernetes.default", "kubernetes.default.svc",
            "kubernetes.default.svc.cluster.local", "metadata.google.internal");

    /**
     * 平台自己的基础设施白名单。{@code platform.egress.allow} 会**追加**到这里
     * （逗号分隔，支持 {@code host}、{@code host:port}、含 {@code *} 的通配）。
     * 这里只放演示/内置数据源，不含 k8s API 与 NodePort 面板地址。
     */
    private static volatile List<String> allowlist = parseAllowlist(String.join(",",
            "postgres.dataworks-test-postgres.svc.cluster.local",
            "mysql84.dataworks-test-mysql.svc.cluster.local",
            "mysql.dataworks-test-mysql.svc.cluster.local",
            "kafka.dataworks-test-kafka.svc.cluster.local",
            // 连接器验收用的测试基础设施命名空间（PG/MySQL/Kafka/ClickHouse/…）。
            // 这个是**测试沙箱**白名单；生产应改成逐个显式主机。
            "*.dataworks-test-*.svc.cluster.local",
            "kafka-demo.dw-dev.svc.cluster.local",
            "doris-fe.dw-dev.svc.cluster.local",
            "minio.dw-dev.svc.cluster.local",
            "postgres.dw-dev.svc.cluster.local",
            // Spark Thrift Server（常驻）：平台内部基础设施，脚本引擎 SPARK 走它
            "spark-thrift.dw-spark.svc.cluster.local"));
            // NodePort 形态入口（<节点IP>:30432/<节点IP>:9030）不再内置：
            // REQ-OSS-SEC-CLEAN-1 起经 platform.egress.allow（env PLATFORM_EGRESS_ALLOW）追加

    private JdbcUrlGuard() {
    }

    /**
     * 启动时由 {@link EgressPolicy} 注入 {@code platform.egress.allow}。
     * 这是**追加**而不是整体替换：代码里的内置基础设施白名单永远生效，
     * 避免配了一个环境变量就把演示库/Doris FE 全部挡在门外。
     */
    public static void addAllowlist(String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        List<String> merged = new ArrayList<>(allowlist);
        for (String entry : parseAllowlist(csv)) {
            if (!merged.contains(entry)) {
                merged.add(entry);
            }
        }
        allowlist = List.copyOf(merged);
    }

    /** 测试用：重置为内置白名单。 */
    public static void resetAllowlist() {
        allowlist = parseAllowlist(String.join(",",
                "postgres.dataworks-test-postgres.svc.cluster.local",
                "mysql84.dataworks-test-mysql.svc.cluster.local",
                "mysql.dataworks-test-mysql.svc.cluster.local",
                "kafka.dataworks-test-kafka.svc.cluster.local",
                "*.dataworks-test-*.svc.cluster.local",
                "kafka-demo.dw-dev.svc.cluster.local",
                "doris-fe.dw-dev.svc.cluster.local",
                "minio.dw-dev.svc.cluster.local",
                "postgres.dw-dev.svc.cluster.local",
                "spark-thrift.dw-spark.svc.cluster.local"));
                // NodePort 形态入口经 platform.egress.allow 追加（见上）
    }

    private static List<String> parseAllowlist(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return List.of();
        }
        for (String part : csv.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    public static String validate(String url) {
        if (url == null || url.isBlank()) {
            throw ApiException.badRequest("JDBC URL 不能为空");
        }
        String trimmed = url.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw ApiException.badRequest("JDBC URL 包含非法控制字符");
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:")) {
            throw ApiException.badRequest("JDBC URL 必须以 jdbc: 开头");
        }
        String rest = trimmed.substring(5);
        int colon = rest.indexOf(':');
        if (colon <= 0) {
            throw ApiException.badRequest("JDBC URL 缺少数据库类型，例如 jdbc:postgresql://...");
        }
        String scheme = rest.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme)) {
            throw ApiException.badRequest("不支持的 JDBC 类型: " + scheme);
        }
        String decoded = decode(trimmed);
        if (DANGEROUS_PARAM.matcher(decoded).find()) {
            throw ApiException.badRequest("JDBC URL 包含不安全的连接参数（禁止反序列化/本地文件/拦截器类参数）");
        }
        // 凭据不能藏在 URL 里：会明文落库并出现在列表接口
        if (decoded.matches("(?s).*jdbc:[a-z0-9]+://[^/@\\s]+@.*")
                || decoded.matches("(?s).*[?&;](password|pwd)=.*")) {
            throw ApiException.badRequest("JDBC URL 不能内嵌口令，请使用独立的用户名/密码字段");
        }
        String authority = rest.substring(colon + 1);
        // S2：按驱动口径解析出**全部**目标主机（多主机故障转移、MySQL 的
        // address=(host=…)(port=…) 写法），逐个走出网判定；解析不出合法主机位就 400，
        // 不再像旧版那样把整串当域名交给 DNS、解析失败又放过。
        for (Target target : targetsOf(scheme, authority)) {
            validateDestination(target.host(), target.port());
            // S1：禁止把数据源指向平台自己的元数据库（platform 库 / 内置 PG）
            PlatformInternalDb.assertNotPlatformDatabase(target);
        }
        PlatformInternalDb.assertNotPlatformSchema(decoded);
        return trimmed;
    }

    /**
     * JDBC URL 里的一个连接目标。
     *
     * @param scheme   驱动类型（{@code mysql} / {@code postgresql} / …），小写
     * @param host     主机位（可能带 IPv6 方括号）
     * @param port     端口；URL 里没写时为 -1（由调用方按驱动默认端口理解）
     * @param database {@code //host:port/} 之后的第一段路径，拿不到时为空串
     */
    public record Target(String scheme, String host, int port, String database) {
    }

    /** MySQL 多主机写法：{@code address=(host=h)(port=p)}，可带外层括号、可逗号分隔多个。 */
    private static final Pattern ADDRESS_BLOCK = Pattern.compile(
            "(?is)\\baddress\\s*=\\s*\\(([^()]*)\\)");
    private static final Pattern ADDRESS_ITEM = Pattern.compile(
            "(?is)\\b(host|port)\\s*=\\s*([^(),;\\s]+)");
    /** Oracle thin：{@code @host:1521:sid} / {@code @//host:1521/service}。 */
    private static final Pattern ORACLE_TAIL = Pattern.compile("(?is).*@\\s*/*/?([^;,\\s]+)");
    /** 其它驱动把主机写在参数位：{@code ;host=} / {@code ?serverName=} / {@code ;nodeName=}。 */
    private static final Pattern HOST_PARAM = Pattern.compile(
            "(?i)(?:^|[;?&])(?:host|hostname|servername|nodename|node|server)\\s*=\\s*([^;,\\s&?]+)");
    private static final Pattern PORT_PARAM = Pattern.compile(
            "(?i)[;?&](?:port|serverport)\\s*=\\s*(\\d{1,5})");

    /** 主机位只允许这些字符：字母（含 IDN）、数字、点、横线、下划线，IPv6 另有方括号与冒号。 */
    private static final Pattern LEGAL_HOST = Pattern.compile(
            "[a-z0-9._\\-\\p{L}]+|\\[[0-9a-f:.]+]", Pattern.CASE_INSENSITIVE);

    /**
     * 解析 JDBC URL authority 部分的全部连接目标。
     *
     * <p>解析不出任何一个合法主机位时直接 400：旧实现返回 {@code null} 让调用方跳过守卫，
     * {@code address=(host=127.0.0.1)(port=8080)} 就是这么绕过去的（泳道 3 扫描 S2）。</p>
     */
    public static List<Target> targetsOf(String scheme, String authority) {
        String driver = scheme == null ? "" : scheme.trim().toLowerCase(Locale.ROOT);
        String text = authority == null ? "" : authority.trim();
        List<Target> out = new ArrayList<>();
        collectAddressBlocks(driver, text, out);
        if (out.isEmpty()) {
            collectDoubleSlash(driver, text, out);
        }
        if (out.isEmpty()) {
            collectOracleOrParams(driver, text, out);
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest(
                    "JDBC URL 解析不出数据库主机地址，请使用 jdbc:<类型>://host:port/库 写法");
        }
        for (Target target : out) {
            assertLegalHost(target.host());
        }
        return List.copyOf(out);
    }

    /** MySQL 的 {@code address=(host=…)(port=…)}：取出真实 host/port，而不是把整段当主机名。 */
    private static void collectAddressBlocks(String scheme, String text, List<Target> out) {
        Matcher blocks = ADDRESS_BLOCK.matcher(text);
        while (blocks.find()) {
            String host = null;
            int port = -1;
            Matcher items = ADDRESS_ITEM.matcher(blocks.group(1));
            while (items.find()) {
                String value = items.group(2).trim();
                if ("host".equalsIgnoreCase(items.group(1))) {
                    host = value;
                } else {
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        port = -1;
                    }
                }
            }
            if (host != null && !host.isBlank()) {
                out.add(new Target(scheme, host, port, databaseOf(text)));
            }
        }
    }

    /** 常见写法：{@code //host:port,host2:port2/库?参数}。 */
    private static void collectDoubleSlash(String scheme, String text, List<Target> out) {
        int slash = text.indexOf("//");
        if (slash < 0) {
            return;
        }
        String after = text.substring(slash + 2);
        int end = after.length();
        for (char stop : new char[]{'/', '?', ';'}) {
            int idx = after.indexOf(stop);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        String hosts = after.substring(0, end);
        int at = hosts.lastIndexOf('@');
        if (at >= 0) {
            // userinfo：内嵌口令在 validate() 里已被拒绝，这里只取主机段
            hosts = hosts.substring(at + 1);
        }
        String database = databaseOf(text);
        for (String item : hosts.split(",")) {
            String host = item.trim();
            if (host.isEmpty()) {
                continue;
            }
            out.add(new Target(scheme, hostOfLiteral(host), portOfLiteral(host), database));
        }
    }

    /** Oracle thin 与「主机写在参数里」的方言（sqlserver/hive 的 {@code ;host=} 形式）。 */
    private static void collectOracleOrParams(String scheme, String text, List<Target> out) {
        Matcher oracle = ORACLE_TAIL.matcher(text);
        if (text.contains("@") && oracle.matches()) {
            String tail = oracle.group(1);
            String[] parts = tail.split(":");
            String host = parts.length > 0 ? parts[0].trim() : "";
            int port = -1;
            if (parts.length > 1) {
                try {
                    port = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    port = -1;
                }
            }
            if (!host.isEmpty()) {
                out.add(new Target(scheme, host, port, parts.length > 2 ? parts[2].trim() : ""));
                return;
            }
        }
        Matcher hostMatcher = HOST_PARAM.matcher(text);
        while (hostMatcher.find()) {
            String host = hostMatcher.group(1).trim();
            if (host.isEmpty()) {
                continue;
            }
            int port = -1;
            Matcher portMatcher = PORT_PARAM.matcher(text);
            if (portMatcher.find()) {
                try {
                    port = Integer.parseInt(portMatcher.group(1));
                } catch (NumberFormatException ignored) {
                    port = -1;
                }
            }
            out.add(new Target(scheme, host, port, databaseOf(text)));
        }
    }

    /** {@code //} 之后第一段路径当库名；拿不到时返回空串。 */
    private static String databaseOf(String text) {
        int slash = text.indexOf("//");
        if (slash < 0) {
            return "";
        }
        String after = text.substring(slash + 2);
        int end = after.length();
        for (char stop : new char[]{'?', ';'}) {
            int idx = after.indexOf(stop);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        String path = after.substring(0, end);
        int first = path.indexOf('/');
        if (first < 0) {
            return "";
        }
        return path.substring(first + 1).trim();
    }

    private static String hostOfLiteral(String hostPort) {
        String value = hostPort.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close > 0 ? value.substring(0, close + 1) : value;
        }
        int colon = value.lastIndexOf(':');
        return colon >= 0 ? value.substring(0, colon) : value;
    }

    private static int portOfLiteral(String hostPort) {
        String value = hostPort.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0 && close + 1 < value.length() && value.charAt(close + 1) == ':') {
                try {
                    return Integer.parseInt(value.substring(close + 2));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            return -1;
        }
        int colon = value.lastIndexOf(':');
        if (colon < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(value.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 主机位字符集校验：驱动能接受、守卫解析不了的写法（括号、编码、空格）一律拒绝。 */
    private static void assertLegalHost(String host) {
        String value = host == null ? "" : host.trim();
        if (value.isEmpty() || !LEGAL_HOST.matcher(value).matches()) {
            throw ApiException.badRequest("JDBC URL 的主机地址不合法: " + host
                    + "（只允许域名或 IP，禁止括号/等号/百分号编码等写法）");
        }
    }

    /**
     * 非 JDBC 入口（告警 Webhook、S3 endpoint、HTTP 回调）的目标校验。
     *
     * <p>S4：告警渠道以前只有一份硬编码黑名单（本机 + 云元数据），内网网段和集群
     * Service DNS 全放行。这里复用同一套出网判定，但<b>不吃数据源白名单</b>：
     * 白名单是给「连数据库」用的，不能顺手变成「任意 HTTP 出网」的通行证。</p>
     */
    public static void validateHttpTarget(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            throw ApiException.badRequest("目标地址不能为空");
        }
        String host;
        int port = -1;
        try {
            String candidate = value.contains("://") ? value : "http://" + value;
            java.net.URI uri = java.net.URI.create(candidate);
            host = uri.getHost();
            port = uri.getPort();
        } catch (Exception e) {
            throw ApiException.badRequest("目标地址格式不正确: " + url);
        }
        if (host == null || host.isBlank()) {
            throw ApiException.badRequest("目标地址解析不出主机名: " + url);
        }
        assertLegalHost(host);
        validateDestination(host, port < 0 ? defaultPort(value) : port, false);
    }

    private static int defaultPort(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            return 443;
        }
        if (lower.startsWith("smtp://") || lower.startsWith("smtps://")) {
            return lower.startsWith("smtps://") ? 465 : 25;
        }
        return 80;
    }

    /**
     * 出网守卫：任意用户可提交的主机（JDBC、Kafka bootstrap、Mongo/Redis/ClickHouse）。
     *
     * <p>顺序：平台白名单 → 本机/元数据/集群名 → 域名解析后的 IP → 私有网段。
     * 解析失败不拦（连不上会在连接阶段失败，不存在探测价值）；解析成功则每个
     * A/AAAA 记录都必须通过，避免「一个域名混解析到内网」。</p>
     */
    public static void validateDestination(String host, int port) {
        validateDestination(host, port, true);
    }

    /**
     * 出网守卫。
     *
     * @param honorEgressAllowlist 数据源链路传 {@code true}（平台内置库要放行）；
     *                             告警 Webhook / HTTP 回调传 {@code false}，
     *                             只认「公网可用」这一档。
     */
    public static void validateDestination(String host, int port, boolean honorEgressAllowlist) {
        if (host == null || host.isBlank()) {
            return;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (honorEgressAllowlist && allowlisted(value, port)) {
            return;
        }
        if (blockedHost(value)) {
            throw ApiException.badRequest("不允许连接本机、云元数据或集群内部地址: " + host);
        }
        // S2：IP 形状的写法（127.1 / 2130706433 / ::ffff:a9fe:a9fe）先按驱动的口径归一，
        // 再判网段。旧实现要求「四段都是 0–255 的十进制」才算字面 IP，其余一律走 DNS 分支，
        // 于是这些写法既没落进私网判定、也没落进白名单判定，被原样放行。
        InetAddress literal = asIpLiteral(value);
        if (literal != null) {
            if (blockedAddress(literal)) {
                throw ApiException.badRequest("不允许连接内网或保留地址: " + host
                        + (literal.getHostAddress().equals(value) ? "" : " → " + literal.getHostAddress()));
            }
            return;
        }
        // 长得像 IP 却解析不出来（1.2.3.4.5、256.1.1.1）：拒绝，不交给 DNS 蒙混
        if (looksLikeIp(value)) {
            throw ApiException.badRequest("IP 地址写法不合法: " + host);
        }
        for (String suffix : BLOCKED_SUFFIXES) {
            if (value.endsWith(suffix)) {
                throw ApiException.badRequest("不允许连接集群内部服务地址: " + host);
            }
        }
        if (BLOCKED_NAMES.contains(value)) {
            throw ApiException.badRequest("不允许连接集群内部服务地址: " + host);
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(value)) {
                if (blockedAddress(address)) {
                    throw ApiException.badRequest(
                            "不允许连接内网地址: " + host + " → " + address.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // 域名解析不了：连接阶段必然失败。到这里已经过完「字符集 + IP 归一 + 网段 +
            // 集群后缀」四道判定，不存在把内网地址藏在解析失败里的空间。
        }
    }

    /** 按 Java/驱动同口径把 IP 字面量解析出来；不是 IP 字面量时返回 null（不走 DNS）。 */
    private static InetAddress asIpLiteral(String value) {
        String text = value;
        int percent = text.indexOf('%');
        if (percent > 0) {
            text = text.substring(0, percent);      // fe80::1%eth0 的 zone id
        }
        boolean ipv4Shape = text.matches("[0-9.]+");
        boolean ipv6Shape = text.contains(":") && text.matches("[0-9a-fA-F:.]+");
        if (!ipv4Shape && !ipv6Shape) {
            return null;
        }
        try {
            return InetAddress.getByName(text);      // 字面量不会触发 DNS
        } catch (Exception e) {
            return null;
        }
    }

    /** 看起来像 IP（纯数字加点 / 含冒号的十六进制串 / 0x 十六进制），但驱动和我们都解析不出来。 */
    private static boolean looksLikeIp(String value) {
        return value.matches("[0-9.]+")
                || value.matches("(?i)0x[0-9a-f.]+")
                || (value.contains(":") && value.matches("[0-9a-fA-F:.]+"));
    }

    /**
     * Kafka bootstrap.servers（逗号分隔）：每个 host[:port] 都走出网守卫。
     * 数据源保存、连接测试、topic 预览三条路径共用，避免再出现「只堵了 JDBC」。
     */
    public static void validateBootstrapServers(String bootstrap) {
        if (bootstrap == null || bootstrap.isBlank()) {
            return;
        }
        for (String part : bootstrap.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int colon = item.lastIndexOf(':');
            String host = colon > 0 ? item.substring(0, colon) : item;
            int port = 9092;
            if (colon > 0) {
                try {
                    port = Integer.parseInt(item.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    port = -1;
                }
            }
            validateDestination(host, port);
        }
    }

    /** 白名单：精确 host、host:port、*.suffix 三种写法。 */
    private static boolean allowlisted(String host, int port) {
        for (String entry : allowlist) {
            if (entry.indexOf(':') > 0 && !entry.startsWith("[")) {
                // host:port 形式：端口必须一致
                int colon = entry.lastIndexOf(':');
                if (entry.substring(0, colon).equals(host)
                        && entry.substring(colon + 1).equals(String.valueOf(port))) {
                    return true;
                }
                continue;
            }
            if (entry.contains("*")) {
                // *.suffix 或中间通配（例如 *.dataworks-test-*.svc.cluster.local）
                String regex = entry.replace(".", "\\.").replace("*", ".*");
                if (host.matches(regex)) {
                    return true;
                }
                continue;
            }
            if (entry.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /** 精确名单之外还要覆盖 127/8、0.0.0.0/8、169.254/16、::1 等写法。 */
    private static boolean blockedHost(String value) {
        if (BLOCKED_HOSTS.contains(value) || BLOCKED_HOSTS.contains("[" + value + "]")) {
            return true;
        }
        return value.equals("::1")
                || value.equals("::")
                || value.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
                || value.matches("0\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
                || value.matches("169\\.254\\.\\d{1,3}\\.\\d{1,3}")
                || value.matches("::ffff:127\\..*")
                || value.equals("0:0:0:0:0:0:0:1");
    }

    /**
     * 保留/内网地址判定：按字节判，覆盖 0/8、10/8、100.64/10、127/8、169.254/16、
     * 172.16/12、192.168/16、224/4 组播与保留段；IPv6 侧覆盖 loopback、未指定地址、
     * link-local、ULA（fc00::/7），并把 IPv4 映射地址（{@code ::ffff:a9fe:a9fe}）
     * 还原成 v4 再判一次——这正是 S4 里绕过黑名单的那个写法。
     */
    private static boolean blockedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes == null) {
            return true;
        }
        if (bytes.length == 4) {
            return blockedIpv4(bytes[0] & 0xff, bytes[1] & 0xff);
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        int first = bytes[0] & 0xff;
        if ((first & 0xfe) == 0xfc) {
            return true;                                    // fc00::/7 唯一本地地址
        }
        if (bytes.length == 16 && isIpv4Embedded(bytes)) {
            return blockedIpv4(bytes[12] & 0xff, bytes[13] & 0xff);
        }
        return false;
    }

    /** {@code ::ffff:a.b.c.d}（IPv4 映射）与 {@code ::a.b.c.d}（IPv4 兼容）两种写法。 */
    private static boolean isIpv4Embedded(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        // 前 10 字节全 0：后 6 字节是 ffff: 映射，或全 0 的兼容写法
        return (bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff)
                || (bytes[10] == 0 && bytes[11] == 0);
    }

    private static boolean blockedIpv4(int a, int b) {
        return a == 0
                || a == 10
                || (a == 100 && b >= 64 && b <= 127)
                || a == 127
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || a >= 224;
    }

    /** 根据 URL 返回后端内置的驱动类；未知/未内置时返回 null，交给 DriverManager 服务发现。 */
    public static String driverFor(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")
                || lower.startsWith("jdbc:tidb:")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        if (lower.startsWith("jdbc:postgresql:")) {
            return "org.postgresql.Driver";
        }
        if (lower.startsWith("jdbc:oracle:")) {
            return "oracle.jdbc.OracleDriver";
        }
        // Spark Thrift Server 走 HiveServer2 协议；用 Kyuubi 的 shaded 客户端
        if (lower.startsWith("jdbc:hive2:") || lower.startsWith("jdbc:kyuubi:")) {
            return "org.apache.kyuubi.jdbc.KyuubiHiveDriver";
        }
        // SQLServer/DB2 等驱动不在后端 classpath：返回 null 交给 DriverManager，
        // 不要 Class.forName 一个必然不存在的类。
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /**
     * 从任意「scheme://host:port/path」形态里取 host（供非 JDBC 入口复用）。
     * 解析不出来返回 null，由调用方决定拒绝还是放过。
     */
    public static String hostOfUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String value = url.trim();
        int slash = value.indexOf("//");
        String after = slash < 0 ? value : value.substring(slash + 2);
        int end = after.length();
        for (char stop : new char[]{'/', '?', ';'}) {
            int idx = after.indexOf(stop);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        String authority = after.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.isEmpty()) {
            return null;
        }
        return authority;
    }
}
