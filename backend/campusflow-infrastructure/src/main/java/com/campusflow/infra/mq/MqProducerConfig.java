package com.campusflow.infra.mq;

import com.campusflow.infra.property.CampusflowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 生产者装配。
 * <p>用 @Bean(initMethod/destroyMethod) 显式管理 Bean 生命周期：
 * start() 连接 NameServer 失败会快速失败（应用拒绝启动），避免"看似启动成功实际 MQ 不可用"；
 * 容器关闭时 shutdown() 保证在途消息发送完成、连接资源释放。
 * <p>消息持久化由 Broker 承担（SYNC_FLUSH 可配，默认 ASYNC_FLUSH + 主从复制）；
 * 本端通过"同步发送 + 发送结果校验 + 本地消息表重试"保障投递可靠性。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MqProducerConfig {

    private final CampusflowProperties properties;

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer defaultMQProducer() {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getMq().getProducerGroup());
        producer.setNamesrvAddr(properties.getMq().getNameServer());
        producer.setSendMsgTimeout(properties.getMq().getSendTimeoutMs());
        producer.setRetryTimesWhenSendFailed(properties.getMq().getSendRetryTimes());
        producer.setRetryTimesWhenSendAsyncFailed(1);
        producer.setMaxMessageSize(1024 * 256);
        log.info("RocketMQ Producer 初始化: nameServer={} group={}",
                properties.getMq().getNameServer(), properties.getMq().getProducerGroup());
        return producer;
    }
}
