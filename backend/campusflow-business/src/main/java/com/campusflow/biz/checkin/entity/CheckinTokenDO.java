package com.campusflow.biz.checkin.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 签到凭证 DO。token 明文只在签发响应中出现一次，数据库只存 SHA-256 摘要（防伪造+防泄漏）。
 */
@Data
@TableName("checkin_token")
public class CheckinTokenDO {

    public static final int STATUS_VALID = 0;
    public static final int STATUS_USED = 1;
    public static final int STATUS_EXPIRED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long registrationId;

    private Long activityId;

    private Long userId;

    private String tokenHash;

    private Integer status;

    private LocalDateTime issuedAt;

    private LocalDateTime expireAt;

    private LocalDateTime usedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
