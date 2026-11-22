package com.campusflow.biz.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusflow.biz.notify.entity.NotifyMessageDO;
import com.campusflow.biz.notify.mapper.NotifyMessageMapper;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知服务：列表/未读数/标记已读。
 * 通知写入统一经 MQ 消费端（campusflow-notify-topic），保证高频业务事务不被通知拖慢。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    private final NotifyMessageMapper notifyMessageMapper;

    public PageResult<NotifyMessageDO> pageMyNotifications(long userId,
                                                           com.campusflow.common.page.PageQuery pageQuery,
                                                           Boolean unreadOnly) {
        Page<NotifyMessageDO> page = notifyMessageMapper.selectPage(
                new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize()),
                new LambdaQueryWrapper<NotifyMessageDO>()
                        .eq(NotifyMessageDO::getUserId, userId)
                        .eq(Boolean.TRUE.equals(unreadOnly), NotifyMessageDO::getIsRead, 0)
                        .orderByDesc(NotifyMessageDO::getCreatedAt));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public long unreadCount(long userId) {
        Long count = notifyMessageMapper.selectCount(new LambdaQueryWrapper<NotifyMessageDO>()
                .eq(NotifyMessageDO::getUserId, userId)
                .eq(NotifyMessageDO::getIsRead, 0));
        return count == null ? 0 : count;
    }

    public void markRead(long userId, Long notificationId) {
        NotifyMessageDO notify = notifyMessageMapper.selectById(notificationId);
        if (notify == null || !notify.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (notify.getIsRead() == 0) {
            notify.setIsRead(1);
            notify.setReadAt(LocalDateTime.now());
            notifyMessageMapper.updateById(notify);
        }
    }

    public void markAllRead(long userId) {
        NotifyMessageDO template = new NotifyMessageDO();
        template.setIsRead(1);
        template.setReadAt(LocalDateTime.now());
        notifyMessageMapper.update(template, new LambdaQueryWrapper<NotifyMessageDO>()
                .eq(NotifyMessageDO::getUserId, userId)
                .eq(NotifyMessageDO::getIsRead, 0));
    }
}
