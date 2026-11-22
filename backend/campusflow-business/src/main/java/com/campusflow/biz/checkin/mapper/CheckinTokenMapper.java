package com.campusflow.biz.checkin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusflow.biz.checkin.entity.CheckinTokenDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CheckinTokenMapper extends BaseMapper<CheckinTokenDO> {
}
