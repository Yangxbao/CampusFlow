package com.campusflow.lab.concurrent.concurrenthashmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConcurrentHashMapLab 的 JUnit 5 测试：CHM 并发安全、computeIfAbsent 原子、HashMap 并发丢数据。
 */
class ConcurrentHashMapLabTest {

    @Test
    @Timeout(60)
    void concurrentHashMapPutIsThreadSafe() throws Exception {
        Map<Integer, Integer> map = new ConcurrentHashMap<>();
        int size = ConcurrentHashMapLab.concurrentPut(map);
        assertEquals(16_000, size, "8 线程 x 2000 条并发 put 后所有数据都应存在");
    }

    @Test
    @Timeout(60)
    void computeIfAbsentRunsMappingFunctionExactlyOnce() throws Exception {
        int invocations = ConcurrentHashMapLab.computeIfAbsentRace();
        assertEquals(1, invocations, "computeIfAbsent 对同一 key 的 mappingFunction 只应由一个线程执行一次");
    }

    @Test
    @Timeout(60)
    void hashMapLosesDataUnderConcurrency() throws Exception {
        boolean lost = ConcurrentHashMapLab.hashMapLosesDataUnderConcurrency(5);
        assertTrue(lost, "HashMap 并发 put 应出现丢数据（或并发异常），8 线程 x 2000 条多轮内必然发生");
    }

    @Test
    @Timeout(30)
    void hashMapPutIsFineWhenSequential() {
        // 对照组：单线程下 HashMap 数据完整
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < 16_000; i++) {
            map.put(i, i);
        }
        assertEquals(16_000, map.size(), "单线程 HashMap 没有问题 —— 问题是并发使用");
    }
}
