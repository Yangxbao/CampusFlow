package com.campusflow.infra.lock;

import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 编程式分布式锁服务（注解之外的灵活用法：需要返回值/细粒度控制时使用）。
 */
@Service
@RequiredArgsConstructor
public class RedissonLockService {

    private final RedissonClient redissonClient;

    /**
     * tryLock + 看门狗模式执行（推荐）。获取失败抛 LOCK_TIMEOUT。
     */
    public <T> T executeWithLock(String lockKey, long waitTimeMillis, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTimeMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw BizException.of(ErrorCode.LOCK_TIMEOUT);
            }
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(ErrorCode.LOCK_TIMEOUT);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * tryLock，获取失败返回 false（用于定时任务防重入：拿不到锁说明另一个实例在跑）。
     */
    public boolean tryRun(String lockKey, Runnable task) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, TimeUnit.MILLISECONDS);
            if (!acquired) {
                return false;
            }
            task.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
