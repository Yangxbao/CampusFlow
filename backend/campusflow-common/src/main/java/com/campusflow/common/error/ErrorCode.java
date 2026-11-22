package com.campusflow.common.error;

import lombok.Getter;

/**
 * 全局错误码清单。
 * <p>分段规则：0 成功；2xxxx 业务错误；3xxxx 基础设施错误；4xxxx 通用 HTTP 语义错误。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "success"),

    // ===== 通用 =====
    PARAM_ERROR(40001, "参数校验失败"),
    UNAUTHORIZED(40101, "未登录或登录已过期"),
    FORBIDDEN(40301, "无权限执行该操作"),
    NOT_FOUND(40401, "资源不存在"),
    METHOD_NOT_ALLOWED(40501, "请求方式不支持"),
    CONCURRENT_REQUEST(42901, "请求正在处理中，请勿重复提交"),
    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(50301, "依赖服务暂不可用，请稍后重试"),

    // ===== 用户模块 21xxx =====
    USER_EXISTS(21001, "用户名已存在"),
    USER_NOT_EXIST(21002, "用户不存在"),
    PASSWORD_ERROR(21003, "用户名或密码错误"),
    USER_DISABLED(21004, "账号已被禁用，请联系管理员"),
    OLD_PASSWORD_ERROR(21005, "原密码错误"),

    // ===== 活动模块 22xxx =====
    ACTIVITY_NOT_EXIST(22001, "活动不存在"),
    ACTIVITY_NOT_PUBLISHED(22002, "活动未发布，暂时无法报名"),
    ACTIVITY_NOT_IN_SIGNUP_WINDOW(22003, "当前不在报名时间范围内"),
    ACTIVITY_STATUS_CONFLICT(22004, "活动状态已变化，请刷新后重试"),
    ACTIVITY_NO_CAPACITY(22005, "活动容量配置不合法"),
    ACTIVITY_TIME_CONFLICT(22006, "活动时间配置不合法"),
    ACTIVITY_CANNOT_MODIFY(22007, "当前状态下活动不允许修改"),
    ACTIVITY_CANNOT_CANCEL(22008, "当前状态下活动不允许取消"),

    // ===== 报名模块 23xxx =====
    ALREADY_REGISTERED(23001, "您已报名该活动，请勿重复报名"),
    STOCK_NOT_ENOUGH(23002, "活动名额已满，可以加入候补队列"),
    RATE_LIMITED(23003, "操作过于频繁，请稍后再试"),
    RESERVATION_IN_PROGRESS(23004, "您的报名正在确认中，请稍后查看结果"),
    REGISTRATION_NOT_EXIST(23005, "报名记录不存在"),
    CANNOT_CANCEL_CURRENT_STATUS(23006, "当前状态不允许取消报名（已签到或已结束）"),
    REGISTRATION_STATE_CONFLICT(23007, "报名状态已变化，请刷新后重试"),
    CANNOT_REGISTER_OWN_ACTIVITY(23008, "组织者不能报名自己发布的活动"),

    // ===== 候补模块 24xxx =====
    WAITLIST_DUPLICATE(24001, "您已在候补队列中，请勿重复排队"),
    WAITLIST_NOT_EXIST(24002, "候补记录不存在"),
    WAITLIST_NOT_ALLOWED(24003, "当前无需候补，可直接报名"),
    WAITLIST_NOT_PROMOTED(24004, "候补尚未晋级，请耐心等待通知"),
    WAITLIST_EXPIRED(24005, "候补晋级已超时，名额已释放"),
    WAITLIST_STATE_CONFLICT(24006, "候补状态已变化，请刷新后重试"),

    // ===== 签到模块 25xxx =====
    CHECKIN_TOKEN_INVALID(25001, "签到凭证无效"),
    CHECKIN_TOKEN_EXPIRED(25002, "签到凭证已过期"),
    ALREADY_CHECKED_IN(25003, "该报名已签到，请勿重复签到"),
    CHECKIN_NOT_IN_TIME_WINDOW(25004, "当前不在签到时间范围内"),
    CHECKIN_REGISTRATION_NOT_CONFIRMED(25005, "报名尚未确认，无法签到"),
    CHECKIN_NO_PERMISSION(25006, "只有活动组织者或管理员可以核销签到"),

    // ===== 消息与补偿 26xxx =====
    MESSAGE_SEND_FAIL(26001, "消息发送失败，系统将自动重试"),
    COMPENSATION_TASK_NOT_EXIST(26002, "补偿任务不存在"),
    EXPORT_TASK_NOT_EXIST(26003, "导出任务不存在"),

    // ===== 基础设施 3xxxx =====
    LOCK_TIMEOUT(30001, "获取分布式锁超时，请稍后重试"),
    REDIS_ERROR(30002, "缓存服务异常"),
    MQ_ERROR(30003, "消息服务异常"),
    DB_ERROR(30004, "数据库异常"),
    CACHE_SERIALIZE_ERROR(30005, "缓存序列化异常");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
