package com.campusflow.server.it;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.audit.entity.StockAuditLogDO;
import com.campusflow.biz.audit.mapper.StockAuditLogMapper;
import com.campusflow.biz.compensation.scheduler.CompensationScheduler;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.entity.RegistrationReservationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.registration.mapper.RegistrationReservationMapper;
import com.campusflow.biz.registration.service.RegistrationService;
import com.campusflow.biz.registration.service.RegistrationTxService;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.common.constant.RedisKeyConstants;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.infra.mq.MessageDeliverDO;
import com.campusflow.infra.mq.MessageDeliverMapper;
import com.campusflow.infra.mq.MessageDeliverService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【场景 D + 故障恢复】预留超时恢复（重发消息）与补偿释放、本地消息重投、库存对账漂移修复。
 * <p>覆盖总控要求的故障场景：
 * - "服务在 Lua 执行后、发送消息前突然退出" -> 预留记录 PENDING_CONFIRM -> 恢复重发 -> 消费确认；
 * - "预留持续无法确认" -> 补偿释放名额（幂等，audit 记账）；
 * - "本地消息发送失败" -> 指数退避重投成功；
 * - "Redis 与 MySQL 库存出现差异" -> 对账修复 + 审计。
 */
class CompensationRecoveryIT extends IntegrationTestBase {

    @Autowired
    private RegistrationService registrationService;
    @Autowired
    private RegistrationTxService registrationTxService;
    @Autowired
    private RegistrationReservationMapper reservationMapper;
    @Autowired
    private RegistrationMapper registrationMapper;
    @Autowired
    private CompensationScheduler scheduler;
    @Autowired
    private StockAuditLogMapper stockAuditLogMapper;
    @Autowired
    private MessageDeliverService messageDeliverService;
    @Autowired
    private MessageDeliverMapper messageDeliverMapper;

    @Test
    void 预留超时后恢复重发消息并完成确认() throws Exception {
        var activity = createPublishedActivity("预留恢复验证", 10);
        UserDO user = createUser("recover");

        // 走完整报名链路（预留 + 消息 + 发送 + 消费确认）
        registrationService.signup(activity.getId(), user.getId());
        awaitConfirmed(activity.getId(), 1, 20000);

        // 模拟"确认丢失"：把预留状态回拨为 PENDING_CONFIRM 并把过期时间置为已过期
        RegistrationDO reg = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activity.getId())
                .eq(RegistrationDO::getUserId, user.getId()));
        RegistrationReservationDO reservation = reservationMapper.selectOne(
                new LambdaQueryWrapper<RegistrationReservationDO>()
                        .eq(RegistrationReservationDO::getActivityId, activity.getId())
                        .eq(RegistrationReservationDO::getUserId, user.getId())
                        .orderByDesc(RegistrationReservationDO::getId)
                        .last("LIMIT 1"));
        assertNotNull(reservation, "预留记录必须存在");
        RegistrationReservationDO reset = reservationMapper.selectById(reservation.getId());
        reset.setStatus(RegistrationReservationDO.STATUS_PENDING_CONFIRM);
        reset.setExpireAt(LocalDateTime.now().minusMinutes(2));
        reservationMapper.updateById(reset);

        // 触发恢复任务：应发现"报名实际已确认" -> 推进预留状态（对账修复，不重复落库）
        scheduler.reservationRecovery();

        RegistrationReservationDO after = reservationMapper.selectById(reservation.getId());
        assertEquals(RegistrationReservationDO.STATUS_CONFIRMED, after.getStatus(),
                "预留状态应被对账修复为 CONFIRMED（报名已有效，不重复确认也不误释放）");
        assertEquals(1, countConfirmed(activity.getId(), user.getId()), "报名仍只有一条");
    }

    @Test
    void 预留重试耗尽后补偿释放名额() throws Exception {
        var activity = createPublishedActivity("补偿释放验证", 10);
        UserDO user = createUser("release");

        // 只做 Lua 预留 + 预留落库，模拟"发送消息前进程退出"（无 MQ 消息、无报名确认）
        // 直接构造预留记录（PENDING_CONFIRM，retry 已达上限，已过期）
        var reservation = registrationTxService.persistReservation(activity.getId(), user.getId(), 0);
        RegistrationReservationDO edit = reservationMapper.selectById(reservation.getId());
        edit.setStatus(RegistrationReservationDO.STATUS_PENDING_CONFIRM);
        edit.setRetryCount(5); // 达到 reservationMaxRetry 上限
        edit.setExpireAt(LocalDateTime.now().minusMinutes(2));
        reservationMapper.updateById(edit);
        // Redis 侧手工模拟已预留（Lua 已扣库存 + 成员已登记）
        stringRedisTemplate.opsForValue().decrement(RedisKeyConstants.activityStock(activity.getId()));
        stringRedisTemplate.opsForSet().add(RedisKeyConstants.activityMembers(activity.getId()),
                String.valueOf(user.getId()));
        long stockBefore = Long.parseLong(String.valueOf(stringRedisTemplate.opsForValue()
                .get(RedisKeyConstants.activityStock(activity.getId()))));

        // 触发恢复任务：重试耗尽 -> 补偿释放
        scheduler.reservationRecovery();

        RegistrationReservationDO after = reservationMapper.selectById(reservation.getId());
        assertEquals(RegistrationReservationDO.STATUS_RELEASED, after.getStatus(), "预留状态 = RELEASED");
        RegistrationDO reg = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activity.getId())
                .eq(RegistrationDO::getUserId, user.getId()));
        // 本场景从未产生报名记录（预留后进程退出），报名行应为空；若存在则必须为确认失败终态
        assertTrue(reg == null || RegistrationStatus.CONFIRM_FAILED.getCode() == reg.getStatus(),
                "不允许出现有效的确认报名（reg=" + (reg == null ? "null" : reg.getStatus()) + "）");

        Long stockAfter = Long.parseLong(String.valueOf(stringRedisTemplate.opsForValue()
                .get(RedisKeyConstants.activityStock(activity.getId()))));
        assertEquals(stockBefore + 1, stockAfter, "库存已幂等加回");

        Long audits = stockAuditLogMapper.selectCount(new LambdaQueryWrapper<StockAuditLogDO>()
                .eq(StockAuditLogDO::getBizKey, "COMP-REL-" + reservation.getId()));
        assertEquals(1L, audits, "补偿释放审计恰好一条");
    }

    @Test
    void 本地消息表失败消息自动重投成功() throws Exception {
        // 构造一条待发送通知消息（NEW 状态，立即到期）
        UserDO user = createUser("msgretry");
        MessageDeliverDO record = messageDeliverService.createDeliverRecord("NTF",
                com.campusflow.common.constant.MqConstants.TOPIC_NOTIFY,
                com.campusflow.common.constant.MqConstants.TAG_NOTIFY, "NOTIFY",
                new com.campusflow.biz.mq.MqPayloads.NotifyMessage(
                        user.getId(), 7, "重投验证-" + UUID.randomUUID().toString().substring(0, 6),
                        "本地消息表重投链路验证", "it"));
        assertEquals(MessageDeliverDO.STATUS_NEW, record.getStatus());

        // 触发重投任务：与 15s 周期的调度器存在分布式锁竞争（tryRun 拿不到锁会跳过），
        // 故轮询最多 5 次（真实语义：调度器下次触发必然重投）
        MessageDeliverDO after = null;
        for (int i = 0; i < 5; i++) {
            scheduler.messageRetry();
            after = messageDeliverService.getByMsgKey(record.getMsgKey());
            if (after.getStatus() == MessageDeliverDO.STATUS_SENT) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals(MessageDeliverDO.STATUS_SENT, after.getStatus(), "重投后消息状态 = SENT（Broker 真实接收）");
    }

    @Test
    void 库存对账发现并修复漂移() {
        var activity = createPublishedActivity("库存对账验证", 10);
        // 制造漂移：手工扣减 Redis 库存 5（预期漂移 >= 阈值 3）
        for (int i = 0; i < 5; i++) {
            stringRedisTemplate.opsForValue().decrement(RedisKeyConstants.activityStock(activity.getId()));
        }
        long drifted = Long.parseLong(String.valueOf(stringRedisTemplate.opsForValue()
                .get(RedisKeyConstants.activityStock(activity.getId()))));
        assertEquals(5L, drifted, "漂移制造后 Redis 库存 = 5");

        // 触发对账任务
        scheduler.stockReconcile();

        long fixed = Long.parseLong(String.valueOf(stringRedisTemplate.opsForValue()
                .get(RedisKeyConstants.activityStock(activity.getId()))));
        assertEquals(10L, fixed, "对账后 Redis 库存恢复为 DB 权威值 10");

        Long audits = stockAuditLogMapper.selectCount(new LambdaQueryWrapper<StockAuditLogDO>()
                .eq(StockAuditLogDO::getActivityId, activity.getId())
                .eq(StockAuditLogDO::getSource, StockAuditLogDO.SOURCE_RECONCILE));
        assertTrue(audits >= 1, "对账修复必须有 RECONCILE 审计记录");
    }

    private int countConfirmed(long activityId, long userId) {
        Long count = registrationMapper.selectCount(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activityId)
                .eq(RegistrationDO::getUserId, userId)
                .in(RegistrationDO::getStatus, RegistrationStatus.CONFIRMED.getCode(),
                        RegistrationStatus.CHECKED_IN.getCode()));
        return count == null ? 0 : count.intValue();
    }
}
