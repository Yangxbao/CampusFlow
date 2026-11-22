package com.campusflow.biz.stats.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导出任务 DO（异步 CSV 导出）。
 */
@Data
@TableName("export_task")
public class ExportTaskDO {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_SUCCESS = 2;
    public static final int STATUS_FAILED = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long activityId;

    private String taskType;

    private Integer status;

    private String filePath;

    private Integer rowCount;

    private String error;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
