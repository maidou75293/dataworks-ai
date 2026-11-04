package com.lab.dataworks.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 前端是单页应用，刷新 /map、/integration 这类深层路由时后端必须回 index.html，
 * 否则会走静态资源处理器并报 “No static resource ...”。
 */
@Component
public class SpaWebFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean html = "GET".equalsIgnoreCase(method)
                && !path.startsWith("/api")
                && !path.startsWith("/actuator")
                && !path.contains(".")
                && !"/".equals(path);
        if (html) {
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
