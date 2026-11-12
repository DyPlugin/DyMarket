package dev.dymarket.auction;

import dev.dymarket.auction.AuctionService.Outcome;
import dev.dymarket.auction.AuctionService.Result;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyRegistry;
import dev.dymarket.economy.RateLimiter;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.economy.TransactionResult.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The auction state machine with storage, money, threads and inventories faked in memory. */
class AuctionServiceTest {
    static final Currency MONEY = new Currency("money", "Money", "Money", "$", false, 2, Locale.US, 0, 100_000_000, true, true, "internal");
    static final Currency CREDITS = new Currency("credits", "Credit", "Credits", "C", true, 0, Locale.US, 0, 1_000_000, true, false, "internal");
    static final Currency GEMS = new Currency("gems", "Gem", "Gems", "G", true, 0, Locale.US, 0, 1_000_000, true, false, "internal");

    /** Listings and collection boxes in maps; {@code failInsert} and {@code failCollect} simulate a broken disk. */
    static final class FakeStore implements AuctionService.Storage {
        final Map<String, ListingStatus> status = new HashMap<>();
        final Map<String, byte[]> items = new HashMap<>();
        final Map<String, UUID> buyers = new HashMap<>();
        final Map<Long, CollectEntry> collect = new LinkedHashMap<>();
        long nextId = 1;
        boolean failInsert, failCollect;

        @Override public List<Listing> loadActive() { return List.of(); }

        @Override public void insert(Listing listing) throws Exception {
            if (failInsert) throw new java.sql.SQLException("disk full");
            status.put(listing.id(), listing.status());
            items.put(listing.id(), listing.item());
        }

        @Override public boolean transition(String id, ListingStatus from, ListingStatus to, UUID buyer) {
            if (status.get(id) != from) return false;
            status.put(id, to);
            buyers.put(id, buyer);
            return true;
        }

        @Override public boolean transitionToCollect(String id, ListingStatus to, UUID owner, String reason, long timestamp) throws Exception {
            if (failCollect) throw new java.sql.SQLException("disk full");
            if (!transition(id, ListingStatus.ACTIVE, to, null)) return false;
            addCollect(owner, items.get(id), reason, timestamp);
            return true;
        }

        @Override public long addCollect(UUID owner, byte[] item, String reason, long timestamp) throws Exception {
            if (failCollect) throw new java.sql.SQLException("disk full");
            long id = nextId++;
            collect.put(id, new CollectEntry(id, owner, item, reason, timestamp));
            return id;
        }

        @Override public List<CollectEntry> collectBox(UUID owner) {
            return collect.values().stream().filter(entry -> entry.owner().equals(owner)).toList();
        }

        @Override public int collectCount(UUID owner) { return collectBox(owner).size(); }

        @Override public CollectEntry takeCollect(long id, UUID owner) {
            CollectEntry entry = collect.get(id);
            if (entry == null || !entry.owner().equals(owner)) return null;
            return collect.remove(id);
        }

        @Override public List<CollectEntry> takeAllCollect(UUID owner) {
            List<CollectEntry> entries = collectBox(owner);
            entries.forEach(entry -> collect.remove(entry.id()));
            return entries;
        }

        List<String> reasons(UUID owner) {
            return collectBox(owner).stream().map(CollectEntry::reason).toList();
        }
    }

    /** Balances per player; {@code failCredit} makes paying the seller fail after the buyer was charged. */
    static final class FakeBank implements AuctionService.Bank {
        final Map<UUID, Long> balances = new HashMap<>();
        long taxSink;
        boolean failCredit;

        long balance(UUID id) { return balances.getOrDefault(id, 0L); }

        @Override public TransactionResult charge(UUID buyer, Currency currency, long price, long tax, String note) {
            if (balance(buyer) < price) return TransactionResult.of(Status.INSUFFICIENT_FUNDS, balance(buyer));
            balances.put(buyer, balance(buyer) - price);
            taxSink += tax;
            return TransactionResult.success(balance(buyer), List.of());
        }

        @Override public TransactionResult credit(UUID seller, Currency currency, long amount, String note) {
            if (failCredit) return TransactionResult.of(Status.MAX_BALANCE, balance(seller));
            balances.put(seller, balance(seller) + amount);
            return TransactionResult.success(balance(seller), List.of());
        }

        @Override public TransactionResult refund(UUID buyer, Currency currency, long amount, String note) {
            balances.put(buyer, balance(buyer) + amount);
            return TransactionResult.success(balance(buyer), List.of());
        }
    }

    /** Runs everything at once on the test thread; players in {@code full} have no inventory space. */
    static final class FakePlatform implements AuctionService.Platform {
        final Set<UUID> online = new HashSet<>();
        final Set<UUID> full = new HashSet<>();
        final Map<UUID, List<String>> inventories = new HashMap<>();
        final List<String> notices = new ArrayList<>();
        final List<String> broadcasts = new ArrayList<>();
        final List<Listing> expired = new ArrayList<>();
        boolean allowList = true, allowPurchase = true, allowCancel = true;

        @Override public void io(Runnable task) { task.run(); }

        @Override public void onPlayer(UUID player, Runnable task, Runnable offline) {
            if (online.contains(player)) task.run();
            else offline.run();
        }

        @Override public boolean give(UUID player, byte[] item) {
            if (full.contains(player)) return false;
            inventories.computeIfAbsent(player, id -> new ArrayList<>()).add(new String(item));
            return true;
        }

        @Override public void giveOrDrop(UUID player, byte[] item) {
            inventories.computeIfAbsent(player, id -> new ArrayList<>()).add(new String(item));
        }

        @Override public void notify(UUID player, String key, Map<String, String> values, boolean keep) {
            notices.add(player + " " + key);
        }

        @Override public void broadcast(String key, Map<String, String> values) { broadcasts.add(key + " " + values.get("{player}")); }

        @Override public void describe(Listing listing) {
            String name = new String(listing.item());
            listing.describe(name, name, name.toLowerCase(Locale.ROOT));
        }

        @Override public boolean callList(AuctionService.SellRequest request) { return allowList; }
        @Override public boolean callPurchase(Listing listing, UUID buyer) { return allowPurchase; }
        @Override public boolean callCancel(Listing listing, UUID actor, boolean admin) { return allowCancel; }
        @Override public void callExpire(Listing listing) { expired.add(listing); }

        List<String> inventory(UUID player) { return inventories.getOrDefault(player, List.of()); }
    }

    final FakeStore store = new FakeStore();
    final FakeBank bank = new FakeBank();
    final FakePlatform platform = new FakePlatform();
    final AtomicLong clock = new AtomicLong(1_000_000L);
    final AuctionService service = new AuctionService(store, bank, platform, new RateLimiter(clock::get),
            () -> new CurrencyRegistry(List.of(MONEY, CREDITS, GEMS)), clock::get, Logger.getLogger("test"));
    final UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID(), other = UUID.randomUUID();
    /** Items handed out by the fake hand, so tests can tell whether the hand was emptied. */
    final AtomicInteger taken = new AtomicInteger();

    @BeforeEach void setUp() {
        platform.online.addAll(List.of(seller, buyer, other));
        bank.balances.put(buyer, 100_000L);
        bank.balances.put(other, 100_000L);
    }

    private AuctionService.SellRequest request(String material, long price, int limit) {
        return request(material, price, limit, "money", List.of());
    }

    private AuctionService.SellRequest request(String material, long price, int limit, String currency, List<String> lore) {
        return new AuctionService.SellRequest(seller, "Seller", currency, price, 1, 1, material, lore, limit, 500, null, () -> {
            taken.incrementAndGet();
            return material.getBytes();
        });
    }

    private Outcome run(java.util.function.Consumer<java.util.function.Consumer<Outcome>> action) {
        AtomicReference<Outcome> result = new AtomicReference<>();
        action.accept(result::set);
        assertNotNull(result.get(), "the callback ran");
        return result.get();
    }

    private Listing list(String material, long price) {
        Outcome outcome = run(done -> service.list(request(material, price, -1), done));
        assertEquals(Result.OK, outcome.result());
        return outcome.listing();
    }

    // ---- listing ------------------------------------------------------------------------------------------------

    @Test void listingTakesTheItemAndStoresIt() {
        Listing listing = list("DIAMOND", 1_000);
        assertEquals(1, taken.get());
        assertEquals(ListingStatus.ACTIVE, store.status.get(listing.id()));
        assertSame(listing, service.get(listing.id()));
        assertSame(listing, service.get(listing.id().toUpperCase(Locale.ROOT)), "ids ignore case");
        assertEquals(clock.get() + service.settings().durationMillis(), listing.expires());
        assertEquals(500, listing.taxBasisPoints());
        assertEquals(1, service.count(seller));
    }

    @Test void limitBlacklistLoreCurrencyAndPriceRefusalsKeepTheItem() {
        service.settings(new AuctionService.Settings(3_600_000L, 5, 1, 1000, 7, "money", List.of("money", "credits"),
                Set.of("BEDROCK"), List.of("soulbound"), true, false, 20));
        list("DIAMOND", 1_000);
        taken.set(0);
        assertEquals(Result.LIMIT_REACHED, run(done -> service.list(request("DIAMOND", 1_000, 1), done)).result());
        assertEquals(Result.BLACKLISTED, run(done -> service.list(request("bedrock", 1_000, -1), done)).result());
        assertEquals(Result.LORE_BLOCKED, run(done -> service.list(request("STICK", 1_000, -1, "money", List.of("Kept on death: SOULBOUND")), done)).result());
        assertEquals(Result.CURRENCY_NOT_ALLOWED, run(done -> service.list(request("STICK", 10, -1, "gems", List.of()), done)).result());
        assertEquals(Result.UNKNOWN_CURRENCY, run(done -> service.list(request("STICK", 10, -1, "coins", List.of()), done)).result());
        assertEquals(Result.PRICE_TOO_LOW, run(done -> service.list(request("STICK", 50, -1), done)).result(), "min 1.00 is 100 minor");
        assertEquals(Result.PRICE_TOO_HIGH, run(done -> service.list(request("STICK", 100_001, -1), done)).result(), "max 1000.00");
        assertEquals(0, taken.get(), "no refused request touched the hand");
        assertEquals(1, service.activeCount());
    }

    @Test void aCancelledListEventKeepsTheItem() {
        platform.allowList = false;
        assertEquals(Result.CANCELLED, run(done -> service.list(request("DIAMOND", 1_000, -1), done)).result());
        assertEquals(0, taken.get());
    }

    @Test void aStorageFailureGivesTheItemBack() {
        store.failInsert = true;
        Outcome outcome = run(done -> service.list(request("DIAMOND", 1_000, -1), done));
        assertEquals(Result.STORAGE_ERROR, outcome.result());
        assertEquals(List.of("DIAMOND"), platform.inventory(seller));
        assertEquals(0, service.activeCount());
        assertEquals(0, service.count(seller));
    }

    // ---- buying -------------------------------------------------------------------------------------------------

    @Test void buyingPaysTheSellerMinusTaxAndDeliversTheItem() {
        Listing listing = list("DIAMOND", 1_000);
        Outcome outcome = run(done -> service.buy(listing.id(), buyer, "Buyer", false, done));
        assertEquals(Result.OK, outcome.result());
        assertEquals(99_000, bank.balance(buyer));
        assertEquals(950, bank.balance(seller), "5% tax");
        assertEquals(50, bank.taxSink, "the tax leaves the economy");
        assertEquals(ListingStatus.SOLD, store.status.get(listing.id()));
        assertEquals(buyer, store.buyers.get(listing.id()));
        assertEquals(ListingStatus.SOLD, listing.status());
        assertEquals(List.of("DIAMOND"), platform.inventory(buyer));
        assertTrue(platform.notices.contains(seller + " auction.sold"));
        assertNull(service.get(listing.id()));
        assertTrue(platform.broadcasts.isEmpty(), "broadcast-sales is off by default");
    }

    @Test void buyingASoldListingFails() {
        Listing listing = list("DIAMOND", 1_000);
        assertEquals(Result.OK, run(done -> service.buy(listing.id(), buyer, "Buyer", false, done)).result());
        Outcome second = run(done -> service.buy(listing.id(), other, "Other", false, done));
        assertEquals(Result.NOT_FOUND, second.result());
        assertEquals(100_000, bank.balance(other), "the second buyer paid nothing");
        assertEquals(List.of("DIAMOND"), platform.inventory(buyer));
        assertTrue(platform.inventory(other).isEmpty());
    }

    @Test void storageHasTheFinalWordOnASale() {
        Listing listing = list("DIAMOND", 1_000);
        store.status.put(listing.id(), ListingStatus.SOLD);
        assertEquals(Result.NOT_AVAILABLE, run(done -> service.buy(listing.id(), buyer, "Buyer", false, done)).result());
        assertEquals(100_000, bank.balance(buyer), "nothing charged");
        assertTrue(platform.inventory(buyer).isEmpty());
    }

    @Test void aFailedSellerCreditRefundsTheBuyerAndKeepsTheListing() {
        Listing listing = list("DIAMOND", 1_000);
        bank.failCredit = true;
        Outcome outcome = run(done -> service.buy(listing.id(), buyer, "Buyer", false, done));
        assertEquals(Result.PAYMENT_FAILED, outcome.result());
        assertEquals(Status.MAX_BALANCE, outcome.payment().status());
        assertEquals(100_000, bank.balance(buyer), "refunded");
        assertEquals(0, bank.balance(seller));
        assertEquals(ListingStatus.ACTIVE, listing.status());
        assertEquals(ListingStatus.ACTIVE, store.status.get(listing.id()));
        assertSame(listing, service.get(listing.id()), "back on sale");
        assertTrue(platform.inventory(buyer).isEmpty(), "no item without payment");
        bank.failCredit = false;
        assertEquals(Result.OK, run(done -> service.buy(listing.id(), buyer, "Buyer", false, done)).result());
    }

    @Test void notEnoughMoneyKeepsTheListing() {
        Listing listing = list("DIAMOND", 1_000);
        bank.balances.put(buyer, 999L);
        Outcome outcome = run(done -> service.buy(listing.id(), buyer, "Buyer", false, done));
        assertEquals(Result.PAYMENT_FAILED, outcome.result());
        assertEquals(Status.INSUFFICIENT_FUNDS, outcome.payment().status());
        assertEquals(ListingStatus.ACTIVE, store.status.get(listing.id()));
        assertSame(listing, service.get(listing.id()));
    }

    @Test void aFullInventorySendsThePurchaseToTheCollectionBox() {
        Listing listing = list("DIAMOND", 1_000);
        platform.full.add(buyer);
        assertEquals(Result.OK, run(done -> service.buy(listing.id(), buyer, "Buyer", false, done)).result());
        assertTrue(platform.inventory(buyer).isEmpty());
        assertEquals(List.of(CollectEntry.FULL_INVENTORY), store.reasons(buyer));
        assertTrue(platform.notices.contains(buyer + " auction.delivered-collect"));
    }

    @Test void aBuyerWhoLeftGetsThePurchaseInTheCollectionBox() {
        Listing listing = list("DIAMOND", 1_000);
        platform.online.remove(buyer);
        assertEquals(Result.OK, run(done -> service.buy(listing.id(), buyer, "Buyer", false, done)).result());
        assertEquals(List.of(CollectEntry.PURCHASE), store.reasons(buyer));
    }

    @Test void ownListingsRateLimitsAndCancelledPurchases() {
        Listing listing = list("DIAMOND", 1_000);
        assertEquals(Result.OWN_LISTING, run(done -> service.buy(listing.id(), seller, "Seller", false, done)).result());
        platform.allowPurchase = false;
        assertEquals(Result.CANCELLED, run(done -> service.buy(listing.id(), buyer, "Buyer", false, done)).result());
        platform.allowPurchase = true;
        service.settings(new AuctionService.Settings(3_600_000L, 5, 1, 1_000_000, 7, "money", List.of("money"), Set.of(), List.of(),
                true, true, 1));
        Listing second = list("EMERALD", 1_000);
        assertEquals(Result.OK, run(done -> service.buy(listing.id(), buyer, "Buyer", false, done)).result());
        assertEquals(Result.RATE_LIMITED, run(done -> service.buy(second.id(), buyer, "Buyer", false, done)).result());
        assertEquals(Result.OK, run(done -> service.buy(second.id(), buyer, "Buyer", true, done)).result(), "bypass permission");
        assertEquals(List.of("auction.sold-broadcast Buyer", "auction.sold-broadcast Buyer"), platform.broadcasts);
    }

    // ---- cancel, remove, expire ---------------------------------------------------------------------------------

    @Test void onlyTheSellerOrAnAdminCancels() {
        Listing listing = list("DIAMOND", 1_000);
        assertEquals(Result.NOT_OWNER, run(done -> service.cancel(listing.id(), buyer, false, done)).result());
        assertEquals(Result.OK, run(done -> service.cancel(listing.id(), seller, false, done)).result());
        assertEquals(ListingStatus.CANCELLED, store.status.get(listing.id()));
        assertEquals(List.of("DIAMOND"), platform.inventory(seller));
        assertEquals(Result.NOT_FOUND, run(done -> service.buy(listing.id(), buyer, "Buyer", false, done)).result());

        Listing second = list("EMERALD", 1_000);
        platform.full.add(seller);
        assertEquals(Result.OK, run(done -> service.cancel(second.id(), other, true, done)).result(), "admin");
        assertEquals(List.of(CollectEntry.CANCELLED), store.reasons(seller), "full inventory: collection box");
    }

    @Test void removeAndPurgeUseTheCollectionBox() {
        Listing one = list("DIAMOND", 1_000);
        list("EMERALD", 1_000);
        list("GOLD_INGOT", 1_000);
        assertEquals(Result.OK, run(done -> service.remove(one.id(), done)).result());
        Outcome purged = run(done -> service.purge(seller, done));
        assertEquals(2, purged.count());
        assertEquals(List.of(CollectEntry.REMOVED, CollectEntry.REMOVED, CollectEntry.REMOVED), store.reasons(seller));
        assertEquals(0, service.activeCount());
        assertEquals(Result.EMPTY, run(done -> service.purge(seller, done)).result());
    }

    @Test void expiredListingsGoToTheCollectionBox() {
        Listing listing = list("DIAMOND", 1_000);
        clock.addAndGet(service.settings().durationMillis() - 1);
        assertEquals(0, service.sweep());
        clock.addAndGet(1);
        assertEquals(Result.NOT_AVAILABLE, run(done -> service.buy(listing.id(), buyer, "Buyer", false, done)).result(), "expired, not swept yet");
        assertEquals(1, service.sweep());
        assertEquals(ListingStatus.EXPIRED, store.status.get(listing.id()));
        assertEquals(List.of(CollectEntry.EXPIRED), store.reasons(seller));
        assertEquals(List.of(listing), platform.expired);
        assertTrue(platform.notices.contains(seller + " auction.expired"));
        assertEquals(0, service.sweep(), "only once");
        assertEquals(100_000, bank.balance(buyer));
    }

    @Test void aFailedExpiryWriteKeepsTheListingActive() {
        Listing listing = list("DIAMOND", 1_000);
        clock.addAndGet(service.settings().durationMillis());
        store.failCollect = true;
        assertEquals(0, service.sweep());
        assertEquals(ListingStatus.ACTIVE, listing.status());
        assertSame(listing, service.get(listing.id()));
        store.failCollect = false;
        assertEquals(1, service.sweep());
    }

    // ---- collection box ------------------------------------------------------------------------------------------

    @Test void collectingTakesWhatFitsAndKeepsTheRest() throws Exception {
        store.addCollect(seller, "A".getBytes(), CollectEntry.EXPIRED, 1);
        long second = store.addCollect(seller, "B".getBytes(), CollectEntry.CANCELLED, 2);
        assertEquals(Result.OK, run(done -> service.collect(seller, second, done)).result());
        assertEquals(List.of("B"), platform.inventory(seller));
        assertEquals(Result.NOT_FOUND, run(done -> service.collect(seller, second, done)).result());
        assertEquals(Result.NOT_FOUND, run(done -> service.collect(buyer, 1, done)).result(), "someone else's entry");

        platform.full.add(seller);
        Outcome full = run(done -> service.collectAll(seller, done));
        assertEquals(Result.INVENTORY_FULL, full.result());
        assertEquals(1, full.remaining());
        assertEquals(List.of(CollectEntry.EXPIRED), store.reasons(seller), "put back with its reason");

        platform.full.remove(seller);
        Outcome all = run(done -> service.collectAll(seller, done));
        assertEquals(1, all.count());
        assertEquals(List.of("B", "A"), platform.inventory(seller));
        assertEquals(Result.EMPTY, run(done -> service.collectAll(seller, done)).result());
    }

    @Test void mergeMovesListingsInMemory() {
        Listing listing = list("DIAMOND", 1_000);
        service.merged(seller, other, "Other");
        assertEquals(other, listing.seller());
        assertEquals("Other", listing.sellerName());
        assertEquals(1, service.count(other));
        assertEquals(0, service.count(seller));
    }

    @Test void taxIsRoundedDownAndLabelsShowTheAmount() {
        Listing listing = new Listing("abc", seller, "Seller", "X".getBytes(), 3, 999, "money", 0, 1, 250, ListingStatus.ACTIVE, null);
        assertEquals(24, listing.tax(), "2.5% of 999 is 24.975");
        listing.describe("OAK_LOG", "Oak Log", "oak log");
        assertEquals("Oak Log x3", AuctionService.label(listing));
        assertEquals("$9.99", service.values(listing, MONEY).get("{amount}"));
    }
}
