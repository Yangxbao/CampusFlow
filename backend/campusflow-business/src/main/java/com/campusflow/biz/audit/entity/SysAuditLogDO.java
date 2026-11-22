package com.campusflow.biz.audit.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统操作审计 DO。
 */
@Data
@TableName("sys_audit_log")
public class SysAuditLogDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String username;

    private String action;

    private String resource;

    private String resourceId;

    private String detail;

    private String ip;

    private String traceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
