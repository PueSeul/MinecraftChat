package com.dudal.javachat.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Test;

public final class BackgroundExecutorsTest {
    @Test
    public void fixedPoolReleasesIdleCoreThreads() {
        ExecutorService service = BackgroundExecutors.fixed("test", 2);
        try {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) service;
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(2, executor.getMaximumPoolSize());
            assertTrue(executor.allowsCoreThreadTimeOut());
        } finally {
            service.shutdownNow();
        }
    }
}
