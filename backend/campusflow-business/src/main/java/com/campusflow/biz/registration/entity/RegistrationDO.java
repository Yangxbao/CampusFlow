package com.campusflow.biz.registration.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报名 DO。uk_activity_user 唯一约束 = 一人一单最终防线；
 * 取消后重新报名复用同一行（状态回迁），保证唯一索引与业务规则不冲突。
 */
@Data
@TableName("registration")
public class RegistrationDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long activityId;

    private Long userId;

    private Integer status;

    private Long reservationId;

    private LocalDateTime registeredAt;

    private LocalDateTime cancelledAt;

    private LocalDateTime checkinAt;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
