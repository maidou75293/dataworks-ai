package com.lab.dataworks.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常 → JSON 错误体。
 *
 * <p><b>v449 P1-6</b>：handler 声明了 {@code produces=text/plain}（同步任务的 logs/conf）
 * 或 {@code text/event-stream}（助手对话）时，这里固定返回 JSON 会触发内容协商失败，
 * 结果任何 4xx/5xx 都变成**空 body 的 500**：跨租户访问本该 404，用户看到的是服务器错误。
 * 现在按请求的 Accept 降级：能收 JSON 就回 JSON，只能收纯文本/事件流就回纯文本。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handleApi(ApiException e, HttpServletRequest request) {
        return response(request, e.getStatus(), body(e.getStatus(), e.getMessage()));
    }

    /**
     * 请求体不是 JSON / 类型不匹配 / 缺参数，都是客户端错误，不能按 500 返回。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadable(HttpMessageNotReadableException e,
                                              HttpServletRequest request) {
        // chunked body 超限时，计数流抛出的异常会被 Jackson 包一层
        if (hasCause(e, RequestSizeLimitFilter.RequestTooLargeException.class)) {
            return response(request, 413, body(413, "请求体不能超过 2MB"));
        }
        // BUG-TC4-6：请求体是合法 JSON，只是带了接口不认的字段——单独说清楚，
        // 不能混进「不是合法 JSON」（那会让调用方去查语法，实际是字段不该传）
        com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException unknown =
                findCause(e, com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
        if (unknown != null) {
            String field = unknown.getPropertyName();
            boolean envLike = "env".equalsIgnoreCase(field) || "environment".equalsIgnoreCase(field);
            return response(request, 400, body(400, "不支持的请求字段: " + field
                    + (envLike
                    ? "（本接口没有环境维度：DEV/PROD 落点由平台按项目 dev_database/prod_database 判定，"
                        + "不接受调用方指定；去掉这个字段后重试）"
                    : "（该接口不接受此字段，去掉后重试）")));
        }
        return response(request, 400, body(400, "请求体不是合法的 JSON，或字段类型不匹配"));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        for (Throwable current = error; current != null && current != current.getCause();
             current = current.getCause()) {
            if (type.isInstance(current)) {
                return (T) current;
            }
        }
        return null;
    }

    @ExceptionHandler(RequestSizeLimitFilter.RequestTooLargeException.class)
    public ResponseEntity<?> handleTooLarge(RequestSizeLimitFilter.RequestTooLargeException e,
                                            HttpServletRequest request) {
        return response(request, 413, body(413, e.getMessage()));
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingParam(MissingServletRequestParameterException e,
                                                HttpServletRequest request) {
        return response(request, 400, body(400, "缺少参数: " + e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                HttpServletRequest request) {
        String name = e.getName();
        String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
        return response(request, 400, body(400, "参数 " + name + " 格式不正确: " + shorten(value)));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<?> handleNotFound(Exception e, HttpServletRequest request) {
        return response(request, 404, body(404, "接口不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethod(HttpRequestMethodNotSupportedException e,
                                          HttpServletRequest request) {
        return response(request, 405, body(405, "不支持的请求方法: " + e.getMethod()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaType(HttpMediaTypeNotSupportedException e,
                                             HttpServletRequest request) {
        return response(request, 415, body(415, "不支持的 Content-Type: " + e.getContentType()));
    }

    /**
     * D13：Accept 与接口声明的 {@code produces} 不匹配时，Spring 在**进方法之前**抛
     * {@code HttpMediaTypeNotAcceptableException}；以前没有对应分支，落到兜底 500
     * 「服务器内部错误」，把客户端的 Accept 写错算成服务端故障（监控误报、排障跑偏）。
     *
     * <p>错误体固定 text/plain：客户端已经声明收不到这个接口的响应类型，
     * 再按 Accept 挑 JSON 可能同样协商失败，又变回空 body 的 500。</p>
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<String> handleNotAcceptable(HttpMediaTypeNotAcceptableException e,
                                                      HttpServletRequest request) {
        String accept = request == null ? null : request.getHeader("Accept");
        log.warn("media type not acceptable: uri={} accept={}",
                request == null ? null : request.getRequestURI(), accept);
        return ResponseEntity.status(406)
                .contentType(MediaType.TEXT_PLAIN)
                .body("此接口的响应类型与 Accept 不匹配（Accept: "
                        + (accept == null ? "未设置" : accept) + "）");
    }

    /** 单行查询没查到数据，对调用方就是 404。 */
    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<?> handleEmpty(EmptyResultDataAccessException e,
                                         HttpServletRequest request) {
        return response(request, 404, body(404, "数据不存在"));
    }

    /** 外键 / 唯一键冲突统一按 409，具体接口能提前判断的仍返回更友好的中文提示。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleIntegrity(DataIntegrityViolationException e,
                                             HttpServletRequest request) {
        String cause = e.getMostSpecificCause().getMessage();
        log.warn("data integrity violation: {}", cause);
        String lower = cause == null ? "" : cause.toLowerCase(java.util.Locale.ROOT);
        // 取值不合法（CHECK 约束 / 非法字符 / 编码问题）是输入错误，不是「数据冲突」：
        // 之前统一报 409，外部审计里 NUL 字符、枚举越界都被误报成「违反唯一性」。
        if (lower.contains("check constraint") || lower.contains("violates check")
                || lower.contains("invalid byte sequence") || lower.contains("null value")) {
            return response(request, 400, body(400, "字段取值不合法"));
        }
        return response(request, 409, body(409, "数据冲突：违反唯一性或外键约束"));
    }

    /**
     * 数据库连接被掐断（PG 主备切换、连接被 terminate、网络闪断）：这是**可重试的暂时故障**，
     * 不是代码 BUG。原来落到 500「服务器内部错误」，客户端不会重试；
     * 实测在断连瞬间正在执行的请求回 500，紧接着的请求立即恢复。
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<?> handleDbUnavailable(DataAccessResourceFailureException e,
                                                 HttpServletRequest request) {
        log.warn("database connection failure: {}", e.getMostSpecificCause().getMessage());
        return response(request, 503, body(503, "数据库连接暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleOther(Exception e, HttpServletRequest request) {
        // 完整堆栈进日志；返回给客户端的消息不含 SQL / 表结构，避免泄露内部细节
        log.error("unhandled error", e);
        return response(request, 500, body(500, "服务器内部错误，请查看后端日志"));
    }

    /**
     * 按 Accept 选择错误体格式：text/event-stream → 事件流纯文本；
     * text/plain（且不接受 JSON）→ 纯文本；其余 → JSON。
     */
    private ResponseEntity<?> response(HttpServletRequest request, int status,
                                       Map<String, Object> body) {
        String accept = request == null ? null : request.getHeader("Accept");
        if (accept != null) {
            String lower = accept.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("text/event-stream")) {
                return ResponseEntity.status(status)
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(String.valueOf(body.get("message")));
            }
            boolean acceptsJson = lower.contains("application/json")
                    || lower.contains("*/*") || lower.contains("application/*");
            if (!acceptsJson && lower.contains("text/plain")) {
                return ResponseEntity.status(status)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(String.valueOf(body.get("message")));
            }
        }
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> body(int status, String message) {
        return errorBody(status, message);
    }

    /**
     * D65：错误体**唯一口径** —— {@code {timestamp,status,message}}。
     *
     * <p>缺陷：拦截器（未登录 401、缺权限点 403、请求体过大 413）自己直写
     * {@code {status,message}} 两键体，与这里的三键契约不同形；客户端统一解析
     * {@code resp["timestamp"]} 会在拦截器那条路上 KeyError。</p>
     *
     * <p>所以把它开成 public static，让**所有**产出错误 JSON 的地方共用同一份构造，
     * 而不是各写一遍「看起来一样的」字段。</p>
     */
    public static Map<String, Object> errorBody(int status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", OffsetDateTime.now().toString());
        out.put("status", status);
        out.put("message", message);
        return out;
    }

    private String shorten(String value) {
        return value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }
}
