package com.campusflow.biz.audit;

import com.campusflow.biz.audit.entity.StockAuditLogDO;
import com.campusflow.biz.audit.mapper.StockAuditLogMapper;
import com.campusflow.infra.redis.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 库存变更审计服务。
 * <p>幂等核心：bizKey 唯一约束。同一业务动作（一次预留/一次取消/一次补偿）
 * 无论被并发执行还是补偿重放，只会落一条审计记录 —— 这也是
 * "库存恢复必须幂等，不能重复加回"的最终防线（Redis Lua 幂等之外的持久化凭证）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAuditService {

    private final StockAuditLogMapper stockAuditLogMapper;
    private final StockRedisService stockRedisService;

    /**
     * 记录库存变更。bizKey 重复（幂等重放）时静默跳过并返回 false。
     */
    public boolean logStockChange(long activityId, int delta, String source, String bizKey,
                                  Long operatorId, String remark) {
        StockAuditLogDO audit = new StockAuditLogDO();
        audit.setActivityId(activityId);
        audit.setDelta(delta);
        audit.setAfterStock(stockRedisService.getStock(activityId) == null
                ? null : stockRedisService.getStock(activityId).intValue());
        audit.setSource(source);
        audit.setBizKey(bizKey);
        audit.setOperatorId(operatorId);
        audit.setRemark(remark);
        try {
            stockAuditLogMapper.insert(audit);
            log.info("库存审计 activityId={} delta={} source={} bizKey={}", activityId, delta, source, bizKey);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("库存审计幂等跳过 bizKey={}（该业务动作已记账）", bizKey);
            return false;
        }
    }
}
