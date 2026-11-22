package com.campusflow.infra.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.common.constant.RedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 消费幂等服务。
 * <p>双层防线：
 * 1. Redis 快速幂等（处理成功后写入 processed 标记，TTL 24h）——命中直接跳过，省一次 DB 查询；
 * 2. DB 唯一约束 uk_msg_group —— Redis 数据丢失（重启/淘汰）后的最终防线。
 * <p>失败重试语义：markFailed 后允许再次投递重试（幂等记录状态回到可处理），
 * 成功后 status=1 永久拦截重复消息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageConsumeLogService {

    private final MessageConsumeLogMapper consumeLogMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 消费开始前登记。返回 false 表示该消息已成功处理过或正在被并发处理（跳过）。
     * <p>三层防线：
     * 1. Redis 认领锁（SETNX，60s TTL）：同一 msgKey 的并发重复消息只有一个线程进入处理
     *    （Broker 对"业务键相同的两条独立消息"不做去重，并发投递时 DB 唯一约束存在检查-插入竞态窗口）；
     * 2. Redis processed 标记（成功后写入，24h）：已处理消息快速跳过；
     * 3. DB 唯一约束 uk_msg_group：Redis 数据丢失后的最终防线。
     * 认领锁 60s 过期保证：处理崩溃后 Broker 重投（重投间隔递增）仍能在锁过期后重试。
     */
    public boolean tryBeginConsume(String msgKey, String consumerGroup) {
        // L1a: 并发认领锁（互斥进入处理）
        String claimKey = RedisKeyConstants.msgIdempotent("claim:" + msgKey + ":" + consumerGroup);
        Boolean claimed = stringRedisTemplate.opsForValue().setIfAbsent(claimKey, "1", java.time.Duration.ofSeconds(60));
        if (!Boolean.TRUE.equals(claimed)) {
            log.info("消费认领锁命中（并发重复消息） msgKey={} group={}", msgKey, consumerGroup);
            return false;
        }
        // L1b: processed 标记
        Boolean processed = stringRedisTemplate.hasKey(
                RedisKeyConstants.msgIdempotent(msgKey + ":" + consumerGroup));
        if (Boolean.TRUE.equals(processed)) {
            log.info("消费幂等命中(Redis) msgKey={} group={}", msgKey, consumerGroup);
            return false;
        }
        // L2: DB 唯一约束
        MessageConsumeLogDO existing = consumeLogMapper.selectOne(
                new LambdaQueryWrapper<MessageConsumeLogDO>()
                        .eq(MessageConsumeLogDO::getMsgKey, msgKey)
                        .eq(MessageConsumeLogDO::getConsumerGroup, consumerGroup));
        if (existing == null) {
            MessageConsumeLogDO row = new MessageConsumeLogDO();
            row.setMsgKey(msgKey);
            row.setConsumerGroup(consumerGroup);
            row.setStatus(MessageConsumeLogDO.STATUS_PROCESSING);
            try {
                consumeLogMapper.insert(row);
                return true;
            } catch (DuplicateKeyException e) {
                // 并发重复投递：另一线程先插入
                existing = consumeLogMapper.selectOne(new LambdaQueryWrapper<MessageConsumeLogDO>()
                        .eq(MessageConsumeLogDO::getMsgKey, msgKey)
                        .eq(MessageConsumeLogDO::getConsumerGroup, consumerGroup));
            }
        }
        // 已成功处理 -> 重复消息；失败/处理中 -> 允许本次重试
        return existing == null || existing.getStatus() != MessageConsumeLogDO.STATUS_SUCCESS;
    }

    public void markSuccess(String msgKey, String consumerGroup) {
        consumeLogMapper.markSuccess(msgKey, consumerGroup);
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstants.msgIdempotent(msgKey + ":" + consumerGroup),
                "1", Duration.ofHours(24));
    }

    public void markFailed(String msgKey, String consumerGroup) {
        consumeLogMapper.markFailed(msgKey, consumerGroup);
    }
}
