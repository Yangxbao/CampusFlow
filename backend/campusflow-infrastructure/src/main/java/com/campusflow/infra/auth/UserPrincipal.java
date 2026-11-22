package com.campusflow.infra.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 当前登录用户身份（JWT 解析产物，贯穿请求链路）。
 */
@Data
@AllArgsConstructor
public class UserPrincipal implements Serializable {

    private Long userId;
    private String username;
    private List<String> roles;

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }

    public boolean isOrganizer() {
        return hasRole("ORGANIZER");
    }
}
