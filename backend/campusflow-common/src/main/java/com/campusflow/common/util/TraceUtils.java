package com.campusflow.common.util;

import java.util.UUID;

/**
 * TraceId 工具：32 位无连字符 UUID，贯穿 HTTP -> Service -> MQ -> 日志。
 */
public final class TraceUtils {

    private TraceUtils() {
    }

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
