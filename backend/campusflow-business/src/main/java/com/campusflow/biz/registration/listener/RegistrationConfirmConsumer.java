package com.campusflow.biz.registration.listener;

import com.campusflow.biz.compensation.entity.CompensationTaskDO;
import com.campusflow.biz.compensation.service.CompensationTaskService;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.registration.service.RegistrationTxService;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
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
import java.util.List;

/**
 * 报名确认消费者（异步落库确认）。
 * <p>可靠性设计：
 * <ul>
 *   <li>消费幂等：双层（Redis 快路径 + DB 唯一约束），重复投递安全；</li>
 *   <li>消费确认：遵循 RocketMQ 约定返回 CONSUME_SUCCESS / RECONSUME_LATER（非 RabbitMQ ack）；</li>
 *   <li>重试上限：达到 maxReconsume 时转补偿任务接管（业务级死信处理），避免消息堆积；</li>
 *   <li>消费完成但确认前宕机：Broker 会重投，幂等表保证安全重放；</li>
 *   <li>消费成功后回调本地消息表 CONFIRMED，形成投递-消费闭环。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class RegistrationConfirmConsumer {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer registrationConfirmMessageConsumer(
            MqConsumerFactory factory,
            RegistrationTxService registrationTxService,
            MessageConsumeLogService consumeLogService,
            MessageDeliverService messageDeliverService,
            CompensationTaskService compensationTaskService,
            CampusflowProperties properties) {

        return factory.create(MqConstants.GROUP_REGISTRATION,
                MqConstants.TOPIC_REGISTRATION_CONFIRM, false,
                (List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) -> {
                    ConsumeConcurrentlyStatus status = ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                    for (MessageExt msg : msgs) {
                        String msgKey = msg.getKeys();
                        try {
                            if (!consumeLogService.tryBeginConsume(msgKey, MqConstants.GROUP_REGISTRATION)) {
                                log.info("报名确认消息重复投递，跳过 msgKey={}", msgKey);
                                continue;
                            }
                            MqPayloads.RegistrationConfirmMessage payload = JsonUtils.fromJson(
                                    new String(msg.getBody(), StandardCharsets.UTF_8),
                                    MqPayloads.RegistrationConfirmMessage.class);
                            if (payload == null) {
                                log.error("报名确认消息体解析失败 msgKey={} -> 转补偿任务", msgKey);
                                compensationTaskService.createTask(
                                        CompensationTaskDO.TYPE_REGISTRATION_CONFIRM, msgKey,
                                        new String(msg.getBody(), StandardCharsets.UTF_8));
                                consumeLogService.markSuccess(msgKey, MqConstants.GROUP_REGISTRATION);
                                continue;
                            }
                            registrationTxService.confirmRegistration(payload);
                            consumeLogService.markSuccess(msgKey, MqConstants.GROUP_REGISTRATION);
                            // 投递-消费闭环：本地消息表置 CONFIRMED
                            messageDeliverService.confirmConsumed(msgKey);
                            log.info("报名确认消费成功 msgKey={} activityId={} userId={}",
                                    msgKey, payload.activityId(), payload.userId());
                        } catch (BizException e) {
                            // 业务性拒绝（活动取消等终态）：记录后确认消费，防止无效重投
                            if (e.getCode() == ErrorCode.ACTIVITY_NOT_PUBLISHED.getCode()
                                    || e.getCode() == ErrorCode.ACTIVITY_NOT_EXIST.getCode()) {
                                log.warn("报名确认业务性拒绝 msgKey={} code={} msg={}", msgKey, e.getCode(), e.getMessage());
                                consumeLogService.markSuccess(msgKey, MqConstants.GROUP_REGISTRATION);
                                continue;
                            }
                            handleFailure(msg, msgKey, consumeLogService, compensationTaskService,
                                    properties, MqConstants.GROUP_REGISTRATION, e);
                            status = ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        } catch (Exception e) {
                            handleFailure(msg, msgKey, consumeLogService, compensationTaskService,
                                    properties, MqConstants.GROUP_REGISTRATION, e);
                            status = ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return status;
                });
    }

    static void handleFailure(MessageExt msg, String msgKey,
                              MessageConsumeLogService consumeLogService,
                              CompensationTaskService compensationTaskService,
                              CampusflowProperties properties, String group, Exception e) {
        consumeLogService.markFailed(msgKey, group);
        if (msg.getReconsumeTimes() >= properties.getMq().getConsumerMaxReconsume()) {
            // 达到重试上限：转补偿任务接管（业务死信处理），确认消费防堆积
            log.error("消息重试达上限，转补偿任务 msgKey={} reconsumeTimes={}",
                    msgKey, msg.getReconsumeTimes(), e);
            compensationTaskService.createTask(
                    CompensationTaskDO.TYPE_REGISTRATION_CONFIRM, msgKey,
                    new String(msg.getBody(), StandardCharsets.UTF_8));
            consumeLogService.markSuccess(msgKey, group);
        }
    }
}
