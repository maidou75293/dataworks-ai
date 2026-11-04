package com.lab.dataworks.mosbridge;

import java.util.Map;

/**
 * mos 媒体库的写端口（REQ-FW-5 搬运器专用）。
 *
 * <p>{@link MosGateway} 是 FW-2 的只读状态端口，本接口只承担「把文件本体交给 mos」这一件事：
 * 生产实现走口令登录 + {@code POST /api/media/upload}（multipart），单测用内存桩——
 * 搬运器的幂等/续传/失败中断判定不需要真实网络。</p>
 */
public interface MosMediaGateway {

    /** 配置齐全（base-url + 服务账号口令）才允许出站；缺配置时搬运器如实报原因。 */
    boolean enabled();

    /** 缺配置时给日志/接口 503 用的一句话原因。 */
    String disabledReason();

    /**
     * POST /api/media/upload（multipart：file/kind=auto/folder），返回对方 {@code _meta}：
     * 键 {@code path}（媒体库相对路径，跨系统唯一引用形态）、{@code url}、{@code ls_url}、
     * {@code size}、{@code kind}。出处 mos {@code api/media.py:659 upload_media}、
     * {@code _meta}（media.py:216-224）。
     */
    Map<String, Object> uploadMedia(byte[] bytes, String filename, String folder) throws Exception;
}
