package dev.dymarket.storage;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single DyMarket-IO thread. Every disk read and write runs here, one after another, so the database never
 * sees two writers and the server threads never touch the disk.
 */
public final class IoThread implements java.util.concurrent.Executor {
    private final ExecutorService executor;
    private final Logger logger;
    private volatile Thread thread;

    public IoThread(Logger logger) {
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread created = new Thread(runnable, "DyMarket-IO");
            created.setDaemon(true);
            thread = created;
            return created;
        });
    }

    public boolean isIoThread() {
        return Thread.currentThread() == thread;
    }

    @Override
    public void execute(Runnable task) {
        if (executor.isShutdown()) {
            task.run();
            return;
        }
        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable failure) {
                logger.log(Level.WARNING, "DyMarket-IO task failed", failure);
            }
        });
    }

    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable body = () -> {
            try {
                result.complete(task.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        if (isIoThread() || executor.isShutdown()) body.run();
        else executor.execute(body);
        return result;
    }

    /**
     * Runs a task on the IO thread and waits for it (directly when already there). Used where a caller needs an
     * answer now, for example an offline player's balance asked for by another plugin; the disk work itself still
     * happens on the IO thread.
     */
    public <T> T call(Callable<T> task) throws Exception {
        if (isIoThread() || executor.isShutdown()) return task.call();
        try {
            return submit(task).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("DyMarket-IO did not answer in time");
        }
    }

    /** Stops accepting work and waits for queued work to finish. */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS))
                logger.warning("DyMarket-IO did not finish within 30 seconds.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
