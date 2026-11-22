package com.campusflow.common.util;

/**
 * 雪花算法全局唯一 ID 生成器（无外部依赖实现）。
 * <p>结构（64 bit）：1 符号位 + 41 时间戳(ms) + 10 机器ID(0-1023) + 12 序列号(0-4095)。
 * <p>单机 QPC 约 409.6 万/毫秒；时钟回拨时等待最多 {@link #MAX_BACKWARD_MS}，超出则抛异常由上层处理。
 * <p>线程安全：synchronized 保护状态位（该实现下争用极低，够用且易懂）。
 */
public class SnowflakeIdWorker {

    /** 机器ID位数 */
    private static final long WORKER_ID_BITS = 10L;
    /** 序列号位数 */
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    /** 允许的最大时钟回拨毫秒数 */
    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdWorker(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 [0, " + MAX_WORKER_ID + "] 范围内");
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个全局唯一 ID（阻塞至下一毫秒以防重复）。
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            // 时钟回拨处理：小幅回拨等待追平，大幅回拨直接失败（避免生成重复 ID）
            long offset = lastTimestamp - timestamp;
            if (offset <= MAX_BACKWARD_MS) {
                try {
                    wait(offset << 1);
                    timestamp = System.currentTimeMillis();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("snowflake 时钟回拨等待被中断", e);
                }
            }
            if (timestamp < lastTimestamp) {
                throw new IllegalStateException("时钟回拨超过 " + MAX_BACKWARD_MS + "ms，拒绝生成 ID");
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 当前毫秒序列耗尽，自旋等待下一毫秒
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return ((timestamp - START_EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long ts = System.currentTimeMillis();
        while (ts <= lastTimestamp) {
            ts = System.currentTimeMillis();
        }
        return ts;
    }

    /** 起始纪元：2020-01-01 00:00:00 UTC，延长可用年限 */
    public static final long START_EPOCH = 1577836800000L;
}
