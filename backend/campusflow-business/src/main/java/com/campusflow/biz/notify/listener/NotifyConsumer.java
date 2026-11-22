package com.campusflow.biz.notify.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.notify.entity.NotifyMessageDO;
import com.campusflow.biz.notify.mapper.NotifyMessageMapper;
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
 * 站内通知消费者：解析通知消息并批量落库。
 * 幂等：NotifyMessage 消息无业务键唯一约束，但消费幂等表拦截重复投递；
 * 内容写入天然可重复执行（相同消息重复落库由 msgKey 幂等避免）。
 */
@Slf4j
@Configuration
public class NotifyConsumer {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer notifyMessageConsumer(
            MqConsumerFactory factory,
            MessageConsumeLogService consumeLogService,
            MessageDeliverService messageDeliverService,
            NotifyMessageMapper notifyMessageMapper) {
        return factory.create(MqConstants.GROUP_NOTIFY, MqConstants.TOPIC_NOTIFY, false,
                (List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) -> {
                    for (MessageExt msg : msgs) {
                        String msgKey = msg.getKeys();
                        try {
                            if (!consumeLogService.tryBeginConsume(msgKey, MqConstants.GROUP_NOTIFY)) {
                                continue;
                            }
                            MqPayloads.NotifyMessage payload = JsonUtils.fromJson(
                                    new String(msg.getBody(), StandardCharsets.UTF_8),
                                    MqPayloads.NotifyMessage.class);
                            if (payload == null) {
                                log.error("通知消息解析失败 msgKey={}", msgKey);
                                consumeLogService.markSuccess(msgKey, MqConstants.GROUP_NOTIFY);
                                continue;
                            }
                            NotifyMessageDO notify = new NotifyMessageDO();
                            notify.setUserId(payload.userId());
                            notify.setTitle(payload.title());
                            notify.setContent(payload.content());
                            notify.setType(payload.type());
                            notify.setBizRef(payload.bizRef());
                            notify.setIsRead(0);
                            notifyMessageMapper.insert(notify);
                            consumeLogService.markSuccess(msgKey, MqConstants.GROUP_NOTIFY);
                            messageDeliverService.confirmConsumed(msgKey);
                        } catch (Exception e) {
                            log.error("通知消息处理失败 msgKey={}", msgKey, e);
                            consumeLogService.markFailed(msgKey, MqConstants.GROUP_NOTIFY);
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                });
    }
}
