package com.dudal.javachat.util;

import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates low-priority pools whose idle threads release their stack memory. */
public final class BackgroundExecutors {
    private static final long IDLE_TIMEOUT_SECONDS = 30L;

    private BackgroundExecutors() {}

    public static ExecutorService fixed(String name, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                IDLE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory(name));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ThreadFactory threadFactory(String name) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
                // Priority changes are an optimization; never block the task.
            }
            task.run();
        }, "MinecraftChat-" + name + "-" + sequence.incrementAndGet());
    }
}
