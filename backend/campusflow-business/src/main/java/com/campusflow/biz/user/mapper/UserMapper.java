package com.campusflow.biz.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusflow.biz.user.entity.UserDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
}
