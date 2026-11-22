package com.campusflow.biz.audit.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存变更审计 DO。uk_biz_key 唯一 = 库存操作幂等防线（补偿重复执行不会重复记账）。
 */
@Data
@TableName("stock_audit_log")
public class StockAuditLogDO {

    public static final String SOURCE_RESERVE = "RESERVE";
    public static final String SOURCE_CANCEL = "CANCEL";
    public static final String SOURCE_WAITLIST_SERVE = "WAITLIST_SERVE";
    public static final String SOURCE_WAITLIST_TIMEOUT = "WAITLIST_TIMEOUT";
    public static final String SOURCE_COMPENSATE = "COMPENSATE";
    public static final String SOURCE_RECONCILE = "RECONCILE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long activityId;

    private Integer delta;

    private Integer afterStock;

    private String source;

    private String bizKey;

    private Long operatorId;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
