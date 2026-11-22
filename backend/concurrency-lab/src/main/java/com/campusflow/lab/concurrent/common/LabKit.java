package com.campusflow.lab.concurrent.common;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发实验通用工具类：统一日志格式、受控 sleep/join、命名线程工厂。
 * 仅依赖 JDK 标准库。
 */
public final class LabKit {

    private LabKit() {
    }

    /** 打印实验小节分隔标题 */
    public static void section(String title) {
        System.out.println();
        System.out.println("==================== " + title + " ====================");
    }

    /** 带线程名的日志输出 */
    public static void log(String format, Object... args) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + String.format(format, args));
    }

    /** 不受中断影响的 sleep（恢复中断标志后返回） */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 带超时的 join（保证实验不会无限挂起） */
    public static void join(Thread t, long millis) {
        if (t == null || !t.isAlive()) {
            return;
        }
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 命名线程工厂：统一线程名前缀，可指定是否守护线程 */
    public static ThreadFactory namedFactory(String prefix, boolean daemon) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.getAndIncrement());
            t.setDaemon(daemon);
            return t;
        };
    }

    /** 毫秒耗时统计（由 startNanos = System.nanoTime() 起算） */
    public static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
