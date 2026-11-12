package dev.dymarket.estate;

import java.util.List;
import java.util.UUID;

/**
 * A tenant's rent of a listing. The tenant holds timed DyClaim trust on every claim of the group until
 * {@code paidUntil + grace}. Fields change only while holding this object's monitor (see {@link EstateService}).
 */
public final class Lease {
    /** ACTIVE: paid; GRACE: the period is over and not paid yet; ENDED: the trust was taken back. */
    public enum Status {
        ACTIVE, GRACE, ENDED;

        public static Status parse(String value) {
            try {
                return valueOf(value);
            } catch (RuntimeException unknown) {
                return ENDED;
            }
        }
    }

    private final String id;
    private final String listingId;
    private volatile UUID owner;
    private volatile String ownerName;
    private volatile UUID tenant;
    private volatile String tenantName;
    private final String world;
    private final List<ChunkPos> chunks;
    private final long price;
    private final String currency;
    private final int days;
    private final long started;
    private final int taxBasisPoints;
    private volatile long paidUntil;
    private volatile boolean autorenew;
    private volatile boolean evicted;
    private volatile Status status;

    public Lease(String id, String listingId, UUID owner, String ownerName, UUID tenant, String tenantName, String world,
                 List<ChunkPos> chunks, long price, String currency, int days, long started, long paidUntil, boolean autorenew,
                 boolean evicted, int taxBasisPoints, Status status) {
        this.id = id;
        this.listingId = listingId;
        this.owner = owner;
        this.ownerName = ownerName;
        this.tenant = tenant;
        this.tenantName = tenantName;
        this.world = world;
        this.chunks = List.copyOf(chunks);
        this.price = price;
        this.currency = currency;
        this.days = days;
        this.started = started;
        this.paidUntil = paidUntil;
        this.autorenew = autorenew;
        this.evicted = evicted;
        this.taxBasisPoints = taxBasisPoints;
        this.status = status;
    }

    public String id() { return id; }
    public String listingId() { return listingId; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName == null ? owner.toString().substring(0, 8) : ownerName; }
    public UUID tenant() { return tenant; }
    public String tenantName() { return tenantName == null ? tenant.toString().substring(0, 8) : tenantName; }
    public String world() { return world; }
    public List<ChunkPos> chunks() { return chunks; }
    /** Rent per period in minor units. */
    public long price() { return price; }
    public String currency() { return currency; }
    public int days() { return days; }
    public long started() { return started; }
    public long paidUntil() { return paidUntil; }
    public boolean autorenew() { return autorenew; }
    /** The owner asked the lease to end when the paid period is over. */
    public boolean evicted() { return evicted; }
    public int taxBasisPoints() { return taxBasisPoints; }
    public Status status() { return status; }
    public boolean open() { return status != Status.ENDED; }

    public long periodMillis() {
        return days * EstateService.DAY;
    }

    public long tax() {
        return EstateService.share(price, taxBasisPoints, 10_000);
    }

    public boolean contains(String world, int x, int z) {
        if (!this.world.equals(world)) return false;
        for (ChunkPos chunk : chunks) if (chunk.x() == x && chunk.z() == z) return true;
        return false;
    }

    void paidUntil(long paidUntil) { this.paidUntil = paidUntil; }
    void autorenew(boolean autorenew) { this.autorenew = autorenew; }
    void evicted(boolean evicted) { this.evicted = evicted; }
    void status(Status status) { this.status = status; }
    void owner(UUID owner, String name) {
        this.owner = owner;
        if (name != null) this.ownerName = name;
    }
    void tenant(UUID tenant, String name) {
        this.tenant = tenant;
        if (name != null) this.tenantName = name;
    }

    @Override
    public String toString() {
        return "Lease#" + id + "(" + status + ")";
    }
}
