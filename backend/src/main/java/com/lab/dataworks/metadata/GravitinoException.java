package com.lab.dataworks.metadata;

/**
 * Gravitino 调用失败（可诊断）。
 *
 * <p>错误码固定，前端/验收脚本按码判定，不依赖某一条中文措辞；消息里带
 * HTTP 状态、请求方法与地址、Gravitino 返回的 error message，排障时不用翻日志。</p>
 */
public class GravitinoException extends RuntimeException {

    public static final String UNAVAILABLE = "GRAVITINO_UNAVAILABLE";
    public static final String NOT_FOUND = "GRAVITINO_NOT_FOUND";
    public static final String CONFLICT = "GRAVITINO_CONFLICT";
    public static final String FORBIDDEN = "GRAVITINO_FORBIDDEN";
    public static final String BAD_REQUEST = "GRAVITINO_BAD_REQUEST";
    public static final String ERROR = "GRAVITINO_ERROR";

    /** HTTP 状态；连接失败/超时为 0。 */
    private final int httpStatus;
    private final String code;
    private final String endpoint;
    /** 仅对「连接失败 / 5xx / 429」允许重试，业务 4xx 不重试。 */
    private final boolean transientFailure;

    public GravitinoException(String code, int httpStatus, String endpoint, String detail,
                              boolean transientFailure) {
        super(buildMessage(code, httpStatus, endpoint, detail));
        this.code = code;
        this.httpStatus = httpStatus;
        this.endpoint = endpoint;
        this.transientFailure = transientFailure;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String endpoint() {
        return endpoint;
    }

    public boolean transientFailure() {
        return transientFailure;
    }

    public boolean notFound() {
        return httpStatus == 404;
    }

    public boolean conflict() {
        return httpStatus == 409;
    }

    private static String buildMessage(String code, int status, String endpoint, String detail) {
        StringBuilder out = new StringBuilder(code).append(": ");
        out.append(switch (code) {
            case UNAVAILABLE -> "Gravitino 不可达或响应超时";
            case NOT_FOUND -> "Gravitino 对象不存在";
            case CONFLICT -> "Gravitino 对象已存在或状态冲突";
            case FORBIDDEN -> "Gravitino 拒绝访问（检查服务端鉴权/网络策略）";
            case BAD_REQUEST -> "Gravitino 拒绝了请求参数";
            default -> "Gravitino 调用失败";
        });
        out.append("（");
        if (status > 0) {
            out.append("HTTP ").append(status).append(' ');
        }
        out.append(endpoint == null ? "" : endpoint);
        if (detail != null && !detail.isBlank()) {
            String trimmed = detail.trim();
            if (trimmed.length() > 300) {
                trimmed = trimmed.substring(0, 300) + "…";
            }
            out.append(": ").append(trimmed);
        }
        return out.append("）").toString();
    }
}
