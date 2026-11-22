package com.campusflow.server.config;

import com.campusflow.common.util.TraceUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TraceId 过滤器：每个请求生成/透传 TraceId，写入 MDC 供日志框架输出，
 * 并通过响应头返回给调用方（便于前后端联调排障）。
 * 过滤器 vs 拦截器的边界：TraceId 属于协议层关注点（所有请求，包括静态资源/actuator），用 Filter；
 * 鉴权属于 MVC 层关注点（仅业务路由），用 HandlerInterceptor。
 */
@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String traceId = request.getHeader(TraceUtils.TRACE_HEADER);
            if (traceId == null || traceId.isEmpty() || traceId.length() > 64) {
                traceId = TraceUtils.newTraceId();
            }
            MDC.put(TraceUtils.MDC_KEY, traceId);
            response.setHeader(TraceUtils.TRACE_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            // MDC 底层也是 ThreadLocal：Tomcat 线程复用，必须清理
            MDC.remove(TraceUtils.MDC_KEY);
        }
    }
}
