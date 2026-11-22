package com.campusflow.biz.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusflow.biz.activity.dto.ActivityDetailVO;
import com.campusflow.biz.activity.dto.ActivityQuery;
import com.campusflow.biz.activity.dto.ActivityUpsertRequest;
import com.campusflow.biz.activity.dto.ActivityVO;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.biz.user.mapper.UserMapper;
import com.campusflow.common.constant.RedisKeyConstants;
import com.campusflow.common.enums.ActivityCategory;
import com.campusflow.common.enums.ActivityStatus;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.common.result.PageResult;
import com.campusflow.common.util.JsonUtils;
import com.campusflow.infra.lock.DistributedLock;
import com.campusflow.infra.mq.MessageDeliverService;
import com.campusflow.infra.property.CampusflowProperties;
import com.campusflow.infra.redis.BloomFilterService;
import com.campusflow.infra.redis.MultiLevelCacheService;
import com.campusflow.infra.redis.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 活动领域服务：创建/编辑/提交审核/取消/详情缓存/容量调整。
 * <p>缓存策略（Cache Aside）：
 * - 读：布隆过滤器判存在 -> 多级缓存（Caffeine 30s -> Redis 30min）-> DB 回填；
 * - 写：DB 更新 -> 删除缓存 -> MQ 广播通知各实例失效本地缓存；
 * - 发布/容量变更：在分布式锁内按 DB 权威口径重算剩余名额并预热 Redis。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final UserMapper userMapper;
    private final RegistrationMapper registrationMapper;
    private final StockRedisService stockRedisService;
    private final MultiLevelCacheService multiLevelCacheService;
    private final BloomFilterService bloomFilterService;
    private final StringRedisTemplate stringRedisTemplate;
    private final MessageDeliverService messageDeliverService;
    private final CampusflowProperties properties;

    // ==================== 查询 ====================

    public PageResult<ActivityVO> pageForStudent(ActivityQuery query) {
        Page<ActivityDO> page = activityMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<ActivityDO>()
                        .in(ActivityDO::getStatus, ActivityStatus.PUBLISHED.getCode(), ActivityStatus.ONGOING.getCode())
                        .eq(query.getCategory() != null, ActivityDO::getCategory, query.getCategory())
                        .like(query.getKeyword() != null && !query.getKeyword().isEmpty(),
                                ActivityDO::getTitle, query.getKeyword())
                        .orderByDesc(ActivityDO::getCreatedAt));
        return toPageResult(page);
    }

    /** 推荐位：进行中 > 报名即将截止的已发布活动，按浏览量（Redis）加权展示由前端排序 */
    public List<ActivityVO> recommend(int limit) {
        List<ActivityDO> activities = activityMapper.selectList(new LambdaQueryWrapper<ActivityDO>()
                .in(ActivityDO::getStatus, ActivityStatus.PUBLISHED.getCode(), ActivityStatus.ONGOING.getCode())
                .orderByAsc(ActivityDO::getSignupEndTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 20)));
        return activities.stream().map(this::toVO).collect(Collectors.toList());
    }

    public PageResult<ActivityVO> pageByOrganizer(Long organizerId, Integer status, com.campusflow.common.page.PageQuery pageQuery) {
        Page<ActivityDO> page = activityMapper.selectPage(new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize()),
                new LambdaQueryWrapper<ActivityDO>()
                        .eq(ActivityDO::getOrganizerId, organizerId)
                        .eq(status != null, ActivityDO::getStatus, status)
                        .orderByDesc(ActivityDO::getCreatedAt));
        return toPageResult(page);
    }

    public PageResult<ActivityVO> pageForAdmin(Integer status, String keyword, com.campusflow.common.page.PageQuery pageQuery) {
        Page<ActivityDO> page = activityMapper.selectPage(new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize()),
                new LambdaQueryWrapper<ActivityDO>()
                        .eq(status != null, ActivityDO::getStatus, status)
                        .like(keyword != null && !keyword.isEmpty(), ActivityDO::getTitle, keyword)
                        .orderByDesc(ActivityDO::getCreatedAt));
        return toPageResult(page);
    }

    /**
     * 活动详情：布隆过滤器防穿透 + 多级缓存 + 个人状态装饰。
     */
    public ActivityDetailVO getDetail(Long activityId, Long viewerId) {
        if (!bloomFilterService.mightContainActivity(activityId)) {
            // 误判率 1%：不存在的 ID 极大概率在此被拦截，DB 零压力
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        String cacheKey = RedisKeyConstants.activityInfo(activityId);
        ActivityVO base = multiLevelCacheService.get(cacheKey, k -> loadBase(activityId), ActivityVO.class);
        if (base == null) {
            // 空值占位（60s 防穿透）或活动真不存在
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        ActivityDetailVO detail = new ActivityDetailVO();
        org.springframework.beans.BeanUtils.copyProperties(base, detail);
        detail.setReviewRemark(null); // 审核备注不缓存进详情，按下文单独填充
        // 浏览量（Redis INCR，展示 + 推荐排序用）
        Long viewCount = stringRedisTemplate.opsForValue()
                .increment(RedisKeyConstants.activityViewCount(activityId));
        detail.setViewCount(viewCount);
        detail.setRemaining(stockRedisService.getStock(activityId));

        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity != null && activity.getStatus() == ActivityStatus.REJECTED.getCode()) {
            detail.setReviewRemark(activity.getReviewRemark());
        }

        // 观察者个人状态装饰
        if (viewerId != null) {
            detail.setMine(activity != null && activity.getOrganizerId().equals(viewerId));
            RegistrationDO reg = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                    .eq(RegistrationDO::getActivityId, activityId)
                    .eq(RegistrationDO::getUserId, viewerId)
                    .last("LIMIT 1"));
            if (reg != null) {
                detail.setMyRegistrationId(reg.getId());
                detail.setMyRegistrationStatus(RegistrationStatus.ofCode(reg.getStatus()).getDesc());
            }
        }
        return detail;
    }

    private ActivityVO loadBase(Long activityId) {
        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1) {
            return null;
        }
        return toVO(activity);
    }

    // ==================== 写操作 ====================

    /**
     * 创建活动（组织者）。默认进入 DRAFT，可再次编辑后提交审核。
     */
    @Transactional(rollbackFor = Exception.class)
    public ActivityVO create(ActivityUpsertRequest request, Long organizerId) {
        validateTimes(request);
        ActivityDO activity = new ActivityDO();
        applyUpsert(activity, request);
        activity.setOrganizerId(organizerId);
        activity.setStatus(ActivityStatus.DRAFT.getCode());
        activity.setConfirmedCount(0);
        activity.setCheckedInCount(0);
        activity.setVersion(0);
        activity.setDeleted(0);
        activityMapper.insert(activity);
        bloomFilterService.addActivityId(activity.getId());
        log.info("活动创建 activityId={} organizerId={}", activity.getId(), organizerId);
        return toVO(activity);
    }

    /**
     * 编辑活动：DRAFT 可全量编辑；PUBLISHED 仅允许调整容量与描述（报名数据已产生）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ActivityVO update(Long activityId, ActivityUpsertRequest request, Long operatorId, boolean isAdmin) {
        ActivityDO activity = requireActivity(activityId);
        checkEditable(activity, operatorId, isAdmin);
        if (activity.getStatus() == ActivityStatus.PUBLISHED.getCode()) {
            // 已发布：仅允许容量与描述调整，时间与标题锁定（避免已报名用户利益受损）
            activity.setDescription(request.getDescription());
            if (request.getCapacity() != null && !request.getCapacity().equals(activity.getCapacity())) {
                adjustCapacityInternal(activity, request.getCapacity(), operatorId);
            }
        } else {
            validateTimes(request);
            applyUpsert(activity, request);
        }
        activityMapper.updateById(activity);
        evictDetailCache(activityId);
        log.info("活动编辑 activityId={} operatorId={} status={}", activityId, operatorId, activity.getStatus());
        return toVO(activity);
    }

    /**
     * 提交审核：DRAFT/REJECTED -> PENDING_REVIEW。
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitForReview(Long activityId, Long organizerId) {
        ActivityDO activity = requireOwnedActivity(activityId, organizerId);
        if (activity.getStatus() != ActivityStatus.DRAFT.getCode()
                && activity.getStatus() != ActivityStatus.REJECTED.getCode()) {
            throw BizException.of(ErrorCode.ACTIVITY_STATUS_CONFLICT);
        }
        activity.setStatus(ActivityStatus.PENDING_REVIEW.getCode());
        activity.setReviewRemark(null);
        activityMapper.updateById(activity);
        log.info("活动提交审核 activityId={} organizerId={}", activityId, organizerId);
    }

    /**
     * 审核通过：PENDING_REVIEW -> PUBLISHED，同步完成库存预热 + 缓存失效。
     * 锁注解演示：AOP 代理 + SpEL 取参（同类外部调用，代理生效）。
     */
    @DistributedLock(key = "'activity:publish:' + #activityId", waitTimeMillis = 3000)
    @Transactional(rollbackFor = Exception.class)
    public ActivityVO approvePublish(Long activityId, String remark) {
        ActivityDO activity = requireActivity(activityId);
        if (activity.getStatus() != ActivityStatus.PENDING_REVIEW.getCode()) {
            throw BizException.of(ErrorCode.ACTIVITY_STATUS_CONFLICT);
        }
        activity.setStatus(ActivityStatus.PUBLISHED.getCode());
        activity.setReviewRemark(remark);
        int affected = activityMapper.updateById(activity);
        if (affected == 0) {
            // 乐观锁冲突：审核并发
            throw BizException.of(ErrorCode.ACTIVITY_STATUS_CONFLICT);
        }
        // 发布即预热（锁内调用，DB 权威口径：capacity - 有效报名数）
        int remaining = countActiveRegistrations(activityId);
        stockRedisService.preloadStock(activityId,
                Math.max(0, activity.getCapacity() - remaining),
                ActivityStatus.PUBLISHED.getCode(),
                activity.getSignupStartTime(), activity.getSignupEndTime(), activity.getEndTime());
        evictDetailCache(activityId);
        notifyOrganizer(activity.getOrganizerId(), activity.getId(), activity.getTitle(),
                com.campusflow.common.enums.NotifyType.REVIEW_PASSED, "您的活动《" + activity.getTitle() + "》已审核通过并发布");
        log.info("活动发布 activityId={} remaining={}", activityId, remaining);
        return toVO(activity);
    }

    @Transactional(rollbackFor = Exception.class)
    public ActivityVO rejectPublish(Long activityId, String remark, Long operatorId) {
        ActivityDO activity = requireActivity(activityId);
        if (activity.getStatus() != ActivityStatus.PENDING_REVIEW.getCode()) {
            throw BizException.of(ErrorCode.ACTIVITY_STATUS_CONFLICT);
        }
        activity.setStatus(ActivityStatus.REJECTED.getCode());
        activity.setReviewRemark(remark == null || remark.isEmpty() ? "不符合平台规范" : remark);
        activityMapper.updateById(activity);
        notifyOrganizer(activity.getOrganizerId(), activity.getId(), activity.getTitle(),
                com.campusflow.common.enums.NotifyType.REVIEW_REJECTED,
                "您的活动《" + activity.getTitle() + "》未通过审核：" + activity.getReviewRemark());
        return toVO(activity);
    }

    /**
     * 取消活动：组织者/管理员操作。清 Redis 库存、通知全部有效报名与候补用户。
     */
    @DistributedLock(key = "'activity:status:' + #activityId", waitTimeMillis = 3000)
    @Transactional(rollbackFor = Exception.class)
    public void cancelActivity(Long activityId, Long operatorId, boolean isAdmin) {
        ActivityDO activity = requireActivity(activityId);
        if (!isAdmin && !activity.getOrganizerId().equals(operatorId)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (!ActivityStatus.ofCode(activity.getStatus()).canCancel()) {
            throw BizException.of(ErrorCode.ACTIVITY_CANNOT_CANCEL);
        }
        activity.setStatus(ActivityStatus.CANCELLED.getCode());
        activityMapper.updateById(activity);
        // 清理 Redis 库存/元数据，防止取消后仍可报名（成员集合保留供对账）
        stockRedisService.evictStockKeys(activityId);
        evictDetailCache(activityId);
        // 通知所有有效报名用户 + 候补用户（通知经 MQ 异步落库）
        List<Long> userIds = registrationMapper.selectList(new LambdaQueryWrapper<RegistrationDO>()
                        .eq(RegistrationDO::getActivityId, activityId)
                        .in(RegistrationDO::getStatus,
                                RegistrationStatus.RESERVED.getCode(),
                                RegistrationStatus.CONFIRMED.getCode()))
                .stream().map(RegistrationDO::getUserId).toList();
        for (Long userId : userIds) {
            notifyUser(userId, com.campusflow.common.enums.NotifyType.ACTIVITY_CANCELLED,
                    "活动取消通知", "您报名的活动《" + activity.getTitle() + "》已被组织者取消", "activity:" + activityId);
        }
        log.info("活动取消 activityId={} operatorId={} notifiedUsers={}", activityId, operatorId, userIds.size());
    }

    /**
     * 已发布活动容量调整（组织者）：锁内重算 Redis 剩余名额。
     */
    public void adjustCapacity(Long activityId, int newCapacity, Long operatorId) {
        ActivityDO activity = requireOwnedActivity(activityId, operatorId);
        if (activity.getStatus() != ActivityStatus.PUBLISHED.getCode()
                && activity.getStatus() != ActivityStatus.DRAFT.getCode()) {
            throw BizException.of(ErrorCode.ACTIVITY_CANNOT_MODIFY);
        }
        adjustCapacityInternal(activity, newCapacity, operatorId);
        activityMapper.updateById(activity);
        log.info("活动容量调整 activityId={} newCapacity={} operatorId={}", activityId, newCapacity, operatorId);
    }

    @DistributedLock(key = "'activity:capacity:' + #activity.id", waitTimeMillis = 3000)
    protected void adjustCapacityInternal(ActivityDO activity, int newCapacity, Long operatorId) {
        activity.setCapacity(newCapacity);
        if (activity.getStatus() == ActivityStatus.PUBLISHED.getCode()) {
            int active = countActiveRegistrations(activity.getId());
            int remaining = Math.max(0, newCapacity - active);
            stockRedisService.preloadStock(activity.getId(), remaining,
                    activity.getStatus(), activity.getSignupStartTime(),
                    activity.getSignupEndTime(), activity.getEndTime());
            evictDetailCache(activity.getId());
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 库存懒预热自愈：报名时发现 Redis 元数据缺失（预热丢失/Redis 重启/种子既存数据），
     * 在分布式锁内按 DB 权威口径（capacity - 有效报名数）重建，然后由调用方重试 Lua。
     */
    @DistributedLock(key = "'stock:warmup:' + #activityId", waitTimeMillis = 3000)
    public void warmupStockIfNeeded(Long activityId) {
        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1) {
            return;
        }
        ActivityStatus status = ActivityStatus.ofCode(activity.getStatus());
        if (status != ActivityStatus.PUBLISHED && status != ActivityStatus.ONGOING) {
            return;
        }
        int remaining = Math.max(0, activity.getCapacity() - countActiveRegistrations(activityId));
        stockRedisService.preloadStock(activityId, remaining, activity.getStatus(),
                activity.getSignupStartTime(), activity.getSignupEndTime(), activity.getEndTime());
        log.info("库存懒预热自愈完成 activityId={} remaining={}", activityId, remaining);
    }

    public int countActiveRegistrations(Long activityId) {
        Long count = registrationMapper.selectCount(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activityId)
                .in(RegistrationDO::getStatus,
                        RegistrationStatus.CONFIRMED.getCode(),
                        RegistrationStatus.CHECKED_IN.getCode()));
        return count == null ? 0 : count.intValue();
    }

    public ActivityDO requireActivity(Long activityId) {
        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        return activity;
    }

    public ActivityDO requireOwnedActivity(Long activityId, Long organizerId) {
        ActivityDO activity = requireActivity(activityId);
        if (!activity.getOrganizerId().equals(organizerId)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return activity;
    }

    private void checkEditable(ActivityDO activity, Long operatorId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        if (!activity.getOrganizerId().equals(operatorId)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (ActivityStatus.ofCode(activity.getStatus()) == ActivityStatus.FINISHED
                || ActivityStatus.ofCode(activity.getStatus()) == ActivityStatus.CANCELLED) {
            throw BizException.of(ErrorCode.ACTIVITY_CANNOT_MODIFY);
        }
    }

    private void validateTimes(ActivityUpsertRequest request) {
        LocalDateTime now = LocalDateTime.now();
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw BizException.of(ErrorCode.ACTIVITY_TIME_CONFLICT, "活动结束时间必须晚于开始时间");
        }
        if (!request.getSignupEndTime().isAfter(request.getSignupStartTime())) {
            throw BizException.of(ErrorCode.ACTIVITY_TIME_CONFLICT, "报名截止时间必须晚于报名开始时间");
        }
        if (request.getSignupEndTime().isAfter(request.getStartTime())) {
            throw BizException.of(ErrorCode.ACTIVITY_TIME_CONFLICT, "报名截止时间不能晚于活动开始时间");
        }
        if (request.getStartTime().isBefore(now)) {
            throw BizException.of(ErrorCode.ACTIVITY_TIME_CONFLICT, "活动开始时间不能早于当前时间");
        }
    }

    private void applyUpsert(ActivityDO activity, ActivityUpsertRequest request) {
        activity.setTitle(request.getTitle());
        activity.setDescription(request.getDescription());
        activity.setCategory(request.getCategory());
        activity.setCoverUrl(request.getCoverUrl());
        activity.setLocation(request.getLocation());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());
        activity.setSignupStartTime(request.getSignupStartTime());
        activity.setSignupEndTime(request.getSignupEndTime());
        activity.setCapacity(request.getCapacity());
    }

    /** 写后删除缓存（Cache Aside Del 策略）并广播通知全部实例失效本地缓存 */
    public void evictDetailCache(Long activityId) {
        String cacheKey = RedisKeyConstants.activityInfo(activityId);
        multiLevelCacheService.evict(cacheKey);
        // 广播模式：每个实例各自失效本地 Caffeine，容忍秒级脏读由本地 TTL 兜底
        messageDeliverService.createAndSend(MqConstantsPrefix.CACHE_EVICT,
                com.campusflow.common.constant.MqConstants.TOPIC_CACHE_EVICT,
                com.campusflow.common.constant.MqConstants.TAG_CACHE_EVICT,
                "CACHE_EVICT", Map.of("cacheKey", cacheKey));
    }

    private void notifyOrganizer(Long organizerId, Long activityId, String title,
                                 com.campusflow.common.enums.NotifyType type, String content) {
        notifyUser(organizerId, type, "活动审核通知", content, "activity:" + activityId);
    }

    public void notifyUser(Long userId, com.campusflow.common.enums.NotifyType type,
                           String title, String content, String bizRef) {
        messageDeliverService.createAndSend("NTF",
                com.campusflow.common.constant.MqConstants.TOPIC_NOTIFY,
                com.campusflow.common.constant.MqConstants.TAG_NOTIFY,
                "NOTIFY", Map.of(
                        "userId", userId,
                        "type", type.getCode(),
                        "title", title,
                        "content", content,
                        "bizRef", bizRef == null ? "" : bizRef));
    }

    private PageResult<ActivityVO> toPageResult(Page<ActivityDO> page) {
        List<ActivityVO> records = page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public ActivityVO toVO(ActivityDO activity) {
        ActivityCategory category = ActivityCategory.ofCode(activity.getCategory());
        UserDO organizer = userMapper.selectById(activity.getOrganizerId());
        return ActivityVO.builder()
                .id(activity.getId())
                .title(activity.getTitle())
                .category(activity.getCategory())
                .categoryName(category == null ? "其他" : category.getDesc())
                .coverUrl(activity.getCoverUrl())
                .location(activity.getLocation())
                .startTime(activity.getStartTime())
                .endTime(activity.getEndTime())
                .signupStartTime(activity.getSignupStartTime())
                .signupEndTime(activity.getSignupEndTime())
                .capacity(activity.getCapacity())
                .confirmedCount(activity.getConfirmedCount())
                .status(activity.getStatus())
                .statusDesc(ActivityStatus.ofCode(activity.getStatus()).getDesc())
                .organizerId(activity.getOrganizerId())
                .organizerName(organizer == null ? "未知" : organizer.getNickname())
                .createdAt(activity.getCreatedAt())
                .build();
    }

    /** JSON 序列化自检（保证缓存对象可序列化；失败快速暴露配置问题） */
    static {
        JsonUtils.toJson(Map.of("ok", 1));
    }

    /** 避免在类名上与 MqConstants 冲突的广播消息键前缀 */
    private static final class MqConstantsPrefix {
        static final String CACHE_EVICT = "EVICT";
    }
}
