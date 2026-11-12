package dev.dymarket.storage;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Writes a small YAML file off the server thread. The caller serialises the data (cheap, in memory) and hands the
 * text over; quick changes in a row are merged into one write of the newest text, and an older text is never written
 * after a newer one. {@link #flush()} writes what is still waiting, for example when the plugin stops.
 */
public final class DeferredWriter {
    private record Snapshot(long sequence, String content) {
    }

    private final File file;
    private final Logger logger;
    private final Executor executor;
    private final AtomicReference<Snapshot> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private long written;

    public DeferredWriter(File file, Logger logger, Executor executor) {
        this.file = file;
        this.logger = logger;
        this.executor = executor == null ? Runnable::run : executor;
    }

    public void submit(String content) {
        pending.set(new Snapshot(sequence.incrementAndGet(), content));
        schedule();
    }

    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            executor.execute(this::drain);
        } catch (RuntimeException rejected) {
            // The scheduler no longer accepts work (plugin stopping); write here instead.
            drain();
        }
    }

    private void drain() {
        try {
            Snapshot snapshot;
            while ((snapshot = pending.getAndSet(null)) != null) write(snapshot);
        } finally {
            scheduled.set(false);
            if (pending.get() != null) schedule();
        }
    }

    /** Writes the newest waiting text now, on the calling thread. */
    public void flush() {
        Snapshot snapshot = pending.getAndSet(null);
        if (snapshot != null) write(snapshot);
    }

    private synchronized void write(Snapshot snapshot) {
        if (snapshot.sequence() <= written) return;
        try {
            YamlFiles.write(snapshot.content(), file);
            written = snapshot.sequence();
        } catch (IOException exception) {
            logger.warning("Could not save " + file.getName() + ": " + exception.getMessage());
        }
    }
}
