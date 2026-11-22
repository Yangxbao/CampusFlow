package com.campusflow.server.config;

import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.common.util.TraceUtils;
import com.campusflow.infra.auth.AuthContext;
import com.campusflow.infra.auth.JwtUtils;
import com.campusflow.infra.auth.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * JWT 鉴权拦截器（HandlerInterceptor）。
 * <p>职责边界：
 * - 解析 Authorization Bearer token -> UserPrincipal -> AuthContext（ThreadLocal）；
 * - afterCompletion 强制清理 ThreadLocal（Tomcat 线程复用防数据串号）；
 * - 角色校验：/api/v1/admin/** 要求 ADMIN；/api/v1/organizer/** 要求 ADMIN 或 ORGANIZER；
 * - 服务端鉴权是安全边界（前端隐藏菜单不是）。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtUtils jwtUtils;

    private static final List<String> ADMIN_PREFIXES = List.of("/api/v1/admin");
    private static final List<String> ORGANIZER_PREFIXES = List.of("/api/v1/organizer");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        UserPrincipal principal = jwtUtils.parseToken(auth.substring(7).trim());
        AuthContext.set(principal);

        // 管理员接口：仅 ADMIN
        if (ADMIN_PREFIXES.stream().anyMatch(request.getRequestURI()::startsWith)
                && !principal.isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        // 组织者接口：ORGANIZER 或 ADMIN
        if (ORGANIZER_PREFIXES.stream().anyMatch(request.getRequestURI()::startsWith)
                && !principal.isOrganizer() && !principal.isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程复用清理：防止下一个请求读到上一个用户身份
        AuthContext.clear();
    }
}
