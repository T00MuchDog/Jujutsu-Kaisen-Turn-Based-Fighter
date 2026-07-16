package com.jjktbf.graphics.multiplayer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class NetworkExecutors {
    private NetworkExecutors() {
    }

    static ThreadPoolExecutor newBoundedDaemonPool(
        String namePrefix,
        int threads,
        int queueCapacity
    ) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(
                task, namePrefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            factory,
            new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
