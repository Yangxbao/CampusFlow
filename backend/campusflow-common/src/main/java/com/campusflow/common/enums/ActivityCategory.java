package com.campusflow.common.enums;

import lombok.Getter;

/**
 * 活动分类（种子数据中固化，前端下拉复用）。
 */
@Getter
public enum ActivityCategory {

    ACADEMIC(0, "学术讲座"),
    TECH(1, "技术交流"),
    COMPETITION(2, "创新创业比赛"),
    CAREER(3, "就业指导"),
    CLUB(4, "社团活动"),
    VOLUNTEER(5, "志愿服务"),
    RECRUIT(6, "校园招聘宣讲");

    private final int code;
    private final String desc;

    ActivityCategory(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ActivityCategory ofCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ActivityCategory s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}
