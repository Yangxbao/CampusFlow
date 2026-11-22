package com.campusflow.biz.audit;

import com.campusflow.biz.audit.entity.SysAuditLogDO;
import com.campusflow.infra.auth.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * 操作审计切面：@AuditLog 标注的方法成功执行后异步落审计日志。
 * 记录：操作人 / 动作 / 资源 / 参数摘要 / IP / TraceId。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final SysAuditService sysAuditService;

    @AfterReturning("@annotation(auditLog)")
    public void afterSuccess(JoinPoint joinPoint, AuditLog auditLog) {
        try {
            SysAuditLogDO audit = new SysAuditLogDO();
            var principal = AuthContext.get();
            if (principal != null) {
                audit.setUserId(principal.getUserId());
                audit.setUsername(principal.getUsername());
            }
            audit.setAction(auditLog.action());
            audit.setResource(auditLog.resource());
            Object[] args = joinPoint.getArgs();
            audit.setDetail(args.length == 0 ? "" :
                    Arrays.stream(args)
                            .map(a -> a == null ? "null" : a.getClass().getSimpleName())
                            .reduce((a, b) -> a + "," + b)
                            .orElse(""));
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                audit.setIp(request.getRemoteAddr());
            }
            audit.setTraceId(MDC.get(com.campusflow.common.util.TraceUtils.MDC_KEY));
            sysAuditService.recordAsync(audit);
        } catch (Exception e) {
            log.error("审计切面执行失败 action={}", auditLog.action(), e);
        }
    }
}
