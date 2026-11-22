package com.campusflow.biz.waitlist.listener;

import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.util.JsonUtils;
import com.campusflow.infra.mq.MessageConsumeLogService;
import com.campusflow.infra.mq.MessageDeliverService;
import com.campusflow.infra.mq.MqConsumerFactory;
import com.campusflow.infra.property.CampusflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 候补晋级消息消费者：兜底通知（主通知已在晋级时同步写入发件箱），
 * 并校验 DB 晋级状态与 Redis 预留状态的一致性（不一致时记录并交给补偿巡检）。
 */
@Slf4j
@Configuration
public class WaitlistPromoteConsumer {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer waitlistPromoteMessageConsumer(
            MqConsumerFactory factory,
            MessageConsumeLogService consumeLogService,
            MessageDeliverService messageDeliverService,
            CampusflowProperties properties) {
        return factory.create(MqConstants.GROUP_WAITLIST, MqConstants.TOPIC_WAITLIST_PROMOTE, false,
                (List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) -> {
                    for (MessageExt msg : msgs) {
                        String msgKey = msg.getKeys();
                        try {
                            if (!consumeLogService.tryBeginConsume(msgKey, MqConstants.GROUP_WAITLIST)) {
                                continue;
                            }
                            MqPayloads.WaitlistPromoteMessage payload = JsonUtils.fromJson(
                                    new String(msg.getBody(), StandardCharsets.UTF_8),
                                    MqPayloads.WaitlistPromoteMessage.class);
                            if (payload != null) {
                                LocalDateTime deadline = LocalDateTime.ofInstant(
                                        Instant.ofEpochMilli(payload.confirmDeadlineMillis()),
                                        ZoneId.systemDefault());
                                log.info("候补晋级消息处理完成 waitlistId={} userId={} 截止时间={}",
                                        payload.waitlistId(), payload.userId(),
                                        deadline.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                            }
                            consumeLogService.markSuccess(msgKey, MqConstants.GROUP_WAITLIST);
                            messageDeliverService.confirmConsumed(msgKey);
                        } catch (Exception e) {
                            log.error("候补晋级消息处理失败 msgKey={}", msgKey, e);
                            consumeLogService.markFailed(msgKey, MqConstants.GROUP_WAITLIST);
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                });
    }
}
