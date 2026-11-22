package com.campusflow.server.it;

import com.campusflow.biz.activity.dto.ActivityUpsertRequest;
import com.campusflow.biz.activity.dto.ActivityVO;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.activity.service.ActivityService;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.user.dto.RegisterRequest;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.biz.user.mapper.UserMapper;
import com.campusflow.biz.user.service.UserService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 集成测试基类：真实 MySQL(campusflow_test) + Redis(VM, db1) + RocketMQ(VM)。
 * <p>schema 由 Flyway 编程式执行一次（test profile 关闭自动迁移，避免多上下文冲突）。
 * 极端场景测试全部使用真实中间件，禁止 Mock。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    private static volatile boolean schemaReady = false;

    @Autowired
    protected ActivityService activityService;
    @Autowired
    protected ActivityMapper activityMapper;
    @Autowired
    protected UserService userService;
    @Autowired
    protected UserMapper userMapper;
    @Autowired
    protected RegistrationMapper registrationMapper;
    @Autowired
    protected StringRedisTemplate stringRedisTemplate;
    @Autowired
    protected DataSource dataSource;

    private static final AtomicInteger USER_SEQ = new AtomicInteger(0);

    @Autowired
    protected com.campusflow.infra.redis.BloomFilterService bloomFilterService;

    @org.junit.jupiter.api.BeforeEach
    void ensureBloom() {
        // 测试上下文无 SeedDataRunner，布隆过滤器需确保初始化（幂等）
        bloomFilterService.initActivityBloomIfAbsent();
    }

    @BeforeAll
    static void initSchema(@org.springframework.beans.factory.annotation.Autowired(required = false) DataSource ds) {
        // Flyway 编程式迁移：仅执行一次（测试库 campusflow_test）
        if (!schemaReady) {
            synchronized (IntegrationTestBase.class) {
                if (!schemaReady) {
                    Flyway.configure()
                            .dataSource("jdbc:mysql://127.0.0.1:3306/campusflow_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                                    "root", "1234")
                            .locations("classpath:db/migration")
                            .baselineOnMigrate(true)
                            .load()
                            .migrate();
                    schemaReady = true;
                }
            }
        }
    }

    // ==================== 测试数据工厂 ====================

    /** 创建独立测试用户（用户名全局唯一） */
    protected UserDO createUser(String tag) {
        String username = "it_" + tag + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword("it123456");
        request.setNickname("集成测试用户-" + username);
        request.setRole("STUDENT");
        userService.register(request);
        return userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, username));
    }

    /** 批量创建测试用户 */
    protected List<UserDO> createUsers(String tag, int count) {
        List<UserDO> users = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            users.add(createUser(tag + i));
        }
        return users;
    }

    /** 创建并发布活动（走真实审核发布链路，触发库存预热） */
    protected ActivityDO createPublishedActivity(String title, int capacity) {
        UserDO organizer = createOrganizer();
        ActivityUpsertRequest request = new ActivityUpsertRequest();
        LocalDateTime now = LocalDateTime.now();
        request.setTitle(title + "-" + UUID.randomUUID().toString().substring(0, 8));
        request.setDescription("集成测试活动");
        request.setCategory(0);
        request.setLocation("测试地点");
        request.setStartTime(now.plusDays(1));
        request.setEndTime(now.plusDays(1).plusHours(2));
        request.setSignupStartTime(now.minusMinutes(1));
        request.setSignupEndTime(now.plusHours(12));
        request.setCapacity(capacity);
        ActivityVO vo = activityService.create(request, organizer.getId());
        activityService.submitForReview(vo.getId(), organizer.getId());
        activityService.approvePublish(vo.getId(), "测试自动通过");
        return activityMapper.selectById(vo.getId());
    }

    protected UserDO createOrganizer() {
        String username = "it_org_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword("it123456");
        request.setNickname("集成测试组织者");
        request.setRole("ORGANIZER");
        userService.register(request);
        return userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, username));
    }

    /** 等待 MQ 消费链路完成：轮询直到指定活动确认人数达到期望或超时 */
    protected void awaitConfirmed(long activityId, int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Long count = registrationMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RegistrationDO>()
                    .eq(RegistrationDO::getActivityId, activityId)
                    .in(RegistrationDO::getStatus,
                            com.campusflow.common.enums.RegistrationStatus.CONFIRMED.getCode(),
                            com.campusflow.common.enums.RegistrationStatus.CHECKED_IN.getCode()));
            if (count != null && count >= expected) {
                return;
            }
            Thread.sleep(300);
        }
        // 超时后不直接失败，让断言输出真实值便于诊断
    }
}
