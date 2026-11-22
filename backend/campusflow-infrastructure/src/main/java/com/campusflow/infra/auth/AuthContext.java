package com.campusflow.infra.auth;

/**
 * 当前用户上下文（ThreadLocal）。
 * <p>写入方：JwtAuthInterceptor.preHandle；清理方：同一拦截器 afterCompletion。
 * <p>必须清理的原因：Tomcat 使用线程池复用 worker 线程，若不 remove，
 * 下一个请求可能读到上一个用户身份（数据越权）+ ThreadLocalMap Entry 泄漏。
 * <p>注意：本 ThreadLocal 跨 @Async 线程池不可用（ThreadLocal 不传递），
 * 异步任务需要的身份信息必须显式作为参数传入。
 */
public final class AuthContext {

    private static final ThreadLocal<UserPrincipal> CONTEXT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(UserPrincipal principal) {
        CONTEXT.set(principal);
    }

    public static UserPrincipal get() {
        return CONTEXT.get();
    }

    public static Long getUserId() {
        UserPrincipal p = CONTEXT.get();
        return p == null ? null : p.getUserId();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
