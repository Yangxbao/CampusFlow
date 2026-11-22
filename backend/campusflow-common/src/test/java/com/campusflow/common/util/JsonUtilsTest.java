package com.campusflow.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JSON 工具单元测试：对象往返、LocalDateTime 支持、未知字段容忍、宽松解析。
 */
class JsonUtilsTest {

    @Test
    void 对象序列化往返() {
        Map<String, Object> origin = Map.of("name", "活动A", "count", 42);
        String json = JsonUtils.toJson(origin);
        Map<String, Object> parsed = JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {
        });
        assertEquals("活动A", parsed.get("name"));
        assertEquals(42, ((Number) parsed.get("count")).intValue());
    }

    @Test
    void LocalDateTime支持() {
        record Dto(LocalDateTime time) {
        }
        String json = JsonUtils.toJson(new Dto(LocalDateTime.of(2026, 11, 21, 12, 30)));
        Dto parsed = JsonUtils.fromJson(json, Dto.class);
        assertEquals(LocalDateTime.of(2026, 11, 21, 12, 30), parsed.time());
    }

    @Test
    void 未知字段容忍() {
        record Dto(String known) {
        }
        Dto parsed = JsonUtils.fromJson("{\"known\":\"a\",\"unknownField\":123}", Dto.class);
        assertEquals("a", parsed.known());
    }

    @Test
    void 宽松解析失败返回null() {
        assertNull(JsonUtils.fromJsonQuietly("not-json", Map.class));
        assertThrows(IllegalStateException.class, () -> JsonUtils.fromJson("not-json", Map.class));
    }

    @Test
    void 空输入处理() {
        assertNull(JsonUtils.toJson(null));
        assertNull(JsonUtils.fromJson(null, Map.class));
        assertEquals(List.of(), JsonUtils.fromJson("[]", List.class));
    }
}
