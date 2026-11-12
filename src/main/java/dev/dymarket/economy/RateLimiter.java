package dev.dymarket.economy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Token buckets per player and action: up to {@code perMinute} actions at once, refilled evenly over a minute. */
public final class RateLimiter {
    private record Key(UUID player, String action) { }

    private static final class Bucket {
        double tokens;
        long updated;
    }

    private final Map<Key, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    public RateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /** Takes one token; false when the player has used up the limit. {@code perMinute <= 0} means no limit. */
    public boolean tryAcquire(UUID player, String action, int perMinute) {
        if (perMinute <= 0) return true;
        long now = clock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(new Key(player, action), key -> {
            Bucket created = new Bucket();
            created.tokens = perMinute;
            created.updated = now;
            return created;
        });
        synchronized (bucket) {
            bucket.tokens = Math.min(perMinute, bucket.tokens + (now - bucket.updated) * perMinute / 60_000d);
            bucket.updated = now;
            if (bucket.tokens < 1) return false;
            bucket.tokens -= 1;
            return true;
        }
    }

    public void forget(UUID player) {
        buckets.keySet().removeIf(key -> key.player().equals(player));
    }
}
