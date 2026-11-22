package com.campusflow.infra.mq;

import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.util.JsonUtils;
import com.campusflow.infra.redis.MultiLevelCacheService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 缓存失效广播消费者。
 * <p>多实例部署下，实例 A 更新活动 -> 实例 B 的 Caffeine 本地缓存仍持有旧值。
 * 方案：更新方发送广播消息（BROADCASTING 模式，每个消费组实例都会收到），
 * 各实例只失效"本地"缓存（不删 Redis，更新方已删）。
 * 广播模式消费失败不重投（无重试语义），所以本地 TTL 30s 是最终兜底 —— 设计上接受秒级脏读。
 */
@Slf4j
@Configuration
public class CacheEvictConsumer {

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQPushConsumer cacheEvictMessageConsumer(MqConsumerFactory factory,
                                                    MultiLevelCacheService multiLevelCacheService) {
        return factory.create(MqConstants.GROUP_CACHE_EVICT, MqConstants.TOPIC_CACHE_EVICT, true,
                (List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) -> {
                    for (MessageExt msg : msgs) {
                        try {
                            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
                            Map<String, Object> payload = JsonUtils.fromJson(body,
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                                    });
                            String cacheKey = payload == null ? null : (String) payload.get("cacheKey");
                            multiLevelCacheService.evictLocal(cacheKey == null ? "" : cacheKey);
                            log.info("[broadcast] 本地缓存失效 cacheKey={} msgId={}", cacheKey, msg.getMsgId());
                        } catch (Exception e) {
                            // 广播模式失败不重投：记录日志，依赖本地 TTL 兜底
                            log.error("[broadcast] 缓存失效消息处理失败 msgId={}", msg.getMsgId(), e);
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                });
    }
}
