package com.lab.dataworks.mosbridge;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UX-MEDIAURL-7 · 桥接层媒体地址收口重写器（纯函数，无 Spring 依赖，可 javac 独立对拍）。
 *
 * <p><b>为什么收口在桥接层</b>：层①（BUG-LABEL-MEDIA-404-1，adapters/label_studio.py
 * :platform_media_url）只覆盖注入侧读取；但 {@code api/media.py:_ls_url} 的 S3/无缓存
 * 回退仍把绝对地址（{@code MEDIA_PUBLIC_BASE} 缺省 http://172.17.0.1:8090）写进任务
 * data，历史存量同理——这些形态经 {@code /api/mos/**} 原样透传给浏览器后，
 * 127.0.0.1 指向<b>观看者自己的电脑</b>，远程必死。逐个产出点打补丁枚举不完，
 * 唯一必经收口点就是本桥：浏览器侧 JSON 全部过这里（toChannelUrl 通道已支持
 * 相对 /media/**，见 web/src/components/ws/canvas/mediaSrc.ts）。</p>
 *
 * <p><b>改写规则（只剥「本平台自己签发」的绝对前缀，其余一律不动）</b>：</p>
 * <ul>
 *   <li>命中集 = host:port 精确匹配：常见自址族
 *       {127.0.0.1, localhost, ::1, 0.0.0.0, 172.17.0.1} × 默认媒体端口 8090，
 *       加上桥接上游 base-url 的 host:port（对方自址就是它），再加运维通过
 *       {@code PLATFORM_MEDIA_SELF_HOSTS}（逗号分隔 host[:port]）追加的条目。</li>
 *   <li>同机不同端口是别的服务（如 127.0.0.1:9000），<b>不误剥</b>；
 *       {@code MEDIA_PUBLIC_BASE} 配成对外可达地址时浏览器本来就能用，<b>不改</b>；
 *       外部 CDN/他服务透传。</li>
 *   <li>只动 {@code scheme://host[:port]/media/} 前缀，其后路径（含百分号编码、
 *       查询串）逐字节保持——重编码会把 {@code a%23b.png} 之类改坏（层①同款教训）。</li>
 *   <li>仅 JSON 响应体（Content-Type 含 json）与 3xx Location 头；非 UTF-8 体
 *       （压缩/二进制伪装）严格解码失败即<b>原样返回</b>——改坏响应比留旧形态更糟。</li>
 * </ul>
 */
public final class MosMediaUrls {

    /** 常见「自己看自己」地址族：这些 host 上的 8090 必是本机 mos。 */
    static final Set<String> CANON_HOSTS =
            Set.of("127.0.0.1", "localhost", "::1", "0.0.0.0", "172.17.0.1");

    /** mos 默认端口（api/media.py:_public_base 缺省与 mos 启动缺省 MODELONESTATION_PORT）。 */
    static final int DEFAULT_MEDIA_PORT = 8090;

    /** group1=方括号 IPv6，group2=普通 host，group3=端口（可空）；(?=/media/) 零宽定位——
     *  命中时 m.end() 恰在 /media/ 前，替换即剥 scheme://host[:port] 留相对 /media/。 */
    private static final Pattern ABS_MEDIA = Pattern.compile(
            "https?://(?:\\[([^/\\]]+)\\]|([A-Za-z0-9._-]+))(?::(\\d+))?(?=/media/)");

    private MosMediaUrls() {
    }

    /** 「本平台自签发」host:port 全集（小写 host，端口缺省按 scheme 补齐）。 */
    public static Set<String> allowedHostPorts(String upstreamBaseUrl, String extraCsv) {
        Set<String> out = new HashSet<>();
        for (String h : CANON_HOSTS) {
            out.add(h + ":" + DEFAULT_MEDIA_PORT);
        }
        addBase(out, upstreamBaseUrl);
        if (extraCsv != null) {
            for (String e : extraCsv.split(",")) {
                addEntry(out, e.trim());
            }
        }
        return out;
    }

    private static void addBase(Set<String> out, String base) {
        if (base == null || base.isBlank()) {
            return;
        }
        try {
            String s = base.trim();
            // 无 scheme 裸 host[:port] 配置按 http 解——与层① adapters 同款口径，
            // 别让 URI 把 "mos.internal:8123" 解析成 scheme=mos.internal 的哑谜
            if (!s.contains("://")) {
                s = "http://" + s;
            }
            URI u = URI.create(s);
            if (u.getHost() != null) {
                int port = u.getPort() > 0 ? u.getPort()
                        : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
                out.add(u.getHost().toLowerCase(Locale.ROOT) + ":" + port);
            }
        } catch (IllegalArgumentException ignored) {
            // 配置残缺不炸桥接通道：少一个自址条目=该形态透传（旧行为），不是新的坏行为
        }
    }

    /** 追加条目：host 或 host:port；IPv6 允许带/不带方括号。 */
    private static void addEntry(Set<String> out, String entry) {
        if (entry.isEmpty()) {
            return;
        }
        String host = entry;
        int port = DEFAULT_MEDIA_PORT;
        if (entry.startsWith("[")) {
            int rb = entry.indexOf(']');
            if (rb > 1) {
                host = entry.substring(1, rb);
                String rest = entry.substring(rb + 1);
                if (rest.startsWith(":") && rest.length() > 1) {
                    try {
                        port = Integer.parseInt(rest.substring(1));
                    } catch (NumberFormatException bad) {
                        return; // 带坏端口=不采信，别把误伤范围放进集合
                    }
                }
            }
        } else if (entry.indexOf(':') != entry.lastIndexOf(':')) {
            // ≥2 个冒号=裸 IPv6（不带方括号）：整串当 host，端口用默认，别把尾段误当端口
        } else if (entry.lastIndexOf(':') > 0 && !entry.contains("%")) {
            int c = entry.lastIndexOf(':');
            try {
                port = Integer.parseInt(entry.substring(c + 1));
                host = entry.substring(0, c);
            } catch (NumberFormatException plain) {
                // 冒号后不是数字端口——整串当 host，端口用默认
            }
        }
        if (!host.isEmpty()) {
            out.add(host.toLowerCase(Locale.ROOT) + ":" + port);
        }
    }

    /** 响应体是否按 JSON 口径处理（application/json、+json、jsonl/ndjson 都算）。 */
    public static boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
    }

    /** JSON 响应体收口：严格 UTF-8 解码失败（压缩/二进制）原样返回，绝不重编码。 */
    public static byte[] relativize(byte[] body, Set<String> allowed) {
        if (body == null || body.length == 0 || allowed.isEmpty()) {
            return body;
        }
        String text;
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            text = dec.decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException notUtf8) {
            return body;
        }
        String out = relativizeText(text, allowed);
        return out.equals(text) ? body : out.getBytes(StandardCharsets.UTF_8);
    }

    /** 文本收口：命中「自址 host:port + /media/」则剥成相对 /media/，其余逐字符原样。 */
    public static String relativizeText(String text, Set<String> allowed) {
        Matcher m = ABS_MEDIA.matcher(text);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            String host = m.group(1) != null ? m.group(1) : m.group(2);
            boolean https = text.charAt(m.start() + 4) == 's';
            // 端口串可能长得溢出（坏数据/攻击面文本里塞个假 URL）：解析失败=当不命中，
            // 绝不让一条坏 URL 把整条桥接请求炸成 500
            Integer port = https ? 443 : 80;
            if (m.group(3) != null) {
                try {
                    port = Integer.parseInt(m.group(3));
                } catch (NumberFormatException junk) {
                    continue;
                }
            }
            if (allowed.contains(host.toLowerCase(Locale.ROOT) + ":" + port)) {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                }
                // 前瞻零宽：m.end() 就停在 /media/ 上，只剥 scheme://host[:port]，
                // /media/ 及其后（编码/查询）由原串逐字符续上——绝不重复拼接
                sb.append(text, last, m.start());
                last = m.end();
            }
        }
        if (sb == null) {
            return text;
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /** 3xx Location 同源收口（整值是一条自址媒体绝对地址时才剥；其余原样，不静默改跳转语义）。 */
    public static String relativizeLocation(String loc, Set<String> allowed) {
        if (loc == null || loc.isEmpty() || !loc.startsWith("http")) {
            return loc;
        }
        // Location 可带 query：/media/ 前缀之后的部分由 relativizeText 原样保留
        String out = relativizeText(loc, allowed);
        return out;
    }
}
