package com.lab.dataworks.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 统一请求体上限：所有 JSON API 都按 2MB 拒绝，避免超大 body 进入业务代码
 * 后才在某个字段上失败（甚至被 Jackson 全量读进内存）。
 *
 * <p>只检查 Content-Length 是不够的：分块传输（Transfer-Encoding: chunked）
 * 时长度是 -1，攻击者可以无上限地把 body 塞进内存。这里对未知长度的请求
 * 额外包一层计数流，超过上限立即中断。</p>
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    /** 上传类接口（UDF 资源、多模态资产）上限，S9 起不再整体放行。 */
    private static final long UPLOAD_MAX_BYTES = 50L * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // S9：multipart 上传路径过去被直接放行，chunked（无 Content-Length）可绕过上限。
        // 现在按路径给不同上限，并且同样走流式计数——放行不等于不限。
        String uri = request.getRequestURI();
        boolean uploadPath = uri != null
                && ((uri.startsWith("/api/udf/projects/") && uri.endsWith("/resources"))
                    || uri.startsWith("/api/assets"))
                && "POST".equalsIgnoreCase(request.getMethod());
        long limit = uploadPath ? UPLOAD_MAX_BYTES : MAX_BYTES;
        long length = request.getContentLengthLong();
        if (length > limit) {
            reject(response, limit);
            return;
        }
        HttpServletRequest effective = length < 0
                ? new LimitedRequest(request, limit) : request;
        try {
            chain.doFilter(effective, response);
        } catch (RequestTooLargeException e) {
            if (response.isCommitted()) {
                throw e;
            }
            response.reset();
            reject(response, limit);
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        reject(response, MAX_BYTES);
    }

    private void reject(HttpServletResponse response, long limit) throws IOException {
        log.warn("reject oversized request");
        response.setStatus(413);
        response.setContentType("application/json;charset=UTF-8");
        long mb = limit / (1024 * 1024);
        // D65：错误体口径唯一（{timestamp,status,message}）。这里原来是手拼两键 JSON，
        // 客户端按统一契约读 timestamp 会在 413 上 KeyError。
        String body = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(GlobalExceptionHandler.errorBody(
                        413, "请求体不能超过 " + mb + "MB"));
        response.getWriter().write(body);
    }

    public static final class RequestTooLargeException extends RuntimeException {
        RequestTooLargeException() {
            super("请求体不能超过 2MB");
        }
    }

    /** 对未知 Content-Length 的请求做流式计数，避免 chunked body 绕过上限。 */
    static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;
        private CountingStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null
                    ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    static final class CountingStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        CountingStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        private void count(long n) {
            if (n <= 0) {
                return;
            }
            count += n;
            if (count > limit) {
                throw new RequestTooLargeException();
            }
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            count(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            return delegate.skip(n);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
