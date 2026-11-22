package com.campusflow.biz.waitlist.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 候补队列 DO（Redis ZSet 为实时队列，此表为持久化/审计依据）。
 */
@Data
@TableName("waitlist")
public class WaitlistDO {

    public static final int STATUS_WAITING = 0;
    public static final int STATUS_PROMOTED = 1;
    public static final int STATUS_CONFIRMED = 2;
    public static final int STATUS_EXPIRED = 3;
    public static final int STATUS_CANCELLED = 4;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long activityId;

    private Long userId;

    private Integer queueNo;

    private Integer status;

    private LocalDateTime joinTime;

    private LocalDateTime promotedAt;

    private LocalDateTime confirmDeadline;

    private LocalDateTime confirmedAt;

    private LocalDateTime closedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
