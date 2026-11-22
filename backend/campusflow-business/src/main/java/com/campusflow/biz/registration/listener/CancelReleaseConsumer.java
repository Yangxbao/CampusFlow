package com.campusflow.biz.registration.listener;

import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.waitlist.service.WaitlistPromoteService;
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
 * 取消释放消息消费者：候补晋级的兜底触发器。
 * <p>为什么需要兜底：取消链路在事务提交后同步触发晋级，但若同步触发时
 * 晋级服务异常（锁超时/Redis 抖动），该消息消费时再次触发，保证"取消必有晋级"最终成立。
 * tryPromoteNext 内部幂等（Lua ZPOPMIN 弹出即消费 + DB CAS）。
 */
@Slf4j
@Configuration
public class CancelReleaseConsumer {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer cancelReleaseMessageConsumer(
            MqConsumerFactory factory,
            MessageConsumeLogService consumeLogService,
            MessageDeliverService messageDeliverService,
            WaitlistPromoteService waitlistPromoteService) {
        return factory.create(MqConstants.GROUP_CANCEL, MqConstants.TOPIC_CANCEL_RELEASE, false,
                (List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) -> {
                    for (MessageExt msg : msgs) {
                        String msgKey = msg.getKeys();
                        try {
                            if (!consumeLogService.tryBeginConsume(msgKey, MqConstants.GROUP_CANCEL)) {
                                continue;
                            }
                            MqPayloads.CancelReleaseMessage payload = JsonUtils.fromJson(
                                    new String(msg.getBody(), StandardCharsets.UTF_8),
                                    MqPayloads.CancelReleaseMessage.class);
                            if (payload != null) {
                                // 兜底触发候补晋级（幂等）
                                waitlistPromoteService.tryPromoteNext(payload.activityId());
                                log.info("取消释放消息处理完成 registrationId={} activityId={}",
                                        payload.registrationId(), payload.activityId());
                            }
                            consumeLogService.markSuccess(msgKey, MqConstants.GROUP_CANCEL);
                            messageDeliverService.confirmConsumed(msgKey);
                        } catch (Exception e) {
                            log.error("取消释放消息处理失败 msgKey={}", msgKey, e);
                            consumeLogService.markFailed(msgKey, MqConstants.GROUP_CANCEL);
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                });
    }
}
