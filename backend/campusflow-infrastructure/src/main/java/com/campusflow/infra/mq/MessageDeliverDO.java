package com.campusflow.infra.mq;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表 DO（事务性发件箱模式）。
 */
@Data
@TableName("message_deliver")
public class MessageDeliverDO {

    public static final int STATUS_NEW = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_CONFIRMED = 2;
    public static final int STATUS_FAILED = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String msgKey;

    private String topic;

    private String tag;

    private String bizType;

    private String body;

    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private LocalDateTime sentAt;

    private LocalDateTime confirmedAt;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
