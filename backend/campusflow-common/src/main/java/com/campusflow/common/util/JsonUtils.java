package com.campusflow.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * JSON 工具（静态 ObjectMapper，全局复用，禁止在循环里 new ObjectMapper）。
 */
@Slf4j
public final class JsonUtils {

    private JsonUtils() {
    }

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败: {}", obj.getClass().getSimpleName(), e);
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.error("JSON 反序列化失败: {}", clazz.getSimpleName(), e);
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.error("JSON 反序列化失败", e);
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /** 宽松模式：MQ 消息体解析失败时返回 null（由调用方决定补偿策略），不打断主流程 */
    public static <T> T fromJsonQuietly(String json, Class<T> clazz) {
        try {
            return fromJson(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> toMap(Object obj) {
        return MAPPER.convertValue(obj, new TypeReference<Map<String, Object>>() {
        });
    }
}
