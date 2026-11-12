package dev.dymarket.auction;

import java.util.UUID;

/** An item waiting in a player's collection box: expired, cancelled or removed listings and undelivered purchases. */
public record CollectEntry(long id, UUID owner, byte[] item, String reason, long timestamp) {
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REMOVED = "REMOVED";
    public static final String FULL_INVENTORY = "FULL_INVENTORY";
    /** A purchase whose buyer left before the item could be handed over. */
    public static final String PURCHASE = "PURCHASE";
}
