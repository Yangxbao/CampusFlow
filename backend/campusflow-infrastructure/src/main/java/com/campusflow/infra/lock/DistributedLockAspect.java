package com.campusflow.infra.lock;

import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁切面（AOP 动态代理落地）。
 * <p>锁误删风险分析（本实现的防护）：
 * 1. 业务超时锁过期：使用看门狗续期模式（leaseTimeMillis<=0），锁不会在业务执行期间过期；
 * 2. 非持有者误删：Redisson 锁值带客户端唯一 ID（UUID:threadId），删除前校验持有者，
 *    错误的客户端无法删除别人的锁；
 * 3. 看门狗不可用场景（显式 leaseTime）：必须保证 leaseTime > 业务最大耗时（P99），
 *    否则配合"锁值校验 + Lua 原子删除"仍可能存在窗口，需评估业务幂等性兜底。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {

    private static final String KEY_PREFIX = "campusflow:lock:aspect:";

    private final RedissonClient redissonClient;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint pjp, DistributedLock distributedLock) throws Throwable {
        String lockKey = KEY_PREFIX + resolveKey(distributedLock.key(), pjp);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            if (distributedLock.leaseTimeMillis() > 0) {
                acquired = lock.tryLock(distributedLock.waitTimeMillis(),
                        distributedLock.leaseTimeMillis(), TimeUnit.MILLISECONDS);
            } else {
                // 看门狗模式：只传 waitTime，leaseTime 由 watchdog 管理（30s 自动续期）
                acquired = lock.tryLock(distributedLock.waitTimeMillis(), TimeUnit.MILLISECONDS);
            }
            if (!acquired) {
                if (distributedLock.skipOnFail()) {
                    log.info("分布式锁未获取，跳过执行: {}", lockKey);
                    return null;
                }
                throw BizException.of(ErrorCode.LOCK_TIMEOUT);
            }
            log.debug("获取分布式锁: {}", lockKey);
            return pjp.proceed();
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                // isHeldByCurrentThread 校验：防止锁已过期被他人持有后误删
                lock.unlock();
                log.debug("释放分布式锁: {}", lockKey);
            }
        }
    }

    private String resolveKey(String spel, ProceedingJoinPoint pjp) {
        try {
            Method method = ((MethodSignature) pjp.getSignature()).getMethod();
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            Object[] args = pjp.getArgs();
            String[] names = nameDiscoverer.getParameterNames(method);
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    ctx.setVariable(names[i], args[i]);
                }
            }
            Object value = parser.parseExpression(spel).getValue(ctx);
            return String.valueOf(value);
        } catch (Exception e) {
            throw new IllegalStateException("分布式锁 SpEL 解析失败: " + spel, e);
        }
    }
}
