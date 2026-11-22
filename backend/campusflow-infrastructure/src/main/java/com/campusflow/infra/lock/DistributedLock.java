package com.campusflow.infra.lock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注解式分布式锁（Redisson）。
 * <p>用法：@DistributedLock(key = "'stock:preload:' + #activityId")
 * key 支持 SpEL，可引用方法参数。
 * <p>leaseTimeMillis <= 0 时不传 leaseTime，启用 Redisson 看门狗自动续期（默认 30s，每 10s 续）；
 * 显式设置 leaseTime 则看门狗失效，适合执行时间确定的短任务。
 * <p>注意：锁注解不可用于同一类内部自调用（AOP 代理失效场景之一）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    /** 锁 Key（SpEL），实际拼上前缀 campusflow:lock: */
    String key();

    /** 获取锁最大等待（毫秒），0 表示不等待 */
    long waitTimeMillis() default 3000;

    /** 锁自动释放时间（毫秒），<= 0 启用看门狗续期 */
    long leaseTimeMillis() default -1;

    /** 获取锁失败时抛异常还是直接跳过（false=抛 LOCK_TIMEOUT） */
    boolean skipOnFail() default false;
}
