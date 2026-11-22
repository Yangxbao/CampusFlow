package com.campusflow.biz.checkin.listener;

import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.util.JsonUtils;
import com.campusflow.infra.mq.MessageConsumeLogService;
import com.campusflow.infra.mq.MessageDeliverService;
import com.campusflow.infra.mq.MqConsumerFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 签到完成消息消费者：统计聚合与闭环确认。
 * （通知已在签到事务内通过发件箱落库；此处负责消费闭环与后续统计钩子。）
 */
@Slf4j
@Configuration
public class CheckinConsumer {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer checkinMessageConsumer(
            MqConsumerFactory factory,
            MessageConsumeLogService consumeLogService,
            MessageDeliverService messageDeliverService) {
        return factory.create(MqConstants.GROUP_CHECKIN, MqConstants.TOPIC_CHECKIN, false,
                (List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) -> {
                    for (MessageExt msg : msgs) {
                        String msgKey = msg.getKeys();
                        try {
                            if (!consumeLogService.tryBeginConsume(msgKey, MqConstants.GROUP_CHECKIN)) {
                                continue;
                            }
                            MqPayloads.CheckinMessage payload = JsonUtils.fromJson(
                                    new String(msg.getBody(), StandardCharsets.UTF_8),
                                    MqPayloads.CheckinMessage.class);
                            if (payload != null) {
                                log.info("签到消息消费完成 activityId={} registrationId={} userId={}",
                                        payload.activityId(), payload.registrationId(), payload.userId());
                            }
                            consumeLogService.markSuccess(msgKey, MqConstants.GROUP_CHECKIN);
                            messageDeliverService.confirmConsumed(msgKey);
                        } catch (Exception e) {
                            log.error("签到消息处理失败 msgKey={}", msgKey, e);
                            consumeLogService.markFailed(msgKey, MqConstants.GROUP_CHECKIN);
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                });
    }
}
