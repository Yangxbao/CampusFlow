package com.campusflow.infra.mq;

import com.campusflow.infra.property.CampusflowProperties;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 推模式消费者工厂。
 * <p>消费确认遵循 RocketMQ 客户端约定（不是 RabbitMQ 的 basicAck）：
 * 监听器返回 CONSUME_SUCCESS 即向 Broker 确认（Broker 删除在途消息）；
 * 返回 RECONSUME_LATER 触发延迟重投，达到 maxReconsumeTimes 后进入 DLQ(%DLQ%{consumerGroup})。
 * <p>消费线程池为有界规格，防止消费端线程失控。
 */
@Component
@RequiredArgsConstructor
public class MqConsumerFactory {

    private final CampusflowProperties properties;

    /**
     * @param group      消费组
     * @param topic      订阅主题
     * @param broadcast  是否广播模式（缓存失效类 Topic 使用：每个实例都消费，用于本地缓存同步）
     * @param listener   并发消费监听器
     */
    public DefaultMQPushConsumer create(String group, String topic, boolean broadcast,
                                        MessageListenerConcurrently listener) {
        // 多环境消费组隔离：同一 Broker 下 local/test/docker 各自独立组，避免消息被跨环境瓜分
        String suffix = properties.getMq().getGroupSuffix();
        String effectiveGroup = (suffix == null || suffix.isEmpty()) ? group : group + "-" + suffix;
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(effectiveGroup);
        consumer.setNamesrvAddr(properties.getMq().getNameServer());
        consumer.setConsumeThreadMin(properties.getMq().getConsumeThreadMin());
        consumer.setConsumeThreadMax(properties.getMq().getConsumeThreadMax());
        consumer.setMaxReconsumeTimes(properties.getMq().getConsumerMaxReconsume());
        // 消费超时（分钟）：业务处理耗时上限，超过 Broker 会认为消费失败重新投递
        consumer.setConsumeTimeout(5);
        if (broadcast) {
            consumer.setMessageModel(MessageModel.BROADCASTING);
        }
        try {
            consumer.subscribe(topic, "*");
        } catch (Exception e) {
            throw new IllegalStateException("消费者订阅失败: " + topic, e);
        }
        consumer.registerMessageListener(listener);
        return consumer;
    }
}
