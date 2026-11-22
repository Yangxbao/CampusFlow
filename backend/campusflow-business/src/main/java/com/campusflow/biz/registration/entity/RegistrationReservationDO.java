package com.campusflow.biz.registration.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报名预留记录 DO（待处理预留，可靠消息与补偿的持久化锚点）。
 * 生命周期：Lua 预留成功 -> insert PENDING_CONFIRM -> 消费成功 CONFIRMED / 超时补偿 RELEASED。
 */
@Data
@TableName("registration_reservation")
public class RegistrationReservationDO {

    public static final int STATUS_PENDING_CONFIRM = 0;
    public static final int STATUS_CONFIRMED = 1;
    public static final int STATUS_COMPENSATING = 2;
    public static final int STATUS_RELEASED = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long activityId;

    private Long userId;

    private Integer status;

    private String messageKey;

    private LocalDateTime reservedAt;

    private LocalDateTime expireAt;

    private LocalDateTime confirmedAt;

    private LocalDateTime releasedAt;

    private Integer retryCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
