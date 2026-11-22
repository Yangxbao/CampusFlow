package com.campusflow.biz.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusflow.biz.audit.entity.StockAuditLogDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockAuditLogMapper extends BaseMapper<StockAuditLogDO> {
}
