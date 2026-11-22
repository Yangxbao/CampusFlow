package com.campusflow.biz.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusflow.biz.audit.entity.SysAuditLogDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysAuditLogMapper extends BaseMapper<SysAuditLogDO> {
}
