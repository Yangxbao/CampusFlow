package com.campusflow.biz.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解：标注在需要审计的接口方法上，AOP 切面异步记录。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 审计动作，如 LOGIN / CREATE_ACTIVITY / REVIEW */
    String action();

    /** 资源类型，如 activity / user */
    String resource() default "";
}
