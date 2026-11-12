package dev.dymarket.estate;

import java.util.List;
import java.util.UUID;

/**
 * A group of connected DyClaim claims offered for sale or for rent. The status only changes while holding this
 * object's monitor (see {@link EstateService}); storage is updated afterwards with a conditional write.
 */
public final class EstateListing {
    public enum Type {
        SALE, RENT;

        public static Type parse(String value) {
            return "RENT".equalsIgnoreCase(value) ? RENT : SALE;
        }
    }

    /** ACTIVE listings are shown; LEASED rent listings have a tenant; every other status is final. */
    public enum Status {
        ACTIVE, LEASED, SOLD, CANCELLED, EXPIRED, REMOVED, ENDED;

        public static Status parse(String value) {
            try {
                return valueOf(value);
            } catch (RuntimeException unknown) {
                return REMOVED;
            }
        }

        public boolean open() {
            return this == ACTIVE || this == LEASED;
        }
    }

    private final String id;
    private volatile UUID owner;
    private volatile String ownerName;
    private final String world;
    private final List<ChunkPos> chunks;
    private final Type type;
    private final long price;
    private final String currency;
    private final int days;
    private final long created;
    private final long expires;
    /** Tax taken from the owner's share, in hundredths of a percent (500 = 5%), fixed when the claims are listed. */
    private final int taxBasisPoints;
    private volatile Status status;

    public EstateListing(String id, UUID owner, String ownerName, String world, List<ChunkPos> chunks, Type type, long price,
                         String currency, int days, long created, long expires, int taxBasisPoints, Status status) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.chunks = List.copyOf(chunks);
        this.type = type;
        this.price = price;
        this.currency = currency;
        this.days = days;
        this.created = created;
        this.expires = expires;
        this.taxBasisPoints = taxBasisPoints;
        this.status = status;
    }

    public String id() { return id; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName == null ? owner.toString().substring(0, 8) : ownerName; }
    public String world() { return world; }
    public List<ChunkPos> chunks() { return chunks; }
    public Type type() { return type; }
    /** The sale price, or the rent per period, in minor units of {@link #currency()}. */
    public long price() { return price; }
    public String currency() { return currency; }
    /** Days per rent period; 0 for a sale. */
    public int days() { return days; }
    public long created() { return created; }
    /** When an unsold listing closes; 0 = never. */
    public long expires() { return expires; }
    public int taxBasisPoints() { return taxBasisPoints; }
    public Status status() { return status; }

    public boolean contains(String world, int x, int z) {
        if (!this.world.equals(world)) return false;
        for (ChunkPos chunk : chunks) if (chunk.x() == x && chunk.z() == z) return true;
        return false;
    }

    public boolean expired(long now) {
        return expires > 0 && now >= expires;
    }

    /** The tax on one payment at this listing's price, rounded down. */
    public long tax() {
        return EstateService.share(price, taxBasisPoints, 10_000);
    }

    void status(Status status) { this.status = status; }
    void owner(UUID owner, String name) {
        this.owner = owner;
        if (name != null) this.ownerName = name;
    }

    @Override
    public String toString() {
        return "EstateListing#" + id + "(" + type + ", " + status + ")";
    }
}
