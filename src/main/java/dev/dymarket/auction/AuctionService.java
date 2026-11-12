package dev.dymarket.auction;

import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.economy.CurrencyRegistry;
import dev.dymarket.economy.RateLimiter;
import dev.dymarket.economy.TransactionResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The auction house rules, free of server classes so they can be tested on their own.
 *
 * <p>Threads: {@link #list}, {@link #buy}, {@link #cancel} and the collection-box methods are called on the acting
 * player's own thread (the region thread on Folia). Every storage step runs through {@link Platform#io} on
 * DyMarket-IO; items are handed to players through {@link Platform#onPlayer}. A listing's status only changes while
 * holding the listing's monitor, and storage applies each change only when the stored status still matches, so
 * an item can be sold, cancelled or expired only once.
 */
public final class AuctionService {
    public enum Result {
        OK, CURRENCY_NOT_ALLOWED, UNKNOWN_CURRENCY, PRICE_TOO_LOW, PRICE_TOO_HIGH, EMPTY_HAND, INVALID_AMOUNT, BLACKLISTED,
        LORE_BLOCKED, LIMIT_REACHED, CANCELLED, STORAGE_ERROR, NOT_FOUND, NOT_AVAILABLE, OWN_LISTING, NOT_OWNER,
        RATE_LIMITED, PAYMENT_FAILED, EMPTY, INVENTORY_FULL
    }

    /** What happened; {@code payment} is set for {@code PAYMENT_FAILED}, the counts for the collection box and purge. */
    public record Outcome(Result result, Listing listing, TransactionResult payment, int count, int remaining) {
        public static Outcome of(Result result) {
            return new Outcome(result, null, null, 0, 0);
        }

        public static Outcome of(Result result, Listing listing) {
            return new Outcome(result, listing, null, 0, 0);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    /** Storage of listings and the collection box. Every method runs on the DyMarket-IO thread. */
    public interface Storage {
        List<Listing> loadActive() throws Exception;

        void insert(Listing listing) throws Exception;

        /** Changes the stored status only when it is still {@code from}; false when it was not. */
        boolean transition(String id, ListingStatus from, ListingStatus to, UUID buyer) throws Exception;

        /** In one database transaction: ACTIVE to {@code to}, and the item into {@code owner}'s collection box. */
        boolean transitionToCollect(String id, ListingStatus to, UUID owner, String reason, long timestamp) throws Exception;

        long addCollect(UUID owner, byte[] item, String reason, long timestamp) throws Exception;

        List<CollectEntry> collectBox(UUID owner) throws Exception;

        int collectCount(UUID owner) throws Exception;

        /** Deletes and returns one entry of the owner's box, or null when it is gone. */
        CollectEntry takeCollect(long id, UUID owner) throws Exception;

        /** Deletes and returns every entry of the owner's box, oldest first. */
        List<CollectEntry> takeAllCollect(UUID owner) throws Exception;
    }

    /** Money for purchases (the economy core). Called on DyMarket-IO. */
    public interface Bank {
        /** Takes the full price from the buyer; {@code tax} is the part that leaves the economy. */
        TransactionResult charge(UUID buyer, Currency currency, long price, long tax, String note);

        TransactionResult credit(UUID seller, Currency currency, long amount, String note);

        TransactionResult refund(UUID buyer, Currency currency, long amount, String note);
    }

    /** Everything that needs the server: threads, inventories, messages and API events. */
    public interface Platform {
        void io(Runnable task);

        /** Runs on the player's own thread, or {@code offline} (on any thread) when the player is not online. */
        void onPlayer(UUID player, Runnable task, Runnable offline);

        /** On the player's thread: adds the whole item, or nothing and returns false when it does not fit. */
        boolean give(UUID player, byte[] item);

        /** On the player's thread: adds the item and drops what does not fit at the player's feet. */
        void giveOrDrop(UUID player, byte[] item);

        /** Tells a player something; with {@code keep}, an offline player is told on the next join. */
        void notify(UUID player, String key, Map<String, String> values, boolean keep);

        void broadcast(String key, Map<String, String> values);

        /** Fills the listing's display data (material, name, search text) from its item bytes. */
        void describe(Listing listing);

        boolean callList(SellRequest request);

        boolean callPurchase(Listing listing, UUID buyer);

        boolean callCancel(Listing listing, UUID actor, boolean admin);

        void callExpire(Listing listing);
    }

    /** The auction settings in effect; prices are in major units of the listing currency. */
    public record Settings(long durationMillis, double taxPercent, double minPrice, double maxPrice, int maxListings,
                           String defaultCurrency, List<String> allowedCurrencies, Set<String> blacklist, List<String> loreBlocks,
                           boolean confirmPurchase, boolean broadcastSales, int buyPerMinute) {
        public static Settings defaults() {
            return new Settings(48L * 3_600_000L, 5.0, 1.0, 100_000_000.0, 7, "money", List.of("money", "credits"),
                    Set.of(), List.of(), true, false, 20);
        }
    }

    /**
     * A request to list the item in the seller's main hand. {@code take} runs on the seller's thread and removes
     * {@code amount} items from the hand, returning their bytes (null when the hand changed). {@code limit} below
     * zero means no limit. {@code preview} is the item for API events.
     */
    public record SellRequest(UUID seller, String sellerName, String currency, long price, int amount, int handAmount,
                              String material, List<String> lore, int limit, int taxBasisPoints, Object preview,
                              Supplier<byte[]> take) {
    }

    private static final String ID_CHARS = "abcdefghijkmnpqrstuvwxyz23456789";

    private final Storage storage;
    private final Bank bank;
    private final Platform platform;
    private final RateLimiter rateLimiter;
    private final LongSupplier clock;
    private final Logger logger;
    private final Supplier<CurrencyRegistry> currencies;
    private volatile Settings settings = Settings.defaults();
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();
    /** Listings taken from a hand but not yet stored, so the limit counts them. */
    private final Map<UUID, AtomicInteger> pending = new ConcurrentHashMap<>();

    public AuctionService(Storage storage, Bank bank, Platform platform, RateLimiter rateLimiter, Supplier<CurrencyRegistry> currencies,
                          LongSupplier clock, Logger logger) {
        this.storage = storage;
        this.bank = bank;
        this.platform = platform;
        this.rateLimiter = rateLimiter;
        this.currencies = currencies;
        this.clock = clock;
        this.logger = logger;
    }

    public Settings settings() { return settings; }
    public void settings(Settings settings) { this.settings = settings; }
    public long now() { return clock.getAsLong(); }

    /** Replaces the active listings in memory (start-up). Runs on DyMarket-IO. */
    public void load(List<Listing> active) {
        listings.clear();
        for (Listing listing : active) if (listing.status() == ListingStatus.ACTIVE) listings.put(listing.id(), listing);
    }

    // ---- queries (memory only, any thread) ------------------------------------------------------------------

    public Listing get(String id) {
        return id == null ? null : listings.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Listing> active() {
        return new ArrayList<>(listings.values());
    }

    public List<Listing> bySeller(UUID seller) {
        List<Listing> list = new ArrayList<>();
        for (Listing listing : listings.values()) if (listing.seller().equals(seller)) list.add(listing);
        return list;
    }

    public int activeCount() {
        return listings.size();
    }

    /** Active listings of the seller plus those being stored right now. */
    public int count(UUID seller) {
        AtomicInteger waiting = pending.get(seller);
        return bySeller(seller).size() + (waiting == null ? 0 : waiting.get());
    }

    // ---- listing ----------------------------------------------------------------------------------------------

    /** Checks a request without changing anything. */
    public Result check(SellRequest request) {
        Settings current = settings;
        if (request.material() == null || request.handAmount() <= 0) return Result.EMPTY_HAND;
        if (request.amount() <= 0 || request.amount() > request.handAmount()) return Result.INVALID_AMOUNT;
        Currency currency = currencies.get().get(request.currency());
        if (currency == null) return Result.UNKNOWN_CURRENCY;
        if (!current.allowedCurrencies().contains(currency.id())) return Result.CURRENCY_NOT_ALLOWED;
        if (request.price() < minPrice(currency)) return Result.PRICE_TOO_LOW;
        if (request.price() > maxPrice(currency)) return Result.PRICE_TOO_HIGH;
        if (current.blacklist().contains(request.material().toUpperCase(Locale.ROOT))) return Result.BLACKLISTED;
        for (String line : request.lore()) {
            String folded = line.toLowerCase(Locale.ROOT);
            for (String blocked : current.loreBlocks())
                if (!blocked.isEmpty() && folded.contains(blocked.toLowerCase(Locale.ROOT))) return Result.LORE_BLOCKED;
        }
        if (request.limit() >= 0 && count(request.seller()) >= request.limit()) return Result.LIMIT_REACHED;
        return Result.OK;
    }

    public long minPrice(Currency currency) {
        return Math.max(1, currency.toMinor(settings.minPrice()));
    }

    public long maxPrice(Currency currency) {
        return Math.min(currency.maxBalance(), currency.toMinor(settings.maxPrice()));
    }

    /**
     * Lists the item in the seller's hand. Runs on the seller's thread: the item leaves the hand before anything is
     * stored; if storing fails it is given back on the seller's thread.
     */
    public void list(SellRequest request, Consumer<Outcome> done) {
        Result problem = check(request);
        if (problem != Result.OK) {
            done.accept(Outcome.of(problem));
            return;
        }
        if (!platform.callList(request)) {
            done.accept(Outcome.of(Result.CANCELLED));
            return;
        }
        byte[] item = request.take().get();
        if (item == null) {
            done.accept(Outcome.of(Result.EMPTY_HAND));
            return;
        }
        long now = clock.getAsLong();
        Listing listing = new Listing(newId(), request.seller(), request.sellerName(), item, request.amount(), request.price(),
                request.currency(), now, now + settings.durationMillis(), request.taxBasisPoints(), ListingStatus.ACTIVE, null);
        platform.describe(listing);
        AtomicInteger waiting = pending.computeIfAbsent(request.seller(), id -> new AtomicInteger());
        waiting.incrementAndGet();
        platform.io(() -> {
            try {
                storage.insert(listing);
                listings.put(listing.id(), listing);
                waiting.decrementAndGet();
                done.accept(Outcome.of(Result.OK, listing));
            } catch (Exception failure) {
                waiting.decrementAndGet();
                logger.log(Level.WARNING, "Could not store auction listing " + listing.id() + "; the item goes back to " + listing.sellerName(), failure);
                listing.status(ListingStatus.CANCELLED);
                platform.onPlayer(request.seller(), () -> platform.giveOrDrop(request.seller(), item),
                        () -> platform.io(() -> toCollect(request.seller(), item, CollectEntry.CANCELLED, listing)));
                done.accept(Outcome.of(Result.STORAGE_ERROR, listing));
            }
        });
    }

    // ---- buying -----------------------------------------------------------------------------------------------

    /**
     * Buys a listing. The status flips to SOLD under the listing's lock; storage, the charge and the seller's
     * credit follow on DyMarket-IO. If the seller cannot be credited the buyer is refunded and the listing is
     * active again. The item is handed over on the buyer's thread, or goes to the buyer's collection box.
     */
    public void buy(String id, UUID buyer, String buyerName, boolean bypassRateLimit, Consumer<Outcome> done) {
        Listing listing = get(id);
        if (listing == null) {
            done.accept(Outcome.of(Result.NOT_FOUND));
            return;
        }
        if (listing.seller().equals(buyer)) {
            done.accept(Outcome.of(Result.OWN_LISTING, listing));
            return;
        }
        Currency currency = currencies.get().get(listing.currency());
        if (currency == null) {
            done.accept(Outcome.of(Result.UNKNOWN_CURRENCY, listing));
            return;
        }
        if (listing.status() != ListingStatus.ACTIVE || listing.expired(clock.getAsLong())) {
            done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
            return;
        }
        if (!bypassRateLimit && !rateLimiter.tryAcquire(buyer, "ah-buy", settings.buyPerMinute())) {
            done.accept(Outcome.of(Result.RATE_LIMITED, listing));
            return;
        }
        if (!platform.callPurchase(listing, buyer)) {
            done.accept(Outcome.of(Result.CANCELLED, listing));
            return;
        }
        synchronized (listing) {
            if (listing.status() != ListingStatus.ACTIVE) {
                done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
                return;
            }
            listing.status(ListingStatus.SOLD);
            listing.buyer(buyer);
            listings.remove(listing.id(), listing);
        }
        platform.io(() -> settle(listing, buyer, buyerName, currency, done));
    }

    private void settle(Listing listing, UUID buyer, String buyerName, Currency currency, Consumer<Outcome> done) {
        try {
            if (!storage.transition(listing.id(), ListingStatus.ACTIVE, ListingStatus.SOLD, buyer)) {
                // Someone else changed it in storage; it is not for sale any more.
                done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
                return;
            }
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Could not mark auction listing " + listing.id() + " as sold", failure);
            reactivate(listing, false);
            done.accept(Outcome.of(Result.STORAGE_ERROR, listing));
            return;
        }
        String note = "auction " + listing.id();
        long tax = Math.min(listing.tax(), listing.price());
        TransactionResult charged = bank.charge(buyer, currency, listing.price(), tax, note);
        if (!charged.success()) {
            reactivate(listing, true);
            done.accept(new Outcome(Result.PAYMENT_FAILED, listing, charged, 0, 0));
            return;
        }
        long net = listing.price() - tax;
        TransactionResult credited = net > 0 ? bank.credit(listing.seller(), currency, net, note) : charged;
        if (!credited.success()) {
            TransactionResult refunded = bank.refund(buyer, currency, listing.price(), note + " refund");
            if (!refunded.success())
                logger.severe("Auction " + listing.id() + ": the seller could not be credited and refunding " + buyer + " failed ("
                        + refunded.status() + "); refund " + CurrencyFormat.format(currency, listing.price()) + " by hand.");
            reactivate(listing, true);
            done.accept(new Outcome(Result.PAYMENT_FAILED, listing, credited, 0, 0));
            return;
        }
        done.accept(Outcome.of(Result.OK, listing));
        Map<String, String> values = values(listing, currency);
        values.put("{net}", CurrencyFormat.format(currency, net));
        values.put("{seller}", listing.sellerName());
        values.put("{player}", buyerName == null ? buyer.toString().substring(0, 8) : buyerName);
        platform.notify(listing.seller(), "auction.sold", values, true);
        if (settings.broadcastSales()) platform.broadcast("auction.sold-broadcast", values);
        deliver(buyer, listing, values);
    }

    /** Hands the bought item over on the buyer's thread; a full inventory or an absent buyer uses the collection box. */
    private void deliver(UUID buyer, Listing listing, Map<String, String> values) {
        platform.onPlayer(buyer, () -> {
            if (platform.give(buyer, listing.item())) return;
            platform.io(() -> {
                if (toCollect(buyer, listing.item(), CollectEntry.FULL_INVENTORY, listing))
                    platform.notify(buyer, "auction.delivered-collect", values, true);
            });
        }, () -> platform.io(() -> {
            if (toCollect(buyer, listing.item(), CollectEntry.PURCHASE, listing))
                platform.notify(buyer, "auction.delivered-collect", values, true);
        }));
    }

    /** Puts a listing back on sale (in storage too when it was already marked sold there). */
    private void reactivate(Listing listing, boolean stored) {
        if (stored) {
            try {
                if (!storage.transition(listing.id(), ListingStatus.SOLD, ListingStatus.ACTIVE, null))
                    logger.warning("Auction listing " + listing.id() + " was not marked sold any more while putting it back on sale.");
            } catch (Exception failure) {
                logger.log(Level.SEVERE, "Auction listing " + listing.id() + " could not be put back on sale in storage", failure);
                return;
            }
        }
        synchronized (listing) {
            listing.status(ListingStatus.ACTIVE);
            listing.buyer(null);
            listings.put(listing.id(), listing);
        }
    }

    // ---- cancel, remove, purge, expire ----------------------------------------------------------------------

    /**
     * The seller (or an admin) takes a listing off sale; the item goes back to the seller's inventory when they are
     * online and it fits, otherwise to the seller's collection box.
     */
    public void cancel(String id, UUID actor, boolean admin, Consumer<Outcome> done) {
        Listing listing = get(id);
        if (listing == null) {
            done.accept(Outcome.of(Result.NOT_FOUND));
            return;
        }
        if (!admin && !listing.seller().equals(actor)) {
            done.accept(Outcome.of(Result.NOT_OWNER, listing));
            return;
        }
        if (!platform.callCancel(listing, actor, admin)) {
            done.accept(Outcome.of(Result.CANCELLED, listing));
            return;
        }
        if (!flip(listing, ListingStatus.CANCELLED)) {
            done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
            return;
        }
        platform.io(() -> {
            try {
                if (!storage.transition(listing.id(), ListingStatus.ACTIVE, ListingStatus.CANCELLED, null)) {
                    done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
                    return;
                }
            } catch (Exception failure) {
                logger.log(Level.WARNING, "Could not cancel auction listing " + listing.id(), failure);
                reactivate(listing, false);
                done.accept(Outcome.of(Result.STORAGE_ERROR, listing));
                return;
            }
            done.accept(Outcome.of(Result.OK, listing));
            UUID seller = listing.seller();
            Map<String, String> values = values(listing, currencies.get().get(listing.currency()));
            Runnable toBox = () -> platform.io(() -> {
                if (toCollect(seller, listing.item(), CollectEntry.CANCELLED, listing))
                    platform.notify(seller, "auction.returned-collect", values, true);
            });
            platform.onPlayer(seller, () -> {
                if (!platform.give(seller, listing.item())) toBox.run();
            }, toBox);
        });
    }

    /** Admin removal: the item goes to the seller's collection box and the seller is told. */
    public void remove(String id, Consumer<Outcome> done) {
        Listing listing = get(id);
        if (listing == null) {
            done.accept(Outcome.of(Result.NOT_FOUND));
            return;
        }
        if (!flip(listing, ListingStatus.REMOVED)) {
            done.accept(Outcome.of(Result.NOT_AVAILABLE, listing));
            return;
        }
        platform.io(() -> done.accept(Outcome.of(retire(listing, ListingStatus.REMOVED, CollectEntry.REMOVED, "auction.removed-notice")
                ? Result.OK : Result.STORAGE_ERROR, listing)));
    }

    /** Admin removal of every active listing of a seller; {@code count} in the outcome says how many. */
    public void purge(UUID seller, Consumer<Outcome> done) {
        List<Listing> flipped = new ArrayList<>();
        for (Listing listing : bySeller(seller)) if (flip(listing, ListingStatus.REMOVED)) flipped.add(listing);
        if (flipped.isEmpty()) {
            done.accept(Outcome.of(Result.EMPTY));
            return;
        }
        platform.io(() -> {
            int removed = 0;
            for (Listing listing : flipped)
                if (retire(listing, ListingStatus.REMOVED, CollectEntry.REMOVED, "auction.removed-notice")) removed++;
            done.accept(new Outcome(Result.OK, null, null, removed, flipped.size() - removed));
        });
    }

    /** Moves every listing past its expiry time to the seller's collection box. Runs on DyMarket-IO. */
    public int sweep() {
        long now = clock.getAsLong();
        int expired = 0;
        for (Listing listing : active()) {
            if (!listing.expired(now) || !flip(listing, ListingStatus.EXPIRED)) continue;
            if (retire(listing, ListingStatus.EXPIRED, CollectEntry.EXPIRED, "auction.expired")) {
                expired++;
                platform.callExpire(listing);
            }
        }
        return expired;
    }

    /** ACTIVE to {@code to} under the listing's lock, and out of the active list. */
    private boolean flip(Listing listing, ListingStatus to) {
        synchronized (listing) {
            if (listing.status() != ListingStatus.ACTIVE) return false;
            listing.status(to);
            listings.remove(listing.id(), listing);
            return true;
        }
    }

    /** Stores the change and the collection-box entry together; on failure the listing is active again. IO thread. */
    private boolean retire(Listing listing, ListingStatus status, String reason, String noticeKey) {
        try {
            if (!storage.transitionToCollect(listing.id(), status, listing.seller(), reason, clock.getAsLong())) {
                logger.warning("Auction listing " + listing.id() + " was no longer active in storage.");
                return false;
            }
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Could not move auction listing " + listing.id() + " to the collection box", failure);
            reactivate(listing, false);
            return false;
        }
        platform.notify(listing.seller(), noticeKey, values(listing, currencies.get().get(listing.currency())), true);
        return true;
    }

    /** Adds an item to a collection box; logs loudly when even that fails, naming the listing that still holds it. */
    private boolean toCollect(UUID owner, byte[] item, String reason, Listing listing) {
        try {
            storage.addCollect(owner, item, reason, clock.getAsLong());
            return true;
        } catch (Exception failure) {
            logger.log(Level.SEVERE, "Could not put the item of auction listing " + listing.id() + " into the collection box of " + owner
                    + "; the item is still stored with that listing", failure);
            return false;
        }
    }

    // ---- collection box ---------------------------------------------------------------------------------------

    /** Reads a collection box on DyMarket-IO and hands it to {@code done} there (null when storage failed). */
    public void collectBox(UUID owner, Consumer<List<CollectEntry>> done) {
        platform.io(() -> {
            try {
                done.accept(storage.collectBox(owner));
            } catch (Exception failure) {
                logger.log(Level.WARNING, "Could not read a collection box", failure);
                done.accept(null);
            }
        });
    }

    public void collectCount(UUID owner, Consumer<Integer> done) {
        platform.io(() -> {
            try {
                done.accept(storage.collectCount(owner));
            } catch (Exception failure) {
                logger.log(Level.WARNING, "Could not count a collection box", failure);
                done.accept(0);
            }
        });
    }

    /** Takes one entry of the player's box into their inventory; it stays in the box when it does not fit. */
    public void collect(UUID player, long entryId, Consumer<Outcome> done) {
        platform.io(() -> {
            CollectEntry entry;
            try {
                entry = storage.takeCollect(entryId, player);
            } catch (Exception failure) {
                logger.log(Level.WARNING, "Could not take from a collection box", failure);
                done.accept(Outcome.of(Result.STORAGE_ERROR));
                return;
            }
            if (entry == null) {
                done.accept(Outcome.of(Result.NOT_FOUND));
                return;
            }
            platform.onPlayer(player, () -> {
                if (platform.give(player, entry.item())) {
                    done.accept(new Outcome(Result.OK, null, null, 1, 0));
                    return;
                }
                platform.io(() -> putBack(entry));
                done.accept(new Outcome(Result.INVENTORY_FULL, null, null, 0, 1));
            }, () -> platform.io(() -> putBack(entry)));
        });
    }

    /** Takes as much of the player's box as fits; the rest goes back. {@code count} given, {@code remaining} kept. */
    public void collectAll(UUID player, Consumer<Outcome> done) {
        platform.io(() -> {
            List<CollectEntry> entries;
            try {
                entries = storage.takeAllCollect(player);
            } catch (Exception failure) {
                logger.log(Level.WARNING, "Could not take from a collection box", failure);
                done.accept(Outcome.of(Result.STORAGE_ERROR));
                return;
            }
            if (entries.isEmpty()) {
                done.accept(Outcome.of(Result.EMPTY));
                return;
            }
            platform.onPlayer(player, () -> {
                int given = 0;
                List<CollectEntry> left = new ArrayList<>();
                for (CollectEntry entry : entries) {
                    if (platform.give(player, entry.item())) given++;
                    else left.add(entry);
                }
                if (!left.isEmpty()) platform.io(() -> left.forEach(this::putBack));
                done.accept(new Outcome(given > 0 ? Result.OK : Result.INVENTORY_FULL, null, null, given, left.size()));
            }, () -> platform.io(() -> entries.forEach(this::putBack)));
        });
    }

    private void putBack(CollectEntry entry) {
        try {
            storage.addCollect(entry.owner(), entry.item(), entry.reason(), entry.timestamp());
        } catch (Exception failure) {
            logger.log(Level.SEVERE, "Could not put an item back into the collection box of " + entry.owner(), failure);
        }
    }

    // ---- merge ------------------------------------------------------------------------------------------------

    /** After /dymarket merge moved the stored listings: the listings in memory follow. */
    public void merged(UUID from, UUID to, String toName) {
        for (Listing listing : listings.values()) if (listing.seller().equals(from)) listing.seller(to, toName);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** {item}, {id}, {amount} (price), {currency}, {player} (seller), {count} (items). */
    public Map<String, String> values(Listing listing, Currency currency) {
        Map<String, String> values = new HashMap<>();
        values.put("{item}", label(listing));
        values.put("{id}", listing.id());
        values.put("{amount}", currency == null ? String.valueOf(listing.price()) : CurrencyFormat.format(currency, listing.price()));
        values.put("{currency}", currency == null ? listing.currency() : currency.plural());
        values.put("{player}", listing.sellerName());
        values.put("{count}", String.valueOf(listing.amount()));
        return values;
    }

    /** "Diamond Sword" or "Oak Log x32". */
    public static String label(Listing listing) {
        return listing.amount() > 1 ? listing.itemName() + " x" + listing.amount() : listing.itemName();
    }

    private String newId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (true) {
            char[] id = new char[6];
            for (int i = 0; i < id.length; i++) id[i] = ID_CHARS.charAt(random.nextInt(ID_CHARS.length()));
            String text = new String(id);
            if (!listings.containsKey(text)) return text;
        }
    }
}
