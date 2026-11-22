package com.campusflow.biz.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActivityMapper extends BaseMapper<ActivityDO> {

    /** 报名确认人数 +1（原子自增，避免读改写覆盖） */
    @Update("UPDATE activity SET confirmed_count = confirmed_count + 1 WHERE id = #{activityId}")
    int incrementConfirmedCount(@Param("activityId") Long activityId);

    /** 签到人数 +1 */
    @Update("UPDATE activity SET checked_in_count = checked_in_count + 1 WHERE id = #{activityId}")
    int incrementCheckedInCount(@Param("activityId") Long activityId);
}
