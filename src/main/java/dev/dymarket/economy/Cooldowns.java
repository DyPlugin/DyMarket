package dev.dymarket.economy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** The time of each player's last use of an action, for the waits set under {@code cooldowns} in config.yml. */
public final class Cooldowns {
    private record Key(UUID player, String action) { }

    private final Map<Key, Long> lastUse = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public Cooldowns() {
        this(System::currentTimeMillis);
    }

    public Cooldowns(LongSupplier clock) {
        this.clock = clock;
    }

    /** Milliseconds the player still has to wait before using the action again; 0 when it is ready. */
    public long remaining(UUID player, String action, long waitMillis) {
        if (waitMillis <= 0) return 0;
        Long last = lastUse.get(new Key(player, action));
        if (last == null) return 0;
        return Math.max(0, last + waitMillis - clock.getAsLong());
    }

    /** Starts the wait; called after the action went through. */
    public void used(UUID player, String action) {
        lastUse.put(new Key(player, action), clock.getAsLong());
    }

    /** Drops waits that ended; a player who leaves and rejoins still has to wait out a running one. */
    public void prune(long longestWaitMillis) {
        long now = clock.getAsLong();
        lastUse.values().removeIf(last -> last + longestWaitMillis <= now);
    }
}
