package com.campusflow.common.enums;

import lombok.Getter;

/**
 * 报名状态机。
 * <pre>
 * 确认链路：RESERVED(异步确认中) -> CONFIRMED -> CHECKED_IN
 * 异常链路：RESERVED -> CONFIRM_FAILED(补偿释放名额后) -> 不可恢复
 * 取消链路：CONFIRMED -> CANCELLED（可重新报名，同一行复用）
 * </pre>
 */
@Getter
public enum RegistrationStatus {

    /** Redis 预留成功、等待 MQ 消费落库确认 */
    RESERVED(0, "确认中"),
    /** 数据库确认成功（有效报名） */
    CONFIRMED(1, "已报名"),
    /** 已签到 */
    CHECKED_IN(2, "已签到"),
    /** 已取消（行保留用于审计与重新报名复用） */
    CANCELLED(3, "已取消"),
    /** 预留超时未确认，名额已释放（补偿产生） */
    CONFIRM_FAILED(4, "确认失败");

    private final int code;
    private final String desc;

    RegistrationStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static RegistrationStatus ofCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (RegistrationStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }

    /** 是否为有效占用名额的状态（Redis members 集合中应存在） */
    public boolean occupiesStock() {
        return this == RESERVED || this == CONFIRMED || this == CHECKED_IN;
    }

    /** 是否允许取消 */
    public boolean canCancel() {
        return this == CONFIRMED;
    }
}
