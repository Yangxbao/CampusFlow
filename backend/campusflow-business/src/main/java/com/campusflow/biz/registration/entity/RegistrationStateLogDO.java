package com.campusflow.biz.registration.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报名状态变更记录 DO（状态机审计轨迹）。
 */
@Data
@TableName("registration_state_log")
public class RegistrationStateLogDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long registrationId;

    private Long activityId;

    private Long userId;

    private Integer fromStatus;

    private Integer toStatus;

    private String reason;

    private Long operatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
