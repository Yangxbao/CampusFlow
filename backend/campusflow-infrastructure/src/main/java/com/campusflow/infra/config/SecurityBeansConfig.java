package com.campusflow.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器：BCrypt（自带盐、慢哈希，抵御彩虹表与暴力破解）。
 * 只引入 spring-security-crypto，不启用完整 Spring Security 自动装配。
 */
@Configuration
public class SecurityBeansConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 强度因子 10：单次哈希约 60-100ms，注册/登录频率下可接受
        return new BCryptPasswordEncoder(10);
    }
}
