package com.campusflow.infra.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.common.util.JsonUtils;
import com.campusflow.common.util.SnowflakeIdWorker;
import com.campusflow.infra.property.CampusflowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 可靠消息服务：本地消息表（事务性发件箱）+ 同步发送确认 + 指数退避重试。
 * <p>为什么不用"事务消息"替代本地消息表：
 * <ul>
 *   <li>事务消息适用场景：业务侧只关心"DB 提交与消息发送的原子性"，且能接受半消息反查机制；</li>
 *   <li>本地消息表适用场景：需要持久化投递轨迹（对账/审计/管理端可视化消息状态），
 *       并希望重试策略完全自主可控；本项目两个都要，主链路用本地消息表，
 *       事务消息在集成测试中做实验对比（见测试报告）。</li>
 * </ul>
 * <p>时序：业务 TX 内 insert message_deliver(status=NEW) -> TX 提交后 sendDeliver() ->
 * 成功置 SENT；失败置 FAILED + 指数退避 next_retry_time，扫描任务重投。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageDeliverService {

    private final DefaultMQProducer producer;
    private final MessageDeliverMapper deliverMapper;
    private final CampusflowProperties properties;
    private final SnowflakeIdWorker snowflakeIdWorker;

    /**
     * 在业务事务内创建投递记录（本方法无 @Transactional，加入调用方事务）。
     */
    public MessageDeliverDO createDeliverRecord(String bizKeyPrefix, String topic, String tag,
                                                String bizType, Object payload) {
        MessageDeliverDO record = new MessageDeliverDO();
        record.setMsgKey(bizKeyPrefix + "-" + snowflakeIdWorker.nextId());
        record.setTopic(topic);
        record.setTag(tag);
        record.setBizType(bizType);
        record.setBody(JsonUtils.toJson(payload));
        record.setStatus(MessageDeliverDO.STATUS_NEW);
        record.setRetryCount(0);
        record.setNextRetryTime(LocalDateTime.now());
        deliverMapper.insert(record);
        log.info("本地消息表创建 msgKey={} topic={} bizType={}", record.getMsgKey(), topic, bizType);
        return record;
    }

    /**
     * 显式业务键版本：调用方需要用确定的 msgKey 事后检索该记录（预留链路：msgKey=预留 messageKey）。
     */
    public MessageDeliverDO createDeliverRecordWithKey(String msgKey, String topic, String tag,
                                                       String bizType, Object payload) {
        MessageDeliverDO record = new MessageDeliverDO();
        record.setMsgKey(msgKey);
        record.setTopic(topic);
        record.setTag(tag);
        record.setBizType(bizType);
        record.setBody(JsonUtils.toJson(payload));
        record.setStatus(MessageDeliverDO.STATUS_NEW);
        record.setRetryCount(0);
        record.setNextRetryTime(LocalDateTime.now());
        deliverMapper.insert(record);
        log.info("本地消息表创建(显式键) msgKey={} topic={} bizType={}", msgKey, topic, bizType);
        return record;
    }

    /**
     * 立即同步发送指定投递记录（事务提交后调用；失败不抛给业务——交给重试扫描）。
     *
     * @return 是否发送成功
     */
    public boolean trySendDeliver(MessageDeliverDO record) {
        try {
            Message msg = new Message(record.getTopic(), record.getTag(),
                    record.getMsgKey(), record.getBody().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SendResult result = producer.send(msg);
            if (result != null && result.getSendStatus() == SendStatus.SEND_OK) {
                deliverMapper.markSent(record.getMsgKey(), LocalDateTime.now());
                log.info("消息发送成功 msgKey={} topic={} msgId={}",
                        record.getMsgKey(), record.getTopic(), result.getMsgId());
                return true;
            }
            // 非 SEND_OK（FLUSH_SLAVE_TIMEOUT 等）：按失败处理进入重试
            handleSendFail(record, "sendStatus=" + (result == null ? "null" : result.getSendStatus()));
            return false;
        } catch (Exception e) {
            handleSendFail(record, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 按业务键创建并立即尝试发送（非事务路径，用于纯通知类消息）。
     */
    public String createAndSend(String bizKeyPrefix, String topic, String tag,
                                String bizType, Object payload) {
        MessageDeliverDO record = createDeliverRecord(bizKeyPrefix, topic, tag, bizType, payload);
        trySendDeliver(record);
        return record.getMsgKey();
    }

    /**
     * 待投递/待重试消息列表（补偿扫描用）。
     */
    public List<MessageDeliverDO> listRetryDue(LocalDateTime now, int limit) {
        return deliverMapper.selectList(new LambdaQueryWrapper<MessageDeliverDO>()
                .in(MessageDeliverDO::getStatus,
                        MessageDeliverDO.STATUS_NEW, MessageDeliverDO.STATUS_FAILED)
                .le(MessageDeliverDO::getNextRetryTime, now)
                .lt(MessageDeliverDO::getRetryCount, properties.getMq().getMessageMaxRetry())
                .orderByAsc(MessageDeliverDO::getNextRetryTime)
                .last("LIMIT " + limit));
    }

    /** 发送失败处理：标记 + 指数退避 */
    private void handleSendFail(MessageDeliverDO record, String error) {
        int retry = record.getRetryCount() == null ? 0 : record.getRetryCount();
        long backoff = (long) properties.getMq().getMessageRetryBaseSeconds() * (1L << Math.min(retry, 6));
        LocalDateTime next = LocalDateTime.now().plusSeconds(backoff);
        deliverMapper.markFailed(record.getMsgKey(), next, truncate(error));
        log.warn("消息发送失败 msgKey={} retry={} nextRetry={} error={}",
                record.getMsgKey(), retry + 1, next, truncate(error));
    }

    /**
     * 消费方确认回调（闭环：本地消息表状态 -> CONFIRMED）。
     */
    public void confirmConsumed(String msgKey) {
        deliverMapper.markConfirmed(msgKey, LocalDateTime.now());
    }

    /**
     * 按业务键查询投递记录（补偿恢复用）。
     */
    public MessageDeliverDO getByMsgKey(String msgKey) {
        return deliverMapper.selectOne(new LambdaQueryWrapper<MessageDeliverDO>()
                .eq(MessageDeliverDO::getMsgKey, msgKey)
                .last("LIMIT 1"));
    }

    private String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 250));
    }
}
