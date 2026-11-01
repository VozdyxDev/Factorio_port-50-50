package com.factorio.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One shared pool for the short and long background jobs.
 * {@code Executors.newSingleThreadExecutor().execute(...)} was used before: every call left an idle
 * thread behind (the executor was never shut down). Threads of this pool end after 30 s of idling.
 */
public final class AppExecutors {
    private static final AtomicInteger counter = new AtomicInteger();
    private static final ThreadFactory factory = (runnable) -> {
        Thread thread = new Thread(runnable, "bg-"+counter.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    private static final ExecutorService background = new ThreadPoolExecutor(
        0, Integer.MAX_VALUE, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), factory);

    private AppExecutors() {}

    public static void execute(Runnable runnable) {
        background.execute(runnable);
    }

    public static ExecutorService background() {
        return background;
    }
}
