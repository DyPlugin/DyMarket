package dev.dymarket.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player's balances in memory, in minor units per currency. All reads and writes of the balances happen while
 * holding this object's monitor (see {@link EconomyService}); {@code dirty} marks changes not yet on disk.
 */
public final class Account {
    private final UUID id;
    private volatile String name;
    private final Map<String, Long> balances = new HashMap<>();
    /** Currencies without a stored row start at the currency's starting balance. */
    private boolean dirty;
    private boolean evicted;
    private volatile long lastAccess = System.currentTimeMillis();

    public Account(UUID id, String name, Map<String, Long> stored) {
        this.id = id;
        this.name = name;
        if (stored != null) balances.putAll(stored);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public void name(String name) { this.name = name; }
    long lastAccess() { return lastAccess; }
    void touch() { lastAccess = System.currentTimeMillis(); }

    /** Balance of a currency; a currency without a row has its starting balance. Caller holds the monitor. */
    long get(Currency currency) {
        Long value = balances.get(currency.id());
        return value == null ? currency.startingBalance() : value;
    }

    void put(Currency currency, long value) {
        balances.put(currency.id(), value);
        dirty = true;
    }

    boolean dirty() { return dirty; }
    void dirty(boolean value) { dirty = value; }
    boolean evicted() { return evicted; }
    void evict() { evicted = true; }

    /** A copy of the stored balances, taken while holding the monitor. */
    Map<String, Long> snapshot() {
        return Map.copyOf(balances);
    }

    boolean has(String currency) {
        return balances.containsKey(currency);
    }
}
