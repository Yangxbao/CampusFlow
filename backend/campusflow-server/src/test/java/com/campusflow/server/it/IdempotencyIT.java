package com.campusflow.server.it;

import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.notify.entity.NotifyMessageDO;
import com.campusflow.biz.notify.mapper.NotifyMessageMapper;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.service.RegistrationService;
import com.campusflow.biz.registration.service.RegistrationTxService;
import com.campusflow.biz.user.entity.UserDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【场景 B/D：一人一单与消息幂等】数据库唯一约束最终防线 + 重复消费安全 + 真实 MQ 重复投递。
 */
class IdempotencyIT extends IntegrationTestBase {

    @Autowired
    private RegistrationService registrationService;
    @Autowired
    private RegistrationTxService registrationTxService;
    @Autowired
    private NotifyMessageMapper notifyMessageMapper;
    @Autowired
    private org.apache.rocketmq.client.producer.DefaultMQProducer producer;

    @Test
    void 数据库唯一约束拦截绕过Redis的重复报名() {
        UserDO user = createUser("dup");
        com.campusflow.biz.activity.entity.ActivityDO activity = createPublishedActivity("唯一约束防线", 50);
        // 正常报名并等待确认
        registrationService.signup(activity.getId(), user.getId());
        awaitConfirmedQuietly(activity.getId(), 1);

        // 模拟"绕过 Redis 直接写库"的极端情况：第二行插入必须被 uk_activity_user 拦截
        RegistrationDO bypass = new RegistrationDO();
        bypass.setActivityId(activity.getId());
        bypass.setUserId(user.getId());
        bypass.setStatus(com.campusflow.common.enums.RegistrationStatus.CONFIRMED.getCode());
        bypass.setRegisteredAt(java.time.LocalDateTime.now());
        bypass.setVersion(0);
        assertThrows(DuplicateKeyException.class, () -> registrationMapper.insert(bypass),
                "uk_activity_user 唯一约束必须拦截重复报名（最终防线）");
    }

    @Test
    void 重复消费同一确认消息不产生重复报名() {
        UserDO user = createUser("dupconsume");
        var activity = createPublishedActivity("重复消费安全", 50);
        registrationService.signup(activity.getId(), user.getId());
        awaitConfirmedQuietly(activity.getId(), 1);

        RegistrationDO reg = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activity.getId())
                .eq(RegistrationDO::getUserId, user.getId()));

        // 直接重复调用确认逻辑（等价于消息重复投递后再次消费）
        MqPayloads.RegistrationConfirmMessage duplicate = new MqPayloads.RegistrationConfirmMessage(
                activity.getId(), user.getId(), 0L, "REG-TEST-DUP-" + System.nanoTime(), 0,
                System.currentTimeMillis());
        registrationTxService.confirmRegistration(duplicate);
        registrationTxService.confirmRegistration(duplicate);

        Long rows = registrationMapper.selectCount(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activity.getId())
                .eq(RegistrationDO::getUserId, user.getId()));
        assertEquals(1, rows, "重复消费后仍只有一条报名记录");
        assertEquals(reg.getId(), registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                        .eq(RegistrationDO::getActivityId, activity.getId())
                        .eq(RegistrationDO::getUserId, user.getId())).getId(),
                "幂等返回同一条记录");
    }

    @Test
    void 真实MQ重复投递消费幂等() throws Exception {
        // 真实 RocketMQ：同一 msgKey 发送两条相同通知消息，消费端必须幂等只落一条
        String msgKey = "ITF-NOTIFY-" + System.nanoTime();
        String title = "幂等验证通知-" + UUID.randomUUID().toString().substring(0, 6);
        UserDO user = createUser("mqdup");
        MqPayloads.NotifyMessage payload = new MqPayloads.NotifyMessage(
                user.getId(), 0, title, "真实 MQ 重复投递幂等验证", "it:" + msgKey);
        byte[] body = com.campusflow.common.util.JsonUtils.toJson(payload)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        org.apache.rocketmq.common.message.Message msg1 =
                new org.apache.rocketmq.common.message.Message(
                        com.campusflow.common.constant.MqConstants.TOPIC_NOTIFY,
                        com.campusflow.common.constant.MqConstants.TAG_NOTIFY, msgKey, body);
        org.apache.rocketmq.common.message.Message msg2 =
                new org.apache.rocketmq.common.message.Message(
                        com.campusflow.common.constant.MqConstants.TOPIC_NOTIFY,
                        com.campusflow.common.constant.MqConstants.TAG_NOTIFY, msgKey, body);
        org.apache.rocketmq.client.producer.SendResult r1 = producer.send(msg1);
        org.apache.rocketmq.client.producer.SendResult r2 = producer.send(msg2);
        assertEquals(org.apache.rocketmq.client.producer.SendStatus.SEND_OK, r1.getSendStatus(), "Broker 真实收到消息1");
        assertEquals(org.apache.rocketmq.client.producer.SendStatus.SEND_OK, r2.getSendStatus(), "Broker 真实收到消息2（重复）");

        // 等待消费端处理两条重复消息
        long deadline = System.currentTimeMillis() + 20000;
        Long rows = 0L;
        while (System.currentTimeMillis() < deadline) {
            rows = notifyMessageMapper.selectCount(new LambdaQueryWrapper<NotifyMessageDO>()
                    .eq(NotifyMessageDO::getTitle, title));
            if (rows != null && rows >= 1) {
                Thread.sleep(2000); // 等第二条也被处理完
                rows = notifyMessageMapper.selectCount(new LambdaQueryWrapper<NotifyMessageDO>()
                        .eq(NotifyMessageDO::getTitle, title));
                break;
            }
            Thread.sleep(300);
        }
        assertEquals(1L, rows, "重复投递的两条相同 msgKey 消息只落一条通知（消费幂等）");
        assertTrue(rows >= 1);
    }

    private void awaitConfirmedQuietly(long activityId, int expected) {
        try {
            awaitConfirmed(activityId, expected, 20000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
