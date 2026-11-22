package com.campusflow.server.it;

import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.registration.dto.SignupResultVO;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.service.RegistrationService;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.common.constant.RedisKeyConstants;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【压力场景 A 缩比版】真实 Redis Lua 并发抢购：不超卖、不重复、库存不为负。
 * <p>30 个真实用户并发抢 10 个名额（通过 RegistrationService 完整链路：
 * Lua 预留 -> MQ -> DB 确认），断言数据库最终有效报名数 == 10 且每人一条。
 * JMeter 场景 A（1000 用户）见 load-test/。
 */
class LuaOversellIT extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private RegistrationService registrationService;

    @Test
    void 并发抢购名额不超卖() throws Exception {
        ActivityDO activity = createPublishedActivity("超卖验证活动", 10);
        List<UserDO> users = createUsers("os", 30);

        ExecutorService pool = Executors.newFixedThreadPool(30);
        CountDownLatch ready = new CountDownLatch(30);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();
        AtomicInteger otherError = new AtomicInteger();
        Set<Long> successUsers = ConcurrentHashMap.newKeySet();

        for (UserDO user : users) {
            pool.execute(() -> {
                try {
                    ready.countDown();
                    start.await();
                    try {
                        SignupResultVO r = registrationService.signup(activity.getId(), user.getId());
                        if ("PENDING_CONFIRM".equals(r.getResult())) {
                            success.incrementAndGet();
                            successUsers.add(user.getId());
                        }
                    } catch (BizException e) {
                        if (e.getCode() == ErrorCode.STOCK_NOT_ENOUGH.getCode()) {
                            full.incrementAndGet();
                        } else {
                            otherError.incrementAndGet();
                        }
                    } catch (Exception e) {
                        otherError.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        // 同时开跑：瞬时并发
        start.countDown();
        pool.shutdown();
        Thread.sleep(2000);
        pool.shutdown();
        // Java 17 无 ExecutorService.close（Java 19+），用 shutdown + awaitTermination
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        int confirmed = awaitAndCount(activity.getId());
        long redisStock = Long.parseLong(String.valueOf(stringRedisTemplate.opsForValue()
                .get(RedisKeyConstants.activityStock(activity.getId()))));

        // ===== 核心断言（不超卖四原则） =====
        assertEquals(10, confirmed, "DB 有效报名必须恰好等于名额数（不超卖也不少卖）");
        assertTrue(redisStock >= 0, "Redis 库存不能为负");
        assertEquals(30, success.get() + full.get() + otherError.get(), "全部请求都有明确结果");
        assertEquals(0, otherError.get(), "不允许非预期错误");
        assertEquals(10, successUsers.size(), "成功用户数 = 名额数");
        // 一人一单
        Long totalRows = registrationMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activity.getId()));
        assertEquals(10, totalRows, "报名表总行数 = 名额数（一人一行）");
    }

    private int awaitAndCount(long activityId) throws InterruptedException {
        awaitConfirmed(activityId, 10, 20000);
        Long count = registrationMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activityId)
                .in(RegistrationDO::getStatus,
                        com.campusflow.common.enums.RegistrationStatus.CONFIRMED.getCode(),
                        com.campusflow.common.enums.RegistrationStatus.CHECKED_IN.getCode()));
        return count == null ? 0 : count.intValue();
    }
}
