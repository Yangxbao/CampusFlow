package com.campusflow.infra.mq;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 消费幂等记录 Mapper。
 */
@Mapper
public interface MessageConsumeLogMapper extends BaseMapper<MessageConsumeLogDO> {

    @Update("UPDATE message_consume_log SET status = 1 WHERE msg_key = #{msgKey} " +
            "AND consumer_group = #{group} AND status = 0")
    int markSuccess(@Param("msgKey") String msgKey, @Param("group") String group);

    @Update("UPDATE message_consume_log SET status = 2 WHERE msg_key = #{msgKey} " +
            "AND consumer_group = #{group} AND status = 0")
    int markFailed(@Param("msgKey") String msgKey, @Param("group") String group);
}
