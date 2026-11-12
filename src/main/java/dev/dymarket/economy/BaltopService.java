package dev.dymarket.economy;

import dev.dymarket.storage.IoThread;
import dev.dymarket.storage.SqliteStore;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * The richest players per currency, read from storage at most every {@code baltop.refresh-seconds} and served
 * from memory in between (for /baltop and placeholders). Players with the exclude permission are remembered
 * when they join and left out.
 */
public final class BaltopService {
    public static final int SIZE = 500;

    private record Snapshot(List<SqliteStore.TopEntry> entries, long taken) { }

    private final EconomyService economy;
    private final SqliteStore store;
    private final IoThread io;
    private final Logger logger;
    private final IntSupplier refreshSeconds;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();

    public BaltopService(EconomyService economy, SqliteStore store, IoThread io, Logger logger, IntSupplier refreshSeconds) {
        this.economy = economy;
        this.store = store;
        this.io = io;
        this.logger = logger;
        this.refreshSeconds = refreshSeconds;
    }

    public void hidden(UUID player, boolean hide) {
        if (hide) hidden.add(player);
        else hidden.remove(player);
    }

    /** The cached list, refreshed in the background when stale; empty until the first refresh finishes. */
    public List<SqliteStore.TopEntry> cached(String currency) {
        Snapshot snapshot = snapshots.get(currency);
        if (snapshot == null || stale(snapshot)) refreshLater(currency);
        return snapshot == null ? List.of() : snapshot.entries();
    }

    /** A fresh enough list, reading storage when needed. Call on the DyMarket-IO thread. */
    public List<SqliteStore.TopEntry> current(String currency) {
        Snapshot snapshot = snapshots.get(currency);
        if (snapshot == null || stale(snapshot)) snapshot = refresh(currency);
        return snapshot == null ? List.of() : snapshot.entries();
    }

    private boolean stale(Snapshot snapshot) {
        return System.currentTimeMillis() - snapshot.taken() > refreshSeconds.getAsInt() * 1000L;
    }

    private void refreshLater(String currency) {
        if (!refreshing.add(currency)) return;
        io.execute(() -> {
            try {
                refresh(currency);
            } finally {
                refreshing.remove(currency);
            }
        });
    }

    private Snapshot refresh(String currency) {
        try {
            economy.flush();
            Snapshot snapshot = new Snapshot(io.call(() -> store.top(currency, SIZE, Set.copyOf(hidden))), System.currentTimeMillis());
            snapshots.put(currency, snapshot);
            return snapshot;
        } catch (Exception failure) {
            logger.warning("Could not read the top balances: " + failure.getMessage());
            return snapshots.get(currency);
        }
    }

    public void clear() {
        snapshots.clear();
    }
}
