package com.campusflow.infra.mq;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 本地消息表 Mapper。
 */
@Mapper
public interface MessageDeliverMapper extends BaseMapper<MessageDeliverDO> {

    /** 标记发送成功（CAS 语义，防止并发扫描与同步发送竞争时重复更新） */
    @Update("UPDATE message_deliver SET status = 1, sent_at = #{sentAt}, retry_count = retry_count, " +
            "last_error = NULL WHERE msg_key = #{msgKey} AND status IN (0, 3)")
    int markSent(@Param("msgKey") String msgKey, @Param("sentAt") LocalDateTime sentAt);

    /** 标记消费确认（消费方回调，闭环追踪） */
    @Update("UPDATE message_deliver SET status = 2, confirmed_at = #{confirmedAt} " +
            "WHERE msg_key = #{msgKey} AND status = 1")
    int markConfirmed(@Param("msgKey") String msgKey, @Param("confirmedAt") LocalDateTime confirmedAt);

    /** 标记发送失败并推进重试计划（指数退避在调用方计算） */
    @Update("UPDATE message_deliver SET status = 3, retry_count = retry_count + 1, " +
            "next_retry_time = #{nextRetryTime}, last_error = #{lastError} " +
            "WHERE msg_key = #{msgKey}")
    int markFailed(@Param("msgKey") String msgKey,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime,
                   @Param("lastError") String lastError);
}
