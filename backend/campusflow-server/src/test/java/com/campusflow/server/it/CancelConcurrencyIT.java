package com.campusflow.server.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.audit.entity.StockAuditLogDO;
import com.campusflow.biz.audit.mapper.StockAuditLogMapper;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.service.RegistrationService;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.common.constant.RedisKeyConstants;
import com.campusflow.common.enums.RegistrationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【场景 C：取消报名并发】10 线程同时取消同一报名：名额只恢复一次、库存审计只一条。
 */
class CancelConcurrencyIT extends IntegrationTestBase {

    @Autowired
    private RegistrationService registrationService;
    @Autowired
    private StockAuditLogMapper stockAuditLogMapper;

    @Test
    void 并发取消只恢复一次名额() throws Exception {
        ActivityDO activity = createPublishedActivity("并发取消验证", 10);
        UserDO user = createUser("cancel");

        // 报名并等待确认
        registrationService.signup(activity.getId(), user.getId());
        awaitConfirmed(activity.getId(), 1, 20000);
        RegistrationDO reg = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activity.getId())
                .eq(RegistrationDO::getUserId, user.getId()));
        assertEquals(RegistrationStatus.CONFIRMED.getCode(), reg.getStatus());
        Long stockAfterSignup = Long.parseLong(String.valueOf(stringRedisTemplate.opsForValue()
                .get(RedisKeyConstants.activityStock(activity.getId()))));
        assertEquals(9L, stockAfterSignup, "报名成功后 Redis 库存 = 10-1");

        // 10 线程并发取消
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    registrationService.cancel(reg.getId(), user.getId());
                    done.incrementAndGet();
                } catch (Exception ignored) {
                    // 某些并发请求可能遇到状态冲突（乐观锁），属预期
                }
            });
        }
        start.countDown();
        pool.shutdown();
        Thread.sleep(1500);
        pool.shutdown();
        // Java 17 无 ExecutorService.close（Java 19+），用 shutdown + awaitTermination
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        // ===== 断言 =====
        RegistrationDO after = registrationMapper.selectById(reg.getId());
        assertEquals(RegistrationStatus.CANCELLED.getCode(), after.getStatus(), "最终状态 = 已取消");

        Long cancelAudits = stockAuditLogMapper.selectCount(new LambdaQueryWrapper<StockAuditLogDO>()
                .eq(StockAuditLogDO::getBizKey, "CANCEL-" + reg.getId()));
        assertEquals(1L, cancelAudits, "库存审计 CANCEL 记录只有一条（幂等记账，防重复加回）");

        Long stock = Long.parseLong(String.valueOf(stringRedisTemplate.opsForValue()
                .get(RedisKeyConstants.activityStock(activity.getId()))));
        assertEquals(10L, stock, "Redis 库存必须恢复到 10（并发取消只 INCR 一次）");

        assertTrue(done.get() >= 1, "至少一个取消请求成功");
    }
}
