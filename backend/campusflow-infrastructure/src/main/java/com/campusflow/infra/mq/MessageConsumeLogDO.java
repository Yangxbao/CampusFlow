package com.campusflow.infra.mq;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息消费幂等记录 DO。uk_msg_group 唯一约束 = 重复投递最终防线。
 */
@Data
@TableName("message_consume_log")
public class MessageConsumeLogDO {

    public static final int STATUS_PROCESSING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String msgKey;

    private String consumerGroup;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
