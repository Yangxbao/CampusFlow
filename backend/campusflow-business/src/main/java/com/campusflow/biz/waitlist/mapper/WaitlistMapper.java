package com.campusflow.biz.waitlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusflow.biz.waitlist.entity.WaitlistDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WaitlistMapper extends BaseMapper<WaitlistDO> {

    /** 晋级（CAS：仅 WAITING 可晋级，防并发重复晋级） */
    @Update("UPDATE waitlist SET status = 1, promoted_at = NOW(), confirm_deadline = #{deadline} " +
            "WHERE id = #{id} AND status = 0")
    int markPromoted(@Param("id") Long id, @Param("deadline") java.time.LocalDateTime deadline);

    /** 转正（报名确认时联动） */
    @Update("UPDATE waitlist SET status = 2, confirmed_at = NOW() WHERE id = #{id} AND status = 1")
    int markConfirmed(@Param("id") Long id);

    /** 晋级超时关闭 */
    @Update("UPDATE waitlist SET status = 3, closed_at = NOW() WHERE id = #{id} AND status = 1")
    int markExpired(@Param("id") Long id);

    /** 主动取消 */
    @Update("UPDATE waitlist SET status = 4, closed_at = NOW() " +
            "WHERE id = #{id} AND status IN (0, 1)")
    int markCancelled(@Param("id") Long id);
}
