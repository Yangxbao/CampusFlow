package com.campusflow.biz.compensation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时补偿任务 DO。
 */
@Data
@TableName("compensation_task")
public class CompensationTaskDO {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_RETRYABLE_FAILED = 3;
    public static final int STATUS_DEAD = 4;

    /** 报名确认消息补偿 */
    public static final String TYPE_REGISTRATION_CONFIRM = "REGISTRATION_CONFIRM";
    /** 库存对账漂移告警 */
    public static final String TYPE_STOCK_RECONCILE_ALERT = "STOCK_RECONCILE_ALERT";
    /** 手工介入标记 */
    public static final String TYPE_MANUAL_INTERVENTION = "MANUAL_INTERVENTION";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String bizType;

    private String bizKey;

    private String payload;

    private Integer status;

    private Integer retryCount;

    private Integer maxRetry;

    private LocalDateTime nextRetryTime;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
