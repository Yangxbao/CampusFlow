package com.campusflow.server.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.checkin.dto.ScanCheckinRequest;
import com.campusflow.biz.checkin.entity.CheckinRecordDO;
import com.campusflow.biz.checkin.mapper.CheckinRecordMapper;
import com.campusflow.biz.checkin.service.CheckinService;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.registration.service.RegistrationService;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.infra.auth.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【场景 E：报名与签到并发】两个管理员同时扫描同一凭证：只能一次有效签到。
 */
class CheckinConcurrencyIT extends IntegrationTestBase {

    @Autowired
    private RegistrationService registrationService;
    @Autowired
    private CheckinService checkinService;
    @Autowired
    private CheckinRecordMapper checkinRecordMapper;
    @Autowired
    private RegistrationMapper registrationMapper;
    @Autowired
    private ActivityMapper activityMapper;

    @Test
    void 并发核销同一凭证只能一次成功() throws Exception {
        // 构造进行中的活动（既存事实直接落库 + 预热）
        UserDO organizer = createOrganizer();
        ActivityDO activity = createOngoingActivity(organizer);

        UserDO student = createUser("ck");
        RegistrationDO reg = new RegistrationDO();
        reg.setActivityId(activity.getId());
        reg.setUserId(student.getId());
        reg.setStatus(com.campusflow.common.enums.RegistrationStatus.CONFIRMED.getCode());
        reg.setRegisteredAt(LocalDateTime.now());
        reg.setVersion(0);
        registrationMapper.insert(reg);

        // 学生签发凭证
        var token = checkinService.issueToken(reg.getId(), student.getId());
        String qrPayload = token.getQrPayload();
        String plainToken = com.campusflow.common.util.JsonUtils.fromJson(qrPayload,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                }).get("token").toString();

        UserPrincipal operator1 = new UserPrincipal(organizer.getId(), organizer.getUsername(), List.of("ORGANIZER"));
        UserPrincipal operator2 = new UserPrincipal(organizer.getId(), organizer.getUsername(), List.of("ADMIN"));

        // 10 个"管理员"并发扫同一凭证
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            UserPrincipal op = i % 2 == 0 ? operator1 : operator2;
            pool.execute(() -> {
                try {
                    start.await();
                    try {
                        ScanCheckinRequest request = new ScanCheckinRequest();
                        request.setActivityId(activity.getId());
                        request.setToken(plainToken);
                        checkinService.scan(request, op);
                        success.incrementAndGet();
                    } catch (Exception e) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        Thread.sleep(2000);
        pool.shutdown();
        // Java 17 无 ExecutorService.close（Java 19+），用 shutdown + awaitTermination
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));

        // ===== 断言 =====
        assertEquals(1, success.get(), "并发核销同一凭证只允许 1 次成功");
        assertEquals(9, rejected.get(), "其余 9 次必须被拒绝");
        Long records = checkinRecordMapper.selectCount(new LambdaQueryWrapper<CheckinRecordDO>()
                .eq(CheckinRecordDO::getRegistrationId, reg.getId()));
        assertEquals(1L, records, "签到记录唯一（uk_registration）");
        RegistrationDO after = registrationMapper.selectById(reg.getId());
        assertEquals(com.campusflow.common.enums.RegistrationStatus.CHECKED_IN.getCode(), after.getStatus());
        ActivityDO actAfter = activityMapper.selectById(activity.getId());
        assertEquals(1, actAfter.getCheckedInCount(), "活动签到计数 = 1");
        assertDoesNotThrow(() -> {
        });
    }

    /** 创建进行中的活动（签到窗口内）：直接落库为既存事实并预热 */
    private ActivityDO createOngoingActivity(UserDO organizer) {
        ActivityDO activity = new ActivityDO();
        LocalDateTime now = LocalDateTime.now();
        activity.setTitle("签到并发验证-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        activity.setDescription("集成测试");
        activity.setCategory(1);
        activity.setLocation("实验楼");
        activity.setStartTime(now.minusMinutes(10));   // 已开始
        activity.setEndTime(now.plusHours(2));         // 未结束
        activity.setSignupStartTime(now.minusDays(1));
        activity.setSignupEndTime(now.minusMinutes(20));
        activity.setCapacity(50);
        activity.setOrganizerId(organizer.getId());
        activity.setConfirmedCount(0);
        activity.setCheckedInCount(0);
        activity.setStatus(com.campusflow.common.enums.ActivityStatus.ONGOING.getCode());
        activity.setVersion(0);
        activity.setDeleted(0);
        activityMapper.insert(activity);
        activityService.warmupStockIfNeeded(activity.getId());
        return activity;
    }
}
