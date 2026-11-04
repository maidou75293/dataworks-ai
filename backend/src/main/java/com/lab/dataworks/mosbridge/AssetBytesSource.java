package com.lab.dataworks.mosbridge;

/**
 * 资产文件本体的读取端口（REQ-FW-5 搬运器）。
 *
 * <p>单独设口是为了搬运器可测：单测用内存桩，生产实现 {@link S3AssetBytesSource}
 * 走与 {@code asset/AssetService} 同一套 MinIO（同实例不同桶，见
 * docs/integration/media-ref.md §1.3）。</p>
 */
public interface AssetBytesSource {

    /** 按 V102 的 uri（{@code s3://<bucket>/<key>}）读出对象字节。 */
    byte[] read(String uri) throws Exception;
}
