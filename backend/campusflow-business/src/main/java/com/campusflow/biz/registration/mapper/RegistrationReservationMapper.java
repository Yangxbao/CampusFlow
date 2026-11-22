package com.campusflow.biz.registration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusflow.biz.registration.entity.RegistrationReservationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RegistrationReservationMapper extends BaseMapper<RegistrationReservationDO> {

    /** 预留确认（CAS：仅 PENDING_CONFIRM 可推进） */
    @Update("UPDATE registration_reservation SET status = 1, confirmed_at = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int markConfirmed(@Param("id") Long id);

    /** 预留标记补偿中 */
    @Update("UPDATE registration_reservation SET status = 2, retry_count = retry_count + 1 " +
            "WHERE id = #{id} AND status = 0")
    int markCompensating(@Param("id") Long id);

    /** 预留释放（幂等：仅补偿中/待确认可释放） */
    @Update("UPDATE registration_reservation SET status = 3, released_at = NOW() " +
            "WHERE id = #{id} AND status IN (0, 2)")
    int markReleased(@Param("id") Long id);

    /** 消费重试成功推进（补偿扫描重发消息成功后不推进状态，由消费端确认） */
    @Update("UPDATE registration_reservation SET retry_count = retry_count + 1 " +
            "WHERE id = #{id} AND status = 0")
    int incrementRetry(@Param("id") Long id);
}
