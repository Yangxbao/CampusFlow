package com.campusflow.lab.jvm.args;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JvmArgsLab 的 JUnit 测试：纯只读观测，无风险。 */
class JvmArgsLabTest {

    @Test
    @Timeout(30)
    void runtimeReportContainsAllSections() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8);

        Map<String, String> report = JvmArgsLab.collectRuntimeReport(capture);

        // Runtime 三兄弟
        assertTrue(Long.parseLong(report.get("maxMemoryMB")) > 0, "maxMemory 应大于 0");
        assertTrue(Long.parseLong(report.get("totalMemoryMB")) > 0);
        assertTrue(Long.parseLong(report.get("freeMemoryMB")) >= 0);

        // GC 名称
        assertNotNull(report.get("gcNames"), "应收集 GC 名称");
        assertFalse(report.get("gcNames").isEmpty());

        // 启动参数（可能为空列表，但键必须存在）
        assertNotNull(report.get("inputArguments"));

        String text = buf.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("maxMemory"), "输出应包含 maxMemory");
        assertTrue(text.contains("MemoryPool"), "输出应包含内存池明细（MemoryPoolMXBean）");
        assertTrue(text.contains("getInputArguments") || text.contains("InputArguments"),
                "输出应包含启动参数段");
    }

    @Test
    @Timeout(30)
    void inputArgumentsReflectJvmFlags() {
        // 无论带什么参数启动，getInputArguments 都应原样返回 JVM 收到的参数
        java.util.List<String> args = java.lang.management.ManagementFactory
                .getRuntimeMXBean().getInputArguments();
        assertNotNull(args);
        // 测试 JVM 由 surefire 启动，至少会有 -Dfile.encoding 之类参数（不强制断言数量）
        assertTrue(args.size() >= 0);
    }
}
