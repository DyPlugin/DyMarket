package dev.dymarket.estate;

import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.economy.CurrencyRegistry;
import dev.dymarket.economy.TransactionKind;
import dev.dymarket.economy.TransactionResult;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The estate rules (selling and renting groups of DyClaim claims), free of server classes so they can be tested on
 * their own.
 *
 * <p>Threads: the player actions ({@link #list}, {@link #buy}, {@link #lease}, {@link #leave}, {@link #evict},
 * {@link #autorenew}, {@link #unlist}) are called on the acting player's thread. Storage and money run through
 * {@link Platform#io} on DyMarket-IO; every DyClaim write runs through {@link Platform#region} on the thread that
 * owns the claim's chunk. {@link #check} (renewals, grace, listing expiry) runs on DyMarket-IO. A listing's or
 * lease's state only changes while holding its monitor, and storage applies listing changes only when the stored
 * status still matches, so a listing is sold, leased or closed only once.
 */
public final class EstateService {
    public static final long HOUR = 3_600_000L;
    public static final long DAY = 24 * HOUR;

    public enum Result {
        OK, DISABLED, NOT_IN_CLAIM, NOT_OWNER, TENANT, ALREADY_LISTED, LEASED, CURRENCY_NOT_ALLOWED, UNKNOWN_CURRENCY,
        PRICE_TOO_LOW, PRICE_TOO_HIGH, DAYS_OUT_OF_RANGE, LIMIT_REACHED, CANCELLED, STORAGE_ERROR, NOT_FOUND, NOT_AVAILABLE,
        WRONG_TYPE, OWN_LISTING, PAYMENT_FAILED, CLAIM_FAILED, NO_LEASE, NOT_TENANT, ALREADY_EVICTED, REFUND_NOT_AFFORDABLE
    }

    /** Why a lease ended. */
    public enum EndReason { LEFT, EVICTED, UNPAID, UNLISTED, CLAIM_LOST, REMOVED }

    /** What happened; {@code payment} is set for {@code PAYMENT_FAILED}; {@code amount} is a refund or rent in minor units. */
    public record Outcome(Result result, EstateListing listing, Lease lease, TransactionResult payment, long amount) {
        public static Outcome of(Result result) {
            return new Outcome(result, null, null, null, 0);
        }

        public static Outcome of(Result result, EstateListing listing) {
            return new Outcome(result, listing, null, null, 0);
        }

        public static Outcome of(Result result, Lease lease) {
            return new Outcome(result, null, lease, null, 0);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    /** Listings and leases on disk. Every method runs on the DyMarket-IO thread. */
    public interface Storage {
        /** ACTIVE and LEASED listings. */
        List<EstateListing> loadListings() throws Exception;

        /** ACTIVE and GRACE leases. */
        List<Lease> loadLeases() throws Exception;

        void insertListing(EstateListing listing) throws Exception;

        /** Changes the stored status only when it is still {@code from}; false when it was not. */
        boolean transitionListing(String id, EstateListing.Status from, EstateListing.Status to) throws Exception;

        void insertLease(Lease lease) throws Exception;

        /** Writes the lease's paid-until time, auto-renew and eviction flags and status. */
        void updateLease(Lease lease) throws Exception;
    }

    /** Money (the economy core). Called on DyMarket-IO. */
    public interface Bank {
        /** Takes the full price from the payer; {@code tax} is the part that leaves the economy. */
        TransactionResult charge(UUID payer, Currency currency, long price, long tax, TransactionKind kind, String note);

        TransactionResult credit(UUID receiver, Currency currency, long amount, TransactionKind kind, String note);

        /** Gives money back that this module took a moment ago (a failed sale or lease). */
        TransactionResult refund(UUID receiver, Currency currency, long amount, String note);

        long balance(UUID player, Currency currency);

        /**
         * Moves {@code amount} from the owner to the tenant as a refund; when the owner has less, moves what the owner
         * has. Returns the amount moved (0 when nothing could be moved).
         */
        long refundFromOwner(UUID owner, UUID tenant, Currency currency, long amount, String note);
    }

    /** Everything that needs the server: threads, messages and API events. */
    public interface Platform {
        void io(Runnable task);

        /** Runs on the thread that owns the chunk (at once when the current thread already does). */
        void region(String world, int chunkX, int chunkZ, Runnable task);

        /** Tells a player something; with {@code keep}, an offline player is told on the next join. */
        void notify(UUID player, String key, Map<String, String> values, boolean keep);

        boolean callList(ListRequest request, List<ChunkPos> chunks);

        boolean callSale(EstateListing listing, UUID buyer);

        boolean callLeaseStart(EstateListing listing, UUID tenant);

        void callLeaseEnd(Lease lease, EndReason reason);
    }

    /** The estate settings in effect; prices are in major units of the listing currency. */
    public record Settings(String defaultCurrency, List<String> allowedCurrencies, int maxChunks, double taxPercent,
                           boolean saleEnabled, double saleMinPrice, double saleMaxPrice, boolean rentEnabled, int minDays,
                           int maxDays, Set<String> rights, long graceMillis, int refundPercent, long listingExpiryMillis) {
        public static Settings defaults() {
            return new Settings("money", List.of("money", "credits"), 64, 5.0, true, 1.0, 1_000_000_000.0, true, 1, 90,
                    Set.of("build", "containers", "doors", "trapdoors", "redstone", "entities", "teleport"), 24 * HOUR, 100, 30 * DAY);
        }
    }

    /**
     * A request to list the claim group around a chunk. {@code here} lists only that chunk. {@code limit} below zero
     * means no limit; {@code taxBasisPoints} is the owner's tax rate (0 with the bypass permission).
     */
    public record ListRequest(UUID owner, String ownerName, String world, int chunkX, int chunkZ, EstateListing.Type type,
                              String currency, long price, int days, boolean here, int limit, int taxBasisPoints) {
    }

    private static final String ID_CHARS = "abcdefghijkmnpqrstuvwxyz23456789";

    private final Storage storage;
    private final Bank bank;
    private final Platform platform;
    private final ClaimBridge claims;
    private final Supplier<CurrencyRegistry> currencies;
    private final LongSupplier clock;
    private final Logger logger;
    private volatile Settings settings = Settings.defaults();
    private final Map<String, EstateListing> listings = new ConcurrentHashMap<>();
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    /** Held while checking that chunks are free and reserving them for a new listing. */
    private final Object listLock = new Object();

    public EstateService(Storage storage, Bank bank, Platform platform, ClaimBridge claims, Supplier<CurrencyRegistry> currencies,
                         LongSupplier clock, Logger logger) {
        this.storage = storage;
        this.bank = bank;
        this.platform = platform;
        this.claims = claims;
        this.currencies = currencies;
        this.clock = clock;
        this.logger = logger;
    }

    public Settings settings() { return settings; }
    public void settings(Settings settings) { this.settings = settings; }
    public long now() { return clock.getAsLong(); }

    /** Replaces what is in memory (start-up). Runs on DyMarket-IO. */
    public void load(List<EstateListing> open, List<Lease> running) {
        listings.clear();
        leases.clear();
        for (EstateListing listing : open) if (listing.status().open()) listings.put(listing.id(), listing);
        for (Lease lease : running) if (lease.open()) leases.put(lease.id(), lease);
    }

    // ---- queries (memory only, any thread) ------------------------------------------------------------------

    public EstateListing get(String id) {
        return id == null ? null : listings.get(id.toLowerCase(Locale.ROOT));
    }

    /** Listings that can be bought or leased now. */
    public List<EstateListing> active() {
        long now = clock.getAsLong();
        List<EstateListing> list = new ArrayList<>();
        for (EstateListing listing : listings.values())
            if (listing.status() == EstateListing.Status.ACTIVE && !listing.expired(now)) list.add(listing);
        return list;
    }

    /** Open (active or leased) listings of an owner. */
    public List<EstateListing> byOwner(UUID owner) {
        List<EstateListing> list = new ArrayList<>();
        for (EstateListing listing : listings.values()) if (listing.status().open() && listing.owner().equals(owner)) list.add(listing);
        return list;
    }

    public int activeCount() {
        return active().size();
    }

    public EstateListing listingAt(String world, int x, int z) {
        for (EstateListing listing : listings.values()) if (listing.status().open() && listing.contains(world, x, z)) return listing;
        return null;
    }

    /** A lease by its own id or by the id of its listing. */
    public Lease lease(String id) {
        if (id == null) return null;
        String key = id.toLowerCase(Locale.ROOT);
        Lease lease = leases.get(key);
        if (lease != null) return lease;
        for (Lease open : leases.values()) if (open.listingId().equals(key)) return open;
        return null;
    }

    public Lease leaseAt(String world, int x, int z) {
        for (Lease lease : leases.values()) if (lease.open() && lease.contains(world, x, z)) return lease;
        return null;
    }

    public Lease leaseOf(EstateListing listing) {
        for (Lease lease : leases.values()) if (lease.open() && lease.listingId().equals(listing.id())) return lease;
        return null;
    }

    /** Open leases where the player is the tenant. */
    public List<Lease> leasesAsTenant(UUID player) {
        List<Lease> list = new ArrayList<>();
        for (Lease lease : leases.values()) if (lease.open() && lease.tenant().equals(player)) list.add(lease);
        return list;
    }

    /** Open leases where the player is the owner. */
    public List<Lease> leasesAsOwner(UUID player) {
        List<Lease> list = new ArrayList<>();
        for (Lease lease : leases.values()) if (lease.open() && lease.owner().equals(player)) list.add(lease);
        return list;
    }

    /** Open leases where the player is tenant or owner. */
    public List<Lease> leasesOf(UUID player) {
        List<Lease> list = new ArrayList<>();
        for (Lease lease : leases.values()) if (lease.open() && (lease.tenant().equals(player) || lease.owner().equals(player))) list.add(lease);
        return list;
    }

    // ---- connected groups -----------------------------------------------------------------------------------

    /**
     * The chunks connected to {@code start} through 4-neighbours that are all in {@code owned}, breadth first from
     * {@code start}, at most {@code cap} of them. Empty when {@code start} itself is not owned.
     */
    public static List<ChunkPos> group(Set<ChunkPos> owned, ChunkPos start, int cap) {
        List<ChunkPos> found = new ArrayList<>();
        if (!owned.contains(start) || cap <= 0) return found;
        Set<ChunkPos> seen = new HashSet<>();
        ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && found.size() < cap) {
            ChunkPos chunk = queue.poll();
            found.add(chunk);
            for (ChunkPos next : List.of(new ChunkPos(chunk.x() + 1, chunk.z()), new ChunkPos(chunk.x() - 1, chunk.z()),
                    new ChunkPos(chunk.x(), chunk.z() + 1), new ChunkPos(chunk.x(), chunk.z() - 1))) {
                if (owned.contains(next) && seen.add(next)) queue.add(next);
            }
        }
        return found;
    }

    /** The owner's claim group around a chunk in one world (or only that chunk with {@code here}). */
    public List<ChunkPos> groupAt(UUID owner, String world, int x, int z, boolean here) {
        ChunkPos start = new ChunkPos(x, z);
        if (here) return List.of(start);
        Set<ChunkPos> owned = new LinkedHashSet<>();
        for (ClaimBridge.ClaimInfo info : claims.claimsOf(owner))
            if (info != null && world.equals(info.world()) && owner.equals(info.owner())) owned.add(info.chunk());
        owned.add(start);
        return group(owned, start, Math.max(1, settings.maxChunks()));
    }

    // ---- listing ----------------------------------------------------------------------------------------------

    public long minPrice(Currency currency, EstateListing.Type type) {
        return Math.max(1, currency.toMinor(type == EstateListing.Type.SALE ? settings.saleMinPrice() : 0));
    }

    public long maxPrice(Currency currency, EstateListing.Type type) {
        if (type != EstateListing.Type.SALE) return currency.maxBalance();
        return Math.min(currency.maxBalance(), currency.toMinor(settings.saleMaxPrice()));
    }

    /** Checks the parts of a request that do not need the claims. */
    Result checkTerms(ListRequest request) {
        Settings current = settings;
        boolean sale = request.type() == EstateListing.Type.SALE;
        if (sale ? !current.saleEnabled() : !current.rentEnabled()) return Result.DISABLED;
        Currency currency = currencies.get().get(request.currency());
        if (currency == null) return Result.UNKNOWN_CURRENCY;
        if (!current.allowedCurrencies().contains(currency.id())) return Result.CURRENCY_NOT_ALLOWED;
        if (request.price() < minPrice(currency, request.type())) return Result.PRICE_TOO_LOW;
        if (request.price() > maxPrice(currency, request.type())) return Result.PRICE_TOO_HIGH;
        if (!sale && (request.days() < current.minDays() || request.days() > current.maxDays())) return Result.DAYS_OUT_OF_RANGE;
        return Result.OK;
    }

    /**
     * Lists the claim group the owner stands in. The chunk must be the owner's claim; a leased or already listed
     * chunk refuses the whole group. Runs on the owner's thread; storage follows on DyMarket-IO.
     */
    public void list(ListRequest request, Consumer<Outcome> done) {
        Result terms = checkTerms(request);
        if (terms != Result.OK) {
            done.accept(Outcome.of(terms));
            return;
        }
        ClaimBridge.ClaimInfo start = claims.claimAt(request.world(), request.chunkX(), request.chunkZ());
        if (start == null) {
            done.accept(Outcome.of(Result.NOT_IN_CLAIM));
            return;
        }
        if (!request.owner().equals(start.owner())) {
            Lease lease = leaseAt(request.world(), request.chunkX(), request.chunkZ());
            done.accept(Outcome.of(lease != null && lease.tenant().equals(request.owner()) ? Result.TENANT : Result.NOT_OWNER));
            return;
        }
        List<ChunkPos> group = groupAt(request.owner(), request.world(), request.chunkX(), request.chunkZ(), request.here());
        if (!platform.callList(request, group)) {
            done.accept(Outcome.of(Result.CANCELLED));
            return;
        }
        long now = clock.getAsLong();
        long expiry = settings.listingExpiryMillis();
        EstateListing listing;
        synchronized (listLock) {
            for (ChunkPos chunk : group) {
                if (leaseAt(request.world(), chunk.x(), chunk.z()) != null) {
                    done.accept(Outcome.of(Result.LEASED));
                    return;
                }
                EstateListing taken = listingAt(request.world(), chunk.x(), chunk.z());
                if (taken != null) {
                    done.accept(Outcome.of(Result.ALREADY_LISTED, taken));
                    return;
                }
            }
            if (request.limit() >= 0 && byOwner(request.owner()).size() >= request.limit()) {
                done.accept(Outcome.of(Result.LIMIT_REACHED));
                return;
            }
            listing = new EstateListing(newId(), request.owner(), request.ownerName(), request.world(), group, request.type(),
                    request.price(), request.currency(), request.type() == EstateListing.Type.RENT ? request.days() : 0, now,
                    expiry > 0 ? now + expiry : 0, request.taxBasisPoints(), EstateListing.Status.ACTIVE);
            // Reserved at once, so a second listing of the same chunks is refused while this one is being stored.
            listings.put(listing.id(), listing);
        }
        platform.io(() -> {
            try {
                storage.insertListing(listing);
                done.accept(Outcome.of(Result.OK, listing));
            } catch (Exception failure) {
                logger.log(Level.WARNING, "Could not store estate listing " + listing.id(), failure);
                synchronized (listing) {
                    listing.status(EstateListing.Status.CANCELLED);
                }
                listings.remove(listing.id(), listing);
                done.accept(Outcome.of(Result.STORAGE_ERROR, listing));
            }
        });
    }

    // ---- buying -------------------------------------------------------------------------------------------------

    /**
     * Buys a sale listing: the buyer pays, every claim is transferred to the buyer on its region thread, then the
     * owner receives the price minus tax. If any transfer fails, the claims already moved go back to the owner, the
     * buyer gets the full price back and the listing is cancelled.
     */
    public void buy(String id, UUID buyer, String buyerName, Consumer<Outcome> done) {
        EstateListing listing = get(id);
        Result problem = checkTake(listing, buyer, EstateListing.Type.SALE);
        if (problem != Result.OK) {
            done.accept(Outcome.of(problem, listing));
            return;
        }
        Currency currency = currencies.get().get(listing.currency());
        if (!platform.callSale(listing, buyer)) {
            done.accept(Outcome.of(Result.CANCELLED, listing));
            return;
        }
        if (!flip(listing, EstateListing.Status.ACTIVE, EstateListing.Status.SOLD)) {
            done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
            return;
        }
        platform.io(() -> {
            if (!storeFlip(listing, EstateListing.Status.ACTIVE, EstateListing.Status.SOLD, done)) return;
            String note = "estate " + listing.id();
            long tax = Math.min(listing.tax(), listing.price());
            TransactionResult charged = bank.charge(buyer, currency, listing.price(), tax, TransactionKind.ESTATE_SALE, note);
            if (!charged.success()) {
                reopen(listing, EstateListing.Status.SOLD);
                done.accept(new Outcome(Result.PAYMENT_FAILED, listing, null, charged, 0));
                return;
            }
            UUID owner = listing.owner();
            String ownerName = listing.ownerName();
            List<ClaimBridge.ClaimInfo> moved = new ArrayList<>();
            each(listing.world(), listing.chunks(), chunk -> chunk, chunk -> {
                ClaimBridge.ClaimInfo info = claims.claimAt(listing.world(), chunk.x(), chunk.z());
                if (info == null || !owner.equals(info.owner())) return false;
                if (!claims.transfer(info.id(), buyer, buyerName)) return false;
                moved.add(info);
                return true;
            }, failed -> {
                if (failed >= 0) {
                    logger.warning("Estate sale " + listing.id() + ": the claim at chunk " + listing.chunks().get(failed) + " in "
                            + listing.world() + " could not be transferred to " + buyerName + "; undoing the sale.");
                    undoSale(listing, buyer, owner, ownerName, moved, currency, note, () ->
                            done.accept(Outcome.of(Result.CLAIM_FAILED, listing)));
                    return;
                }
                platform.io(() -> {
                    long net = listing.price() - tax;
                    TransactionResult credited = net > 0 ? bank.credit(owner, currency, net, TransactionKind.ESTATE_SALE, note) : charged;
                    if (!credited.success()) {
                        logger.warning("Estate sale " + listing.id() + ": the owner could not be paid (" + credited.status() + "); undoing the sale.");
                        undoSale(listing, buyer, owner, ownerName, moved, currency, note, () ->
                                done.accept(new Outcome(Result.PAYMENT_FAILED, listing, null, credited, 0)));
                        return;
                    }
                    Map<String, String> values = values(listing, currency);
                    values.put("{net}", CurrencyFormat.format(currency, net));
                    values.put("{buyer}", name(buyerName, buyer));
                    platform.notify(owner, "estate.sold-notice", values, true);
                    done.accept(Outcome.of(Result.OK, listing));
                });
            });
        });
    }

    /** Transfers moved claims back to the owner, refunds the buyer and cancels the listing, then runs {@code after}. */
    private void undoSale(EstateListing listing, UUID buyer, UUID owner, String ownerName, List<ClaimBridge.ClaimInfo> moved,
                          Currency currency, String note, Runnable after) {
        each(listing.world(), moved, ClaimBridge.ClaimInfo::chunk, info -> {
            if (!claims.transfer(info.id(), owner, ownerName))
                logger.severe("Estate sale " + listing.id() + ": claim " + info.id() + " at chunk " + info.chunk() + " in " + info.world()
                        + " could not be given back to " + ownerName + "; transfer it by hand.");
            return true;
        }, ignored -> platform.io(() -> {
            TransactionResult refunded = bank.refund(buyer, currency, listing.price(), note + " refund");
            if (!refunded.success())
                logger.severe("Estate sale " + listing.id() + ": refunding " + buyer + " failed (" + refunded.status() + "); refund "
                        + CurrencyFormat.format(currency, listing.price()) + " by hand.");
            close(listing, EstateListing.Status.SOLD, EstateListing.Status.CANCELLED);
            platform.notify(owner, "estate.listing-failed-notice", values(listing, currency), true);
            after.run();
        }));
    }

    // ---- leasing ------------------------------------------------------------------------------------------------

    /**
     * Rents a listing: the tenant pays the first period, every claim gets timed trust for the tenant (until the end
     * of the period plus the grace time), then the owner receives the rent minus tax. On any failure the trust is
     * taken back, the tenant refunded and the listing cancelled.
     */
    public void lease(String id, UUID tenant, String tenantName, Consumer<Outcome> done) {
        EstateListing listing = get(id);
        Result problem = checkTake(listing, tenant, EstateListing.Type.RENT);
        if (problem != Result.OK) {
            done.accept(Outcome.of(problem, listing));
            return;
        }
        Currency currency = currencies.get().get(listing.currency());
        if (!platform.callLeaseStart(listing, tenant)) {
            done.accept(Outcome.of(Result.CANCELLED, listing));
            return;
        }
        if (!flip(listing, EstateListing.Status.ACTIVE, EstateListing.Status.LEASED)) {
            done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
            return;
        }
        platform.io(() -> {
            if (!storeFlip(listing, EstateListing.Status.ACTIVE, EstateListing.Status.LEASED, done)) return;
            String note = "estate lease " + listing.id();
            long tax = Math.min(listing.tax(), listing.price());
            TransactionResult charged = bank.charge(tenant, currency, listing.price(), tax, TransactionKind.ESTATE_RENT, note);
            if (!charged.success()) {
                reopen(listing, EstateListing.Status.LEASED);
                done.accept(new Outcome(Result.PAYMENT_FAILED, listing, null, charged, 0));
                return;
            }
            long now = clock.getAsLong();
            Lease lease = new Lease(newId(), listing.id(), listing.owner(), listing.ownerName(), tenant, tenantName, listing.world(),
                    listing.chunks(), listing.price(), listing.currency(), listing.days(), now, now + listing.days() * DAY, true, false,
                    listing.taxBasisPoints(), Lease.Status.ACTIVE);
            Set<String> rights = settings.rights();
            long trustUntil = lease.paidUntil() + settings.graceMillis();
            List<ClaimBridge.ClaimInfo> trusted = new ArrayList<>();
            each(listing.world(), listing.chunks(), chunk -> chunk, chunk -> {
                ClaimBridge.ClaimInfo info = claims.claimAt(listing.world(), chunk.x(), chunk.z());
                if (info == null || !listing.owner().equals(info.owner())) return false;
                if (!claims.setTrust(info.id(), tenant, rights, trustUntil)) return false;
                trusted.add(info);
                return true;
            }, failed -> {
                if (failed >= 0) {
                    logger.warning("Estate lease of " + listing.id() + ": the claim at chunk " + listing.chunks().get(failed) + " in "
                            + listing.world() + " could not trust " + tenantName + "; undoing the lease.");
                    undoLease(listing, null, tenant, trusted, currency, note, () -> done.accept(Outcome.of(Result.CLAIM_FAILED, listing)));
                    return;
                }
                platform.io(() -> {
                    try {
                        storage.insertLease(lease);
                    } catch (Exception failure) {
                        logger.log(Level.WARNING, "Could not store the lease of estate listing " + listing.id() + "; undoing it", failure);
                        undoLease(listing, null, tenant, trusted, currency, note, () -> done.accept(Outcome.of(Result.STORAGE_ERROR, listing)));
                        return;
                    }
                    long net = listing.price() - tax;
                    TransactionResult credited = net > 0 ? bank.credit(listing.owner(), currency, net, TransactionKind.ESTATE_RENT, note) : charged;
                    if (!credited.success()) {
                        logger.warning("Estate lease of " + listing.id() + ": the owner could not be paid (" + credited.status() + "); undoing it.");
                        undoLease(listing, lease, tenant, trusted, currency, note, () ->
                                done.accept(new Outcome(Result.PAYMENT_FAILED, listing, null, credited, 0)));
                        return;
                    }
                    leases.put(lease.id(), lease);
                    Map<String, String> values = values(lease, currency);
                    values.put("{net}", CurrencyFormat.format(currency, net));
                    platform.notify(listing.owner(), "estate.leased-notice", values, true);
                    done.accept(new Outcome(Result.OK, listing, lease, null, listing.price()));
                });
            });
        });
    }

    /** Takes trust back, refunds the tenant, ends the stored lease (when there is one) and cancels the listing. */
    private void undoLease(EstateListing listing, Lease stored, UUID tenant, List<ClaimBridge.ClaimInfo> trusted, Currency currency,
                           String note, Runnable after) {
        each(listing.world(), trusted, ClaimBridge.ClaimInfo::chunk, info -> {
            claims.removeTrust(info.id(), tenant);
            return true;
        }, ignored -> platform.io(() -> {
            TransactionResult refunded = bank.refund(tenant, currency, listing.price(), note + " refund");
            if (!refunded.success())
                logger.severe("Estate lease of " + listing.id() + ": refunding " + tenant + " failed (" + refunded.status() + "); refund "
                        + CurrencyFormat.format(currency, listing.price()) + " by hand.");
            if (stored != null) {
                synchronized (stored) {
                    stored.status(Lease.Status.ENDED);
                }
                persist(stored);
            }
            close(listing, EstateListing.Status.LEASED, EstateListing.Status.CANCELLED);
            platform.notify(listing.owner(), "estate.listing-failed-notice", values(listing, currency), true);
            after.run();
        }));
    }

    private Result checkTake(EstateListing listing, UUID player, EstateListing.Type type) {
        if (listing == null) return Result.NOT_FOUND;
        if (listing.type() != type) return Result.WRONG_TYPE;
        if (listing.owner().equals(player)) return Result.OWN_LISTING;
        if (currencies.get().get(listing.currency()) == null) return Result.UNKNOWN_CURRENCY;
        if (listing.status() != EstateListing.Status.ACTIVE || listing.expired(clock.getAsLong())) return Result.NOT_AVAILABLE;
        return Result.OK;
    }

    // ---- tenant and owner actions -------------------------------------------------------------------------------

    /** Turns automatic renewal on or off; {@code amount} in the outcome is 1 when it is now on. */
    public void autorenew(String leaseId, UUID tenant, Consumer<Outcome> done) {
        Lease lease = lease(leaseId);
        if (lease == null || !lease.open()) {
            done.accept(Outcome.of(Result.NO_LEASE));
            return;
        }
        if (!lease.tenant().equals(tenant)) {
            done.accept(Outcome.of(Result.NOT_TENANT, lease));
            return;
        }
        boolean on;
        synchronized (lease) {
            on = !lease.autorenew();
            lease.autorenew(on);
        }
        platform.io(() -> {
            persist(lease);
            done.accept(new Outcome(Result.OK, null, lease, null, on ? 1 : 0));
        });
    }

    /** The tenant ends the lease now, without a refund. */
    public void leave(String leaseId, UUID tenant, Consumer<Outcome> done) {
        Lease lease = lease(leaseId);
        if (lease == null || !lease.open()) {
            done.accept(Outcome.of(Result.NO_LEASE));
            return;
        }
        if (!lease.tenant().equals(tenant)) {
            done.accept(Outcome.of(Result.NOT_TENANT, lease));
            return;
        }
        if (!finish(lease, EndReason.LEFT, 0)) {
            done.accept(Outcome.of(Result.NO_LEASE, lease));
            return;
        }
        done.accept(Outcome.of(Result.OK, lease));
    }

    /**
     * The owner ends the lease when the paid period is over (no new period is charged). A lease already in grace
     * ends at once.
     */
    public void evict(String leaseId, UUID owner, Consumer<Outcome> done) {
        Lease lease = lease(leaseId);
        if (lease == null || !lease.open()) {
            done.accept(Outcome.of(Result.NO_LEASE));
            return;
        }
        if (!lease.owner().equals(owner)) {
            done.accept(Outcome.of(Result.NOT_OWNER, lease));
            return;
        }
        boolean now;
        synchronized (lease) {
            if (lease.evicted()) {
                done.accept(Outcome.of(Result.ALREADY_EVICTED, lease));
                return;
            }
            lease.evicted(true);
            now = lease.status() == Lease.Status.GRACE;
        }
        if (now) {
            finish(lease, EndReason.EVICTED, 0);
            done.accept(new Outcome(Result.OK, null, lease, null, 1));
            return;
        }
        platform.io(() -> {
            persist(lease);
            platform.notify(lease.tenant(), "estate.evict-notice", values(lease, currencies.get().get(lease.currency())), true);
            done.accept(new Outcome(Result.OK, null, lease, null, 0));
        });
    }

    /** The share of the rent a tenant gets back for the unused time of the current period. */
    public long refundFor(Lease lease, long now) {
        if (lease.status() != Lease.Status.ACTIVE) return 0;
        long period = lease.periodMillis();
        long left = Math.min(period, lease.paidUntil() - now);
        if (period <= 0 || left <= 0) return 0;
        return BigInteger.valueOf(lease.price()).multiply(BigInteger.valueOf(left)).multiply(BigInteger.valueOf(settings.refundPercent()))
                .divide(BigInteger.valueOf(period).multiply(BigInteger.valueOf(100))).longValue();
    }

    /**
     * Takes a listing off the market. The owner (or an admin with {@code admin}) may do this; a leased listing ends
     * its lease at once and the tenant gets {@code refund-on-owner-cancel-percent} of the unused time back from the
     * owner. An owner who cannot pay that refund is refused; an admin removal pays what the owner has.
     * {@code amount} in the outcome is the refund.
     */
    public void unlist(String id, UUID actor, boolean admin, Consumer<Outcome> done) {
        EstateListing listing = get(id);
        if (listing == null || !listing.status().open()) {
            done.accept(Outcome.of(Result.NOT_FOUND));
            return;
        }
        if (!admin && !listing.owner().equals(actor)) {
            done.accept(Outcome.of(Result.NOT_OWNER, listing));
            return;
        }
        EstateListing.Status target = admin && !listing.owner().equals(actor) ? EstateListing.Status.REMOVED : EstateListing.Status.CANCELLED;
        EndReason reason = target == EstateListing.Status.REMOVED ? EndReason.REMOVED : EndReason.UNLISTED;
        Lease lease = leaseOf(listing);
        Currency currency = currencies.get().get(listing.currency());
        if (lease == null) {
            EstateListing.Status from = listing.status();
            if (!flip(listing, from, target)) {
                done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
                return;
            }
            platform.io(() -> {
                if (!storeFlip(listing, from, target, done)) return;
                if (target == EstateListing.Status.REMOVED) platform.notify(listing.owner(), "estate.removed-notice", values(listing, currency), true);
                done.accept(Outcome.of(Result.OK, listing));
            });
            return;
        }
        long refund = currency == null ? 0 : refundFor(lease, clock.getAsLong());
        platform.io(() -> {
            if (!admin && refund > 0 && bank.balance(listing.owner(), currency) < refund) {
                done.accept(new Outcome(Result.REFUND_NOT_AFFORDABLE, listing, lease, null, refund));
                return;
            }
            if (!flip(listing, EstateListing.Status.LEASED, target)) {
                done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
                return;
            }
            if (!storeFlip(listing, EstateListing.Status.LEASED, target, done)) return;
            long paid = endWithRefund(lease, reason, refund, currency);
            if (target == EstateListing.Status.REMOVED) platform.notify(listing.owner(), "estate.removed-notice", values(listing, currency), true);
            done.accept(new Outcome(Result.OK, listing, lease, null, paid));
        });
    }

    /** Pays the refund from the owner (as much as the owner has) and ends the lease. IO thread. Returns what was paid. */
    private long endWithRefund(Lease lease, EndReason reason, long refund, Currency currency) {
        long paid = 0;
        if (refund > 0 && currency != null) {
            paid = bank.refundFromOwner(lease.owner(), lease.tenant(), currency, refund, "estate lease " + lease.id() + " refund");
            if (paid < refund)
                logger.warning("Estate lease " + lease.id() + ": the owner could only refund " + CurrencyFormat.format(currency, paid)
                        + " of " + CurrencyFormat.format(currency, refund) + " to " + lease.tenantName() + ".");
        }
        finish(lease, reason, paid);
        return paid;
    }

    // ---- claims changed by DyClaim ------------------------------------------------------------------------------

    /**
     * A claim left {@code previousOwner} (removed, or transferred outside DyMarket): every open listing and lease of
     * that owner containing the chunk ends; tenants get the unused time back at the refund percentage. Any thread.
     */
    public void claimLost(String world, int x, int z, UUID previousOwner) {
        List<EstateListing> affected = new ArrayList<>();
        for (EstateListing listing : listings.values())
            if (listing.status().open() && listing.owner().equals(previousOwner) && listing.contains(world, x, z)) affected.add(listing);
        Set<String> handled = new HashSet<>();
        for (EstateListing listing : affected) {
            Lease lease = leaseOf(listing);
            EstateListing.Status from = listing.status();
            if (!flip(listing, from, EstateListing.Status.CANCELLED)) continue;
            if (lease != null) handled.add(lease.id());
            Currency currency = currencies.get().get(listing.currency());
            long refund = lease == null || currency == null ? 0 : refundFor(lease, clock.getAsLong());
            platform.io(() -> {
                close(listing, from, EstateListing.Status.CANCELLED);
                platform.notify(listing.owner(), "estate.listing-ended-claim", values(listing, currency), true);
                if (lease != null) endWithRefund(lease, EndReason.CLAIM_LOST, refund, currency);
            });
        }
        for (Lease lease : leases.values()) {
            if (!lease.open() || handled.contains(lease.id()) || !lease.owner().equals(previousOwner) || !lease.contains(world, x, z)) continue;
            Currency currency = currencies.get().get(lease.currency());
            long refund = currency == null ? 0 : refundFor(lease, clock.getAsLong());
            platform.io(() -> endWithRefund(lease, EndReason.CLAIM_LOST, refund, currency));
        }
    }

    // ---- renewals, grace and expiry -----------------------------------------------------------------------------

    /**
     * Expires old listings and moves leases along: a paid period that is over is renewed when auto-renew is on and
     * the tenant can pay, otherwise the lease enters grace; after the grace time (or at once for an evicted lease)
     * the trust is taken back and the lease ends. Runs on DyMarket-IO.
     */
    public void check() {
        long now = clock.getAsLong();
        for (EstateListing listing : new ArrayList<>(listings.values())) {
            if (listing.status() != EstateListing.Status.ACTIVE || !listing.expired(now)) continue;
            if (!flip(listing, EstateListing.Status.ACTIVE, EstateListing.Status.EXPIRED)) continue;
            close(listing, EstateListing.Status.ACTIVE, EstateListing.Status.EXPIRED);
            platform.notify(listing.owner(), "estate.listing-expired", values(listing, currencies.get().get(listing.currency())), true);
        }
        for (Lease lease : new ArrayList<>(leases.values())) {
            try {
                advance(lease, now);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "Estate lease " + lease.id() + " could not be checked", failure);
            }
        }
    }

    private void advance(Lease lease, long now) {
        if (!lease.open() || now < lease.paidUntil()) return;
        if (lease.evicted()) {
            finish(lease, EndReason.EVICTED, 0);
            return;
        }
        if (lease.autorenew() && renew(lease)) return;
        if (now >= lease.paidUntil() + settings.graceMillis()) {
            finish(lease, EndReason.UNPAID, 0);
            return;
        }
        boolean entered;
        synchronized (lease) {
            entered = lease.status() == Lease.Status.ACTIVE;
            if (entered) lease.status(Lease.Status.GRACE);
        }
        if (!entered) return;
        persist(lease);
        Currency currency = currencies.get().get(lease.currency());
        Map<String, String> values = values(lease, currency);
        long left = Math.max(0, lease.paidUntil() + settings.graceMillis() - now);
        values.put("{hours}", String.valueOf((left + HOUR - 1) / HOUR));
        platform.notify(lease.tenant(), lease.autorenew() ? "estate.grace-unpaid" : "estate.grace", values, true);
        platform.notify(lease.owner(), "estate.grace-owner", values, true);
    }

    /** Charges the next period; on success extends the lease and the trust. IO thread. */
    private boolean renew(Lease lease) {
        Currency currency = currencies.get().get(lease.currency());
        if (currency == null) return false;
        String note = "estate lease " + lease.id() + " renewal";
        long tax = Math.min(lease.tax(), lease.price());
        TransactionResult charged = bank.charge(lease.tenant(), currency, lease.price(), tax, TransactionKind.ESTATE_RENT, note);
        if (!charged.success()) return false;
        long net = lease.price() - tax;
        TransactionResult credited = net > 0 ? bank.credit(lease.owner(), currency, net, TransactionKind.ESTATE_RENT, note) : charged;
        if (!credited.success()) {
            bank.refund(lease.tenant(), currency, lease.price(), note + " refund");
            logger.warning("Estate lease " + lease.id() + ": the owner could not receive the rent (" + credited.status() + "); the renewal was refunded.");
            return false;
        }
        long until;
        synchronized (lease) {
            if (!lease.open()) {
                // Ended while being charged (the tenant left at that moment): the owner's share and the tax go back.
                bank.refundFromOwner(lease.owner(), lease.tenant(), currency, net, note + " refund");
                if (tax > 0) bank.refund(lease.tenant(), currency, tax, note + " refund");
                return true;
            }
            until = lease.paidUntil() + lease.periodMillis();
            lease.paidUntil(until);
            lease.status(Lease.Status.ACTIVE);
        }
        persist(lease);
        long trustUntil = until + settings.graceMillis();
        Set<String> rights = settings.rights();
        UUID tenant = lease.tenant();
        UUID owner = lease.owner();
        each(lease.world(), lease.chunks(), chunk -> chunk, chunk -> {
            ClaimBridge.ClaimInfo info = claims.claimAt(lease.world(), chunk.x(), chunk.z());
            if (info == null || !owner.equals(info.owner()) || !claims.setTrust(info.id(), tenant, rights, trustUntil))
                logger.warning("Estate lease " + lease.id() + ": could not extend the trust on chunk " + chunk + " in " + lease.world() + ".");
            return true;
        }, ignored -> { });
        Map<String, String> values = values(lease, currency);
        values.put("{net}", CurrencyFormat.format(currency, net));
        platform.notify(tenant, "estate.renewed", values, true);
        platform.notify(owner, "estate.rent-received", values, true);
        return true;
    }

    /**
     * Ends a lease once: status ENDED, the listing closes, trust is taken back on every claim of the group and
     * both parties are told. {@code refund} is what the tenant got back (for the message). Any thread.
     */
    boolean finish(Lease lease, EndReason reason, long refund) {
        synchronized (lease) {
            if (!lease.open()) return false;
            lease.status(Lease.Status.ENDED);
        }
        leases.remove(lease.id(), lease);
        EstateListing listing = listings.get(lease.listingId());
        boolean closeListing = listing != null && flip(listing, EstateListing.Status.LEASED, EstateListing.Status.ENDED);
        Currency currency = currencies.get().get(lease.currency());
        Map<String, String> values = values(lease, currency);
        values.put("{refund}", currency == null ? String.valueOf(refund) : CurrencyFormat.format(currency, refund));
        platform.io(() -> {
            persist(lease);
            if (closeListing) close(listing, EstateListing.Status.LEASED, EstateListing.Status.ENDED);
        });
        UUID tenant = lease.tenant();
        each(lease.world(), lease.chunks(), chunk -> chunk, chunk -> {
            ClaimBridge.ClaimInfo info = claims.claimAt(lease.world(), chunk.x(), chunk.z());
            if (info != null) claims.removeTrust(info.id(), tenant);
            return true;
        }, ignored -> { });
        switch (reason) {
            case LEFT -> platform.notify(lease.owner(), "estate.lease-left", values, true);
            case EVICTED -> {
                platform.notify(tenant, "estate.lease-ended-evicted", values, true);
                platform.notify(lease.owner(), "estate.lease-ended-owner", values, true);
            }
            case UNPAID -> {
                platform.notify(tenant, "estate.lease-ended-unpaid", values, true);
                platform.notify(lease.owner(), "estate.lease-ended-owner", values, true);
            }
            case UNLISTED -> platform.notify(tenant, "estate.lease-ended-unlisted", values, true);
            case CLAIM_LOST -> platform.notify(tenant, "estate.lease-ended-claim", values, true);
            case REMOVED -> platform.notify(tenant, "estate.lease-ended-removed", values, true);
        }
        platform.callLeaseEnd(lease, reason);
        return true;
    }

    // ---- state helpers ------------------------------------------------------------------------------------------

    /** {@code from} to {@code to} under the listing's lock; a listing that is no longer open leaves memory. */
    private boolean flip(EstateListing listing, EstateListing.Status from, EstateListing.Status to) {
        synchronized (listing) {
            if (listing.status() != from) return false;
            listing.status(to);
            if (!to.open()) listings.remove(listing.id(), listing);
            return true;
        }
    }

    /** Stores a flip already made in memory; on failure puts the listing back and reports. IO thread. */
    private boolean storeFlip(EstateListing listing, EstateListing.Status from, EstateListing.Status to, Consumer<Outcome> done) {
        try {
            if (storage.transitionListing(listing.id(), from, to)) return true;
            logger.warning("Estate listing " + listing.id() + " was no longer " + from + " in storage.");
            done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
            return false;
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Could not change estate listing " + listing.id() + " to " + to, failure);
            restore(listing, to, from);
            done.accept(Outcome.of(Result.STORAGE_ERROR, listing));
            return false;
        }
    }

    /** Puts a listing back on the market after a failed payment (storage and memory). IO thread. */
    private void reopen(EstateListing listing, EstateListing.Status from) {
        try {
            if (!storage.transitionListing(listing.id(), from, EstateListing.Status.ACTIVE))
                logger.warning("Estate listing " + listing.id() + " was not " + from + " any more while putting it back on the market.");
        } catch (Exception failure) {
            logger.log(Level.SEVERE, "Estate listing " + listing.id() + " could not be put back on the market in storage", failure);
            return;
        }
        restore(listing, from, EstateListing.Status.ACTIVE);
    }

    private void restore(EstateListing listing, EstateListing.Status from, EstateListing.Status to) {
        synchronized (listing) {
            if (listing.status() != from) return;
            listing.status(to);
            if (to.open()) listings.put(listing.id(), listing);
        }
    }

    /** Moves a listing to a closed status in memory (if still {@code from}) and in storage. IO thread. */
    private void close(EstateListing listing, EstateListing.Status from, EstateListing.Status to) {
        flip(listing, from, to);
        try {
            storage.transitionListing(listing.id(), from, to);
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Could not close estate listing " + listing.id(), failure);
        }
    }

    private void persist(Lease lease) {
        try {
            storage.updateLease(lease);
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Could not save estate lease " + lease.id(), failure);
        }
    }

    /**
     * Runs {@code step} for each item on the thread owning its chunk, one after another. {@code done} gets the index
     * of the first item whose step returned false (or threw), or -1 when all succeeded.
     */
    private <T> void each(String world, List<T> items, Function<T, ChunkPos> where, Predicate<T> step, IntConsumer done) {
        next(world, List.copyOf(items), 0, where, step, done);
    }

    private <T> void next(String world, List<T> items, int index, Function<T, ChunkPos> where, Predicate<T> step, IntConsumer done) {
        if (index >= items.size()) {
            done.accept(-1);
            return;
        }
        T item = items.get(index);
        ChunkPos chunk = where.apply(item);
        platform.region(world, chunk.x(), chunk.z(), () -> {
            boolean ok;
            try {
                ok = step.test(item);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "DyClaim refused a change at chunk " + chunk + " in " + world, failure);
                ok = false;
            }
            if (ok) next(world, items, index + 1, where, step, done);
            else done.accept(index);
        });
    }

    // ---- merge ------------------------------------------------------------------------------------------------

    /** After /dymarket merge moved the stored rows: listings and leases in memory follow. */
    public void merged(UUID from, UUID to, String toName) {
        for (EstateListing listing : listings.values()) if (listing.owner().equals(from)) listing.owner(to, toName);
        for (Lease lease : leases.values()) {
            if (lease.owner().equals(from)) lease.owner(to, toName);
            if (lease.tenant().equals(from)) lease.tenant(to, toName);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** {@code amount * numerator / denominator}, rounded down, without overflow. */
    public static long share(long amount, long numerator, long denominator) {
        if (denominator == 0) return 0;
        return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(numerator)).divide(BigInteger.valueOf(denominator)).longValue();
    }

    /** {id}, {world}, {chunks}, {amount} (price), {currency}, {player} (owner), {days}. */
    public Map<String, String> values(EstateListing listing, Currency currency) {
        Map<String, String> values = new HashMap<>();
        values.put("{id}", listing.id());
        values.put("{world}", listing.world());
        values.put("{chunks}", String.valueOf(listing.chunks().size()));
        values.put("{amount}", currency == null ? String.valueOf(listing.price()) : CurrencyFormat.format(currency, listing.price()));
        values.put("{currency}", currency == null ? listing.currency() : currency.plural());
        values.put("{player}", listing.ownerName());
        values.put("{days}", String.valueOf(listing.days()));
        return values;
    }

    /** {id} (listing), {lease}, {world}, {chunks}, {amount} (rent), {currency}, {player} (owner), {tenant}, {days}. */
    public Map<String, String> values(Lease lease, Currency currency) {
        Map<String, String> values = new HashMap<>();
        values.put("{id}", lease.listingId());
        values.put("{lease}", lease.id());
        values.put("{world}", lease.world());
        values.put("{chunks}", String.valueOf(lease.chunks().size()));
        values.put("{amount}", currency == null ? String.valueOf(lease.price()) : CurrencyFormat.format(currency, lease.price()));
        values.put("{currency}", currency == null ? lease.currency() : currency.plural());
        values.put("{player}", lease.ownerName());
        values.put("{tenant}", lease.tenantName());
        values.put("{days}", String.valueOf(lease.days()));
        return values;
    }

    private static String name(String name, UUID id) {
        return name == null ? id.toString().substring(0, 8) : name;
    }

    private String newId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (true) {
            char[] id = new char[6];
            for (int i = 0; i < id.length; i++) id[i] = ID_CHARS.charAt(random.nextInt(ID_CHARS.length()));
            String text = new String(id);
            if (!listings.containsKey(text) && !leases.containsKey(text)) return text;
        }
    }

    /** Open listings and leases, for tests and the API. */
    Collection<Lease> allLeases() {
        return leases.values();
    }
}
