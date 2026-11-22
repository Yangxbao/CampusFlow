package com.campusflow.biz.compensation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusflow.biz.compensation.entity.CompensationTaskDO;
import com.campusflow.biz.compensation.mapper.CompensationTaskMapper;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.registration.service.RegistrationTxService;
import com.campusflow.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 补偿任务服务：创建（幂等）+ 扫描执行 + 重试管理。
 * <p>幂等：uk_biz_key 唯一约束，同一业务异常只创建一条任务；
 * 重复执行安全：任务处理逻辑全部依赖底层幂等（DB 状态 CAS / Lua / 唯一约束）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationTaskService {

    private final CompensationTaskMapper taskMapper;
    private final RegistrationTxService registrationTxService;

    /**
     * 创建补偿任务（bizKey 幂等）。
     */
    public boolean createTask(String bizType, String bizKey, String payload) {
        CompensationTaskDO task = new CompensationTaskDO();
        task.setBizType(bizType);
        task.setBizKey(bizKey);
        task.setPayload(payload);
        task.setStatus(CompensationTaskDO.STATUS_PENDING);
        task.setRetryCount(0);
        task.setMaxRetry(5);
        task.setNextRetryTime(LocalDateTime.now());
        try {
            taskMapper.insert(task);
            log.warn("创建补偿任务 type={} bizKey={}", bizType, bizKey);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("补偿任务已存在（幂等跳过） bizKey={}", bizKey);
            return false;
        }
    }

    /**
     * 扫描到期任务并执行。
     *
     * @return 本次处理数量
     */
    public int processDueTasks(int limit) {
        List<CompensationTaskDO> tasks = taskMapper.selectList(new LambdaQueryWrapper<CompensationTaskDO>()
                .in(CompensationTaskDO::getStatus,
                        CompensationTaskDO.STATUS_PENDING, CompensationTaskDO.STATUS_RETRYABLE_FAILED)
                .le(CompensationTaskDO::getNextRetryTime, LocalDateTime.now())
                .orderByAsc(CompensationTaskDO::getNextRetryTime)
                .last("LIMIT " + limit));
        int processed = 0;
        for (CompensationTaskDO task : tasks) {
            if (executeTask(task)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean executeTask(CompensationTaskDO task) {
        try {
            switch (task.getBizType()) {
                case CompensationTaskDO.TYPE_REGISTRATION_CONFIRM -> {
                    MqPayloads.RegistrationConfirmMessage payload = JsonUtils.fromJson(
                            task.getPayload(), MqPayloads.RegistrationConfirmMessage.class);
                    if (payload == null) {
                        throw new IllegalStateException("补偿任务载荷解析失败: " + task.getBizKey());
                    }
                    registrationTxService.confirmRegistration(payload);
                }
                case CompensationTaskDO.TYPE_STOCK_RECONCILE_ALERT,
                     CompensationTaskDO.TYPE_MANUAL_INTERVENTION -> {
                    // 仅告警类：由管理员在后台查看并人工处理（重新入队即人工认领）
                    log.warn("补偿任务为告警类型，等待人工处理 bizKey={} payload={}", task.getBizKey(), task.getPayload());
                    task.setStatus(CompensationTaskDO.STATUS_RETRYABLE_FAILED);
                    task.setNextRetryTime(LocalDateTime.now().plusHours(1));
                    taskMapper.updateById(task);
                    return true;
                }
                default -> throw new IllegalStateException("未知补偿任务类型: " + task.getBizType());
            }
            task.setStatus(CompensationTaskDO.STATUS_SUCCESS);
            taskMapper.updateById(task);
            log.info("补偿任务执行成功 bizKey={} type={}", task.getBizKey(), task.getBizType());
            return true;
        } catch (Exception e) {
            int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
            if (retry >= task.getMaxRetry()) {
                task.setStatus(CompensationTaskDO.STATUS_DEAD);
                task.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                taskMapper.updateById(task);
                log.error("补偿任务进入死信（超过最大重试） bizKey={} retry={}", task.getBizKey(), retry, e);
            } else {
                // 指数退避：30s * 2^n，上限 1 小时
                long backoffSeconds = Math.min(30L * (1L << Math.min(retry, 7)), 3600);
                task.setStatus(CompensationTaskDO.STATUS_RETRYABLE_FAILED);
                task.setRetryCount(retry);
                task.setNextRetryTime(LocalDateTime.now().plusSeconds(backoffSeconds));
                task.setLastError(e.getClass().getSimpleName() + ": " + e.getMessage());
                taskMapper.updateById(task);
                log.warn("补偿任务重试 bizKey={} retry={} next={} error={}",
                        task.getBizKey(), retry, task.getNextRetryTime(), e.getMessage());
            }
            return false;
        }
    }

    public com.campusflow.common.result.PageResult<CompensationTaskDO> pageTasks(int pageNum, int pageSize, String bizType, Integer status) {
        Page<CompensationTaskDO> page = taskMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<CompensationTaskDO>()
                        .eq(bizType != null && !bizType.isEmpty(), CompensationTaskDO::getBizType, bizType)
                        .eq(status != null, CompensationTaskDO::getStatus, status)
                        .orderByDesc(CompensationTaskDO::getCreatedAt));
        return new com.campusflow.common.result.PageResult<>(page.getRecords(), page.getTotal(),
                page.getCurrent(), page.getSize());
    }

    /**
     * 管理员重新入队（死信任务人工恢复）。
     */
    public void requeue(Long taskId) {
        CompensationTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw com.campusflow.common.exception.BizException.of(
                    com.campusflow.common.error.ErrorCode.COMPENSATION_TASK_NOT_EXIST);
        }
        task.setStatus(CompensationTaskDO.STATUS_PENDING);
        task.setRetryCount(0);
        task.setNextRetryTime(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("补偿任务重新入队 taskId={} bizKey={}", taskId, task.getBizKey());
    }
}
