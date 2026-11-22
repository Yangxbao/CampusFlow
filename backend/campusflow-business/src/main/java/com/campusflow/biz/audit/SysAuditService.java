package com.campusflow.biz.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusflow.biz.audit.entity.SysAuditLogDO;
import com.campusflow.biz.audit.mapper.SysAuditLogMapper;
import com.campusflow.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 系统操作审计服务：异步落库（notifyExecutor，有界队列 CallerRuns 不丢审计）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysAuditService {

    private final SysAuditLogMapper sysAuditLogMapper;
    private final ThreadPoolExecutor notifyExecutor;

    public void recordAsync(SysAuditLogDO auditLog) {
        notifyExecutor.execute(() -> {
            try {
                sysAuditLogMapper.insert(auditLog);
            } catch (Exception e) {
                // 审计失败不能影响主业务，只记录错误日志
                log.error("操作审计落库失败 action={}", auditLog.getAction(), e);
            }
        });
    }

    public PageResult<SysAuditLogDO> pageLogs(int pageNum, int pageSize, Long userId, String action) {
        Page<SysAuditLogDO> page = sysAuditLogMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<SysAuditLogDO>()
                        .eq(userId != null, SysAuditLogDO::getUserId, userId)
                        .eq(action != null && !action.isEmpty(), SysAuditLogDO::getAction, action)
                        .orderByDesc(SysAuditLogDO::getCreatedAt));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
