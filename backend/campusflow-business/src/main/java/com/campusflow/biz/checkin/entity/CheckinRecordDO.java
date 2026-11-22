package com.campusflow.biz.checkin.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 签到记录 DO（uk_registration 唯一：一凭证一次成功核销）。
 */
@Data
@TableName("checkin_record")
public class CheckinRecordDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long activityId;

    private Long registrationId;

    private Long userId;

    private Long operatorId;

    private String tokenHash;

    private LocalDateTime checkinAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
