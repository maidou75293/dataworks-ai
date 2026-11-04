package com.lab.dataworks.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 运行日志完善：每个请求带 requestId，5xx 和慢请求落日志。
 *
 * 目的：日志自检工具（scripts/ops/logcheck.py）能直接按 requestId 关联
 * 前端报错、后端异常和具体接口，不需要人工翻整段日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final long SLOW_MS = 2000;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            log.error("request failed {} {} requestId={} elapsed={}ms",
                    request.getMethod(), path(request), requestId, elapsedMs(started), e);
            throw e;
        } finally {
            long elapsed = elapsedMs(started);
            int status = response.getStatus();
            if (status >= 500) {
                log.error("request {} {} status={} requestId={} elapsed={}ms",
                        request.getMethod(), path(request), status, requestId, elapsed);
            } else if (elapsed >= SLOW_MS) {
                log.warn("slow request {} {} status={} requestId={} elapsed={}ms",
                        request.getMethod(), path(request), status, requestId, elapsed);
            }
            MDC.remove("requestId");
        }
    }

    /** 只记路径，不记查询串，避免 token / 密码等敏感参数进日志。 */
    private static String path(HttpServletRequest request) {
        return request.getRequestURI();
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
