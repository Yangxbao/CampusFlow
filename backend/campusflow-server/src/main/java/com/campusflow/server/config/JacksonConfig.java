package com.campusflow.server.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局序列化定制。
 * <p>雪花 ID 是 19 位 Long，超出 JavaScript Number 安全整数范围（2^53-1），
 * 直接以 JSON number 下发会被前端舍入导致 ID 错乱（如 2123838677402947593 -> ...600）。
 * 统一将 Long 序列化为字符串，是雪花 ID + Vue 前端的标准解法。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
