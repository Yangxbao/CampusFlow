package com.campusflow.common.enums;

import lombok.Getter;

/**
 * 报名预留（待处理）记录状态机 —— 可靠消息与补偿的核心依据。
 * <pre>
 * REDIS_RESERVED --Lua 预留成功写入--> PENDING_CONFIRM --消费成功--> CONFIRMED
 *                                                    +--超时/发送失败重试耗尽--> COMPENSATING --幂等释放--> RELEASED
 * </pre>
 */
@Getter
public enum ReservationStatus {

    /** Redis 已预留、消息待确认落库 */
    PENDING_CONFIRM(0, "预留待确认"),
    /** MQ 消费成功、报名落库确认 */
    CONFIRMED(1, "已确认"),
    /** 补偿中（正在重发消息或释放名额） */
    COMPENSATING(2, "补偿中"),
    /** 名额已释放（幂等释放完成） */
    RELEASED(3, "已释放");

    private final int code;
    private final String desc;

    ReservationStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ReservationStatus ofCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ReservationStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}
