package com.campusflow.common.enums;

import lombok.Getter;

/**
 * 角色编码。权限模型：STUDENT / ORGANIZER / ADMIN 三级，数据库 user_role 关联，JWT 中携带。
 */
@Getter
public enum RoleCode {

    STUDENT("STUDENT", "学生"),
    ORGANIZER("ORGANIZER", "组织者"),
    ADMIN("ADMIN", "管理员");

    private final String code;
    private final String desc;

    RoleCode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
