package com.campusflow.biz.seed;

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
import com.campusflow.common.enums.ActivityStatus;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.infra.auth.JwtUtils;
import com.campusflow.infra.redis.BloomFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子数据初始化（幂等：仅在用户表为空时执行）。
 * <p>提供完整体验数据：
 * - 测试账号：admin / organizer1 / student01（密码见 FINAL_DELIVERY_REPORT）；
 * - JMeter 压测用户池：jmeter0001..jmeter1000（密码统一 Jm123456）；
 * - 示例活动：进行中的热门活动（容量 100，压测目标）、待审核活动、进行中活动、已结束活动等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "campusflow.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedDataRunner implements ApplicationRunner {

    private final UserMapper userMapper;
    private final UserService userService;
    private final ActivityService activityService;
    private final ActivityMapper activityMapper;
    private final RegistrationMapper registrationMapper;
    private final BloomFilterService bloomFilterService;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 布隆过滤器必须先于活动创建初始化（create 会写入布隆）
        bloomFilterService.initActivityBloomIfAbsent();
        // 阶段一：账号（仅用户表为空时执行一次）
        Long userCount = userMapper.selectCount(null);
        if (userCount == null || userCount == 0) {
            log.info("开始初始化种子账号...");
            createAccounts();
        } else {
            log.info("种子账号已存在（用户数={}），跳过", userCount);
        }
        // 阶段二：示例活动（按标题幂等：新增模板在旧库上也能增量生效，不破坏已有数据）
        List<ActivityDO> activities = createSampleActivities();
        if (userCount == null || userCount == 0) {
            createSampleRegistrations(activities);
        }
        warmupBloom();
        // 既存活动（直接落库绕过发布流程）需要补库存预热
        for (ActivityDO activity : activities) {
            if (activity.getStatus() == ActivityStatus.PUBLISHED.getCode()
                    || activity.getStatus() == ActivityStatus.ONGOING.getCode()) {
                activityService.warmupStockIfNeeded(activity.getId());
            }
        }
        log.info("种子数据初始化完成");
    }

    private void createAccounts() {
        // 压测账号统一密码的 BCrypt 密文（同密码同盐会不同——BCrypt 每次生成随机盐，
        // 1000 个账号共用一份密文即可通过同一明文校验，且避免 1000 次慢哈希拖慢启动）
        BCRYPT_CACHE = passwordEncoder.encode("Jm123456");
        // 管理员
        RegisterRequest admin = buildRegister("admin", "admin123456", "系统管理员", "ADMIN");
        userService.register(admin);
        // 组织者
        RegisterRequest organizer = buildRegister("organizer1", "org123456", "学生会技术部", "ORGANIZER");
        userService.register(organizer);
        // 学生
        for (int i = 1; i <= 20; i++) {
            String username = String.format("student%02d", i);
            userService.register(buildRegister(username, "stu123456", "同学" + i, "STUDENT"));
        }
        // JMeter 压测用户池（1000 个）
        List<UserDO> jmeterUsers = new ArrayList<>(1000);
        for (int i = 1; i <= 1000; i++) {
            UserDO u = new UserDO();
            u.setUsername(String.format("jmeter%04d", i));
            u.setPassword(BCRYPT_CACHE);
            u.setNickname("压测用户" + i);
            u.setStatus(UserDO.STATUS_ACTIVE);
            u.setDeleted(0);
            jmeterUsers.add(u);
        }
        // 分批插入
        for (int i = 0; i < jmeterUsers.size(); i += 100) {
            jmeterUsers.subList(i, Math.min(i + 100, jmeterUsers.size()))
                    .forEach(userMapper::insert);
        }
        log.info("压测用户池创建完成: 1000 个（jmeter0001..jmeter1000，密码 Jm123456）");
    }

    /** 压测账号统一 BCrypt 密文（密码 Jm123456），由 UserService 注册首个学生时生成缓存 */
    private String BCRYPT_CACHE;

    private RegisterRequest buildRegister(String username, String password, String nickname, String role) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setNickname(nickname);
        request.setRole(role);
        return request;
    }

    /**
     * 示例活动模板（按标题幂等：已存在同标题活动则跳过，新增模板可在既有库上增量生效）。
     * status：0草稿 1待审核 2未通过 3已发布 4进行中 5已结束 6已取消
     */
    private List<ActivityDO> createSampleActivities() {
        UserDO organizer = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, "organizer1").last("LIMIT 1"));
        if (organizer == null) {
            log.warn("组织者账号不存在，跳过示例活动播种");
            return new ArrayList<>();
        }
        Long organizerId = organizer.getId();
        LocalDateTime now = LocalDateTime.now();

        record Template(String title, String desc, int category, String location,
                        int status, int capacity,
                        java.time.Duration startOffset, java.time.Duration duration,
                        java.time.Duration signupStartOffset, java.time.Duration signupEndOffset,
                        int preConfirmed, int preCheckedIn) {
        }

        List<Template> templates = List.of(
                // ===== 既有 5 个 =====
                new Template("2026 年校园人工智能前沿讲座", "邀请业界专家分享大模型技术的最新进展与工程实践，限 100 人，先到先得！",
                        0, "图书馆报告厅", 3, 100,
                        Duration.ofDays(7), Duration.ofHours(2), Duration.ofHours(-1), Duration.ofDays(6), 0, 0),
                new Template("Python 爬虫实战工作坊", "手把手带你完成一个完整的爬虫项目，自带电脑参加。",
                        1, "实验楼 402", 4, 50,
                        Duration.ofMinutes(-10), Duration.ofHours(3), Duration.ofDays(-1), Duration.ofMinutes(-20), 0, 0),
                new Template("春季志愿服务动员大会", "招募校园志愿者，介绍学期服务计划与岗位安排。",
                        5, "大学生活动中心", 1, 200,
                        Duration.ofDays(10), Duration.ofHours(2), Duration.ofMinutes(5), Duration.ofDays(9), 0, 0),
                new Template("创新创业大赛宣讲会", "大赛规则解读与往届获奖团队经验分享。",
                        2, "创业孵化基地", 0, 300,
                        Duration.ofDays(15), Duration.ofHours(2), Duration.ofDays(1), Duration.ofDays(14), 0, 0),
                new Template("就业指导：简历门诊", "HR 一对一简历诊断，帮助你在秋招中脱颖而出。",
                        3, "就业指导中心", 5, 80,
                        Duration.ofDays(-3), Duration.ofHours(2), Duration.ofDays(-8), Duration.ofDays(-4), 20, 18),
                // ===== 新增 8 个（活动广场更丰富）=====
                new Template("考研数学冲刺讲座", "线性代数与概率论高频考点串讲，冲刺阶段复习规划指导。",
                        0, "教学楼 301", 3, 150,
                        Duration.ofDays(3), Duration.ofHours(2), Duration.ofHours(-2), Duration.ofDays(2), 0, 0),
                new Template("Spring Boot 3 实战分享会", "从自动装配原理到高并发报名系统设计，剖析真实项目的技术选型。",
                        1, "软件学院报告厅", 3, 60,
                        Duration.ofDays(2), Duration.ofHours(3), Duration.ofMinutes(-30), Duration.ofDays(1), 0, 0),
                new Template("校园歌手大赛报名启动", "唱出你的高光时刻！初赛线上提交作品，决赛线下舞台对决。",
                        2, "大礼堂", 3, 200,
                        Duration.ofDays(12), Duration.ofHours(3), Duration.ofHours(-1), Duration.ofDays(10), 0, 0),
                new Template("名企 HR 模拟面试工作坊", "一对一半结构化模拟面试，覆盖简历、群面与压力面。",
                        3, "就业指导中心 201", 3, 40,
                        Duration.ofDays(4), Duration.ofHours(3), Duration.ofMinutes(-15), Duration.ofDays(3), 0, 0),
                new Template("动漫社秋季迎新茶话会", "新社员见面会，漫展拼团与痛车企划发布，周边抽奖。",
                        4, "学生活动中心 305", 3, 80,
                        Duration.ofDays(5), Duration.ofHours(2), Duration.ofHours(-1), Duration.ofDays(4), 0, 0),
                new Template("图书馆整理志愿行动", "协助图书归架与旧书修补，计入志愿服务时长。",
                        5, "校图书馆", 3, 100,
                        Duration.ofDays(6), Duration.ofHours(4), Duration.ofMinutes(-45), Duration.ofDays(5), 0, 0),
                new Template("区块链技术前沿沙龙", "从共识机制到智能合约安全，聊聊 Web3 的技术底座与落地边界。",
                        1, "线上直播", 3, 45,
                        Duration.ofDays(1), Duration.ofHours(2), Duration.ofMinutes(-20), Duration.ofHours(18), 0, 0),
                new Template("校友创业经验分享会", "三位学长学姐复盘从校园到创业的真实历程与踩坑清单。",
                        3, "创业孵化基地路演厅", 4, 120,
                        Duration.ofMinutes(-5), Duration.ofHours(2), Duration.ofDays(-3), Duration.ofMinutes(-30), 0, 0)
        );

        List<ActivityDO> result = new ArrayList<>();
        int created = 0;
        for (Template t : templates) {
            // 按标题幂等：已存在（含逻辑删除）则跳过
            Long exists = activityMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ActivityDO>()
                    .eq(ActivityDO::getTitle, t.title()));
            if (exists != null && exists > 0) {
                continue;
            }
            ActivityDO activity = createActivity(organizerId, t.title(), t.desc(), t.category(), t.location(),
                    now.plus(t.startOffset()), now.plus(t.startOffset()).plus(t.duration()),
                    now.plus(t.signupStartOffset()), now.plus(t.signupEndOffset()), t.capacity());
            activity.setStatus(t.status());
            activity.setConfirmedCount(t.preConfirmed());
            activity.setCheckedInCount(t.preCheckedIn());
            activityMapper.updateById(activity);
            result.add(activity);
            created++;
        }
        log.info("示例活动播种完成: 新增 {} 个（模板 {} 个）", created, templates.size());
        return result;
    }

    private ActivityDO createActivity(Long organizerId, String title, String description,
                                      int category, String location,
                                      LocalDateTime start, LocalDateTime end,
                                      LocalDateTime signupStart, LocalDateTime signupEnd,
                                      int capacity) {
        // 种子数据是"既存事实"（可能已开始/已结束），直接落库而不走用户创建校验链路
        //（validateTimes 要求开始时间在未来，对已开始活动不适用）
        ActivityDO activity = new ActivityDO();
        activity.setTitle(title);
        activity.setDescription(description);
        activity.setCategory(category);
        activity.setLocation(location);
        activity.setStartTime(start);
        activity.setEndTime(end);
        activity.setSignupStartTime(signupStart);
        activity.setSignupEndTime(signupEnd);
        activity.setCapacity(capacity);
        activity.setOrganizerId(organizerId);
        activity.setConfirmedCount(0);
        activity.setCheckedInCount(0);
        activity.setStatus(ActivityStatus.DRAFT.getCode());
        activity.setVersion(0);
        activity.setDeleted(0);
        activityMapper.insert(activity);
        bloomFilterService.addActivityId(activity.getId());
        return activity;
    }

    private void createSampleRegistrations(List<ActivityDO> activities) {
        // 给已结束活动补 20 条已签到报名（统计演示数据）
        ActivityDO finished = activities.stream()
                .filter(a -> a.getStatus() == ActivityStatus.FINISHED.getCode())
                .findFirst().orElse(null);
        if (finished == null) {
            return;
        }
        List<UserDO> students = userMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserDO>()
                        .likeRight(UserDO::getUsername, "student")
                        .last("LIMIT 20"));
        int checkedIn = 0;
        for (UserDO student : students) {
            RegistrationDO reg = new RegistrationDO();
            reg.setActivityId(finished.getId());
            reg.setUserId(student.getId());
            reg.setStatus(checkedIn < 18
                    ? RegistrationStatus.CHECKED_IN.getCode()
                    : RegistrationStatus.CONFIRMED.getCode());
            reg.setRegisteredAt(LocalDateTime.now().minusDays(5));
            reg.setCheckinAt(checkedIn < 18 ? LocalDateTime.now().minusDays(3) : null);
            reg.setVersion(0);
            try {
                registrationMapper.insert(reg);
            } catch (Exception ignored) {
                // 唯一约束冲突跳过
            }
            checkedIn++;
        }
        log.info("已结束活动示例报名创建完成: {} 条", students.size());
    }

    private void warmupBloom() {
        // 应用启动时从 DB 全量装载活动 ID（Redis 重启后布隆数据重建）
        Long count = activityMapper.selectCount(null);
        bloomFilterService.initActivityBloomIfAbsent();
        List<ActivityDO> activities = activityMapper.selectList(null);
        for (ActivityDO activity : activities) {
            bloomFilterService.addActivityId(activity.getId());
        }
        log.info("布隆过滤器预热完成 activityCount={}", count);
    }
}
