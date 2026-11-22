package com.campusflow.biz.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.stats.entity.ExportTaskDO;
import com.campusflow.biz.stats.mapper.ExportTaskMapper;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.biz.user.mapper.UserMapper;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 报名数据 CSV 异步导出服务。
 * <p>使用有界 exportExecutor（core2/max4/queue50/Abort）执行重任务：
 * - 拒绝时 AbortPolicy 抛 RejectedExecutionException -> 明确告知"导出任务过多"；
 * - 导出结果落 export_task，前端轮询任务状态后下载文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ExportTaskMapper exportTaskMapper;
    private final RegistrationMapper registrationMapper;
    private final UserMapper userMapper;
    private final ActivityMapper activityMapper;
    private final ThreadPoolExecutor exportExecutor;

    @Value("${campusflow.export.dir:./data/exports}")
    private String exportDir;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Paths.get(exportDir));
    }

    /**
     * 发起异步导出：创建任务记录 -> 提交线程池。
     */
    public ExportTaskDO submitRegistrationsExport(long activityId, long operatorId) {
        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        if (!activity.getOrganizerId().equals(operatorId)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        ExportTaskDO task = new ExportTaskDO();
        task.setUserId(operatorId);
        task.setActivityId(activityId);
        task.setTaskType("REGISTRATIONS");
        task.setStatus(ExportTaskDO.STATUS_PENDING);
        exportTaskMapper.insert(task);
        try {
            exportExecutor.execute(() -> runExport(task.getId(), activityId));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            task.setStatus(ExportTaskDO.STATUS_FAILED);
            task.setError("导出任务过多，请稍后重试");
            exportTaskMapper.updateById(task);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "导出任务过多，请稍后重试");
        }
        return task;
    }

    private void runExport(long taskId, long activityId) {
        ExportTaskDO task = exportTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(ExportTaskDO.STATUS_RUNNING);
        exportTaskMapper.updateById(task);
        try {
            Path file = Paths.get(exportDir, "registrations-" + activityId + "-" + taskId + ".csv");
            List<RegistrationDO> registrations = registrationMapper.selectList(
                    new LambdaQueryWrapper<RegistrationDO>()
                            .eq(RegistrationDO::getActivityId, activityId)
                            .orderByAsc(RegistrationDO::getRegisteredAt));
            ActivityDO activity = activityMapper.selectById(activityId);
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                 CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT
                         .withHeader("报名ID", "活动ID", "活动标题", "用户名", "昵称", "报名状态",
                                 "报名时间", "签到时间", "取消时间"))) {
                for (RegistrationDO reg : registrations) {
                    UserDO user = userMapper.selectById(reg.getUserId());
                    printer.printRecord(
                            reg.getId(),
                            activityId,
                            activity == null ? "" : activity.getTitle(),
                            user == null ? "" : user.getUsername(),
                            user == null ? "" : user.getNickname(),
                            RegistrationStatus.ofCode(reg.getStatus()).getDesc(),
                            reg.getRegisteredAt() == null ? "" :
                                    reg.getRegisteredAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                            reg.getCheckinAt() == null ? "" :
                                    reg.getCheckinAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                            reg.getCancelledAt() == null ? "" :
                                    reg.getCancelledAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                }
            }
            task.setStatus(ExportTaskDO.STATUS_SUCCESS);
            task.setFilePath(file.toString());
            task.setRowCount(registrations.size());
            exportTaskMapper.updateById(task);
            log.info("导出完成 taskId={} rows={} file={}", taskId, registrations.size(), file);
        } catch (IOException e) {
            task.setStatus(ExportTaskDO.STATUS_FAILED);
            task.setError("IO 异常: " + e.getMessage());
            exportTaskMapper.updateById(task);
            log.error("导出失败 taskId={}", taskId, e);
        }
    }

    public ExportTaskDO getTask(long taskId, long userId) {
        ExportTaskDO task = exportTaskMapper.selectById(taskId);
        if (task == null || !task.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.EXPORT_TASK_NOT_EXIST);
        }
        return task;
    }

    public Path getTaskFile(long taskId, long userId) {
        ExportTaskDO task = getTask(taskId, userId);
        if (task.getStatus() != ExportTaskDO.STATUS_SUCCESS || task.getFilePath() == null) {
            throw BizException.of(ErrorCode.EXPORT_TASK_NOT_EXIST, "导出尚未完成或已失败");
        }
        return Paths.get(task.getFilePath());
    }
}
