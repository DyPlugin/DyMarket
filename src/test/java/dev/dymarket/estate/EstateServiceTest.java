package dev.dymarket.estate;

import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyRegistry;
import dev.dymarket.economy.TransactionKind;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.economy.TransactionResult.Status;
import dev.dymarket.estate.EstateService.Outcome;
import dev.dymarket.estate.EstateService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The estate rules with storage, money, threads and DyClaim faked in memory and a clock the test moves. */
class EstateServiceTest {
    static final Currency MONEY = new Currency("money", "Money", "Money", "$", false, 2, Locale.US, 0, 100_000_000_00L, true, true, "internal");
    static final String WORLD = "world";
    static final long DAY = EstateService.DAY, HOUR = EstateService.HOUR;

    /** One claim per chunk; trust as claim id + player to expiry; {@code failTransferAt} makes the n-th transfer (1-based) fail. */
    static final class FakeClaims implements ClaimBridge {
        final Map<String, ClaimInfo> claims = new LinkedHashMap<>();
        final Map<String, Long> trust = new HashMap<>();
        final List<String> regionCalls = new ArrayList<>();
        int transfers;
        int failTransferAt = -1;

        static String key(String world, int x, int z) { return world + ":" + x + ":" + z; }

        void claim(UUID owner, String name, int x, int z) {
            claims.put(key(WORLD, x, z), new ClaimInfo(UUID.randomUUID(), WORLD, x, z, owner, name));
        }

        UUID ownerAt(int x, int z) {
            ClaimInfo info = claims.get(key(WORLD, x, z));
            return info == null ? null : info.owner();
        }

        Long trustAt(int x, int z, UUID player) {
            ClaimInfo info = claims.get(key(WORLD, x, z));
            return info == null ? null : trust.get(info.id() + "/" + player);
        }

        void remove(int x, int z) { claims.remove(key(WORLD, x, z)); }

        @Override public ClaimInfo claimAt(String world, int x, int z) { return claims.get(key(world, x, z)); }

        @Override public List<ClaimInfo> claimsOf(UUID owner) {
            return claims.values().stream().filter(info -> info.owner().equals(owner)).toList();
        }

        @Override public boolean transfer(UUID claimId, UUID newOwner, String newOwnerName) {
            transfers++;
            if (transfers == failTransferAt) return false;
            for (Map.Entry<String, ClaimInfo> entry : claims.entrySet()) {
                ClaimInfo info = entry.getValue();
                if (!info.id().equals(claimId)) continue;
                entry.setValue(new ClaimInfo(info.id(), info.world(), info.x(), info.z(), newOwner, newOwnerName));
                return true;
            }
            return false;
        }

        @Override public boolean setTrust(UUID claimId, UUID player, Set<String> rights, long expiresAt) {
            trust.put(claimId + "/" + player, expiresAt);
            return true;
        }

        @Override public boolean removeTrust(UUID claimId, UUID player) {
            return trust.remove(claimId + "/" + player) != null;
        }
    }

    static final class FakeStore implements EstateService.Storage {
        final Map<String, EstateListing.Status> listings = new HashMap<>();
        final Map<String, Lease.Status> leases = new HashMap<>();
        final Map<String, Long> paidUntil = new HashMap<>();

        @Override public List<EstateListing> loadListings() { return List.of(); }
        @Override public List<Lease> loadLeases() { return List.of(); }
        @Override public void insertListing(EstateListing listing) { listings.put(listing.id(), listing.status()); }

        @Override public boolean transitionListing(String id, EstateListing.Status from, EstateListing.Status to) {
            if (listings.get(id) != from) return false;
            listings.put(id, to);
            return true;
        }

        @Override public void insertLease(Lease lease) {
            leases.put(lease.id(), lease.status());
            paidUntil.put(lease.id(), lease.paidUntil());
        }

        @Override public void updateLease(Lease lease) {
            leases.put(lease.id(), lease.status());
            paidUntil.put(lease.id(), lease.paidUntil());
        }
    }

    static final class FakeBank implements EstateService.Bank {
        final Map<UUID, Long> balances = new HashMap<>();
        long taxSink;

        long balance(UUID id) { return balances.getOrDefault(id, 0L); }

        @Override public TransactionResult charge(UUID payer, Currency currency, long price, long tax, TransactionKind kind, String note) {
            if (balance(payer) < price) return TransactionResult.of(Status.INSUFFICIENT_FUNDS, balance(payer));
            balances.put(payer, balance(payer) - price);
            taxSink += tax;
            return TransactionResult.success(balance(payer), List.of());
        }

        @Override public TransactionResult credit(UUID receiver, Currency currency, long amount, TransactionKind kind, String note) {
            balances.put(receiver, balance(receiver) + amount);
            return TransactionResult.success(balance(receiver), List.of());
        }

        @Override public TransactionResult refund(UUID receiver, Currency currency, long amount, String note) {
            return credit(receiver, currency, amount, TransactionKind.REFUND, note);
        }

        @Override public long balance(UUID player, Currency currency) { return balance(player); }

        @Override public long refundFromOwner(UUID owner, UUID tenant, Currency currency, long amount, String note) {
            long move = Math.min(amount, balance(owner));
            if (move <= 0) return 0;
            balances.put(owner, balance(owner) - move);
            balances.put(tenant, balance(tenant) + move);
            return move;
        }
    }

    /** Runs everything at once on the test thread and records what players were told. */
    static final class FakePlatform implements EstateService.Platform {
        final Map<UUID, List<String>> notices = new HashMap<>();
        final List<EstateService.EndReason> ended = new ArrayList<>();
        int regionCalls;
        boolean allowList = true, allowSale = true;

        @Override public void io(Runnable task) { task.run(); }

        @Override public void region(String world, int chunkX, int chunkZ, Runnable task) {
            regionCalls++;
            task.run();
        }

        @Override public void notify(UUID player, String key, Map<String, String> values, boolean keep) {
            notices.computeIfAbsent(player, id -> new ArrayList<>()).add(key);
        }

        @Override public boolean callList(EstateService.ListRequest request, List<ChunkPos> chunks) { return allowList; }
        @Override public boolean callSale(EstateListing listing, UUID buyer) { return allowSale; }
        @Override public boolean callLeaseStart(EstateListing listing, UUID tenant) { return true; }
        @Override public void callLeaseEnd(Lease lease, EstateService.EndReason reason) { ended.add(reason); }

        List<String> told(UUID player) { return notices.getOrDefault(player, List.of()); }
    }

    final UUID owner = UUID.randomUUID(), buyer = UUID.randomUUID(), stranger = UUID.randomUUID();
    final AtomicLong clock = new AtomicLong(1_000_000_000L);
    FakeClaims claims;
    FakeStore store;
    FakeBank bank;
    FakePlatform platform;
    EstateService service;

    @BeforeEach void setUp() {
        claims = new FakeClaims();
        store = new FakeStore();
        bank = new FakeBank();
        platform = new FakePlatform();
        CurrencyRegistry registry = new CurrencyRegistry(List.of(MONEY));
        service = new EstateService(store, bank, platform, claims, () -> registry, clock::get, Logger.getLogger("test"));
        service.settings(new EstateService.Settings("money", List.of("money"), 64, 10.0, true, 1.0, 1_000_000.0, true, 1, 90,
                Set.of("build", "containers"), 24 * HOUR, 100, 30 * DAY));
    }

    /** A row of claims of the owner from x = 0 to count - 1 at z = 0. */
    void row(int count) {
        for (int x = 0; x < count; x++) claims.claim(owner, "Owner", x, 0);
    }

    Outcome list(UUID who, EstateListing.Type type, long price, int days, int x, boolean here) {
        AtomicReference<Outcome> result = new AtomicReference<>();
        service.list(new EstateService.ListRequest(who, who.equals(owner) ? "Owner" : "Other", WORLD, x, 0, type, "money", price, days,
                here, -1, 1000), result::set);
        return result.get();
    }

    Outcome buy(String id, UUID who) {
        AtomicReference<Outcome> result = new AtomicReference<>();
        service.buy(id, who, "Buyer", result::set);
        return result.get();
    }

    Outcome lease(String id, UUID who) {
        AtomicReference<Outcome> result = new AtomicReference<>();
        service.lease(id, who, "Tenant", result::set);
        return result.get();
    }

    Lease rentedOut(int chunks, long price, int days) {
        row(chunks);
        bank.balances.put(buyer, price * 10);
        Outcome listed = list(owner, EstateListing.Type.RENT, price, days, 0, false);
        assertEquals(Result.OK, listed.result());
        Outcome leased = lease(listed.listing().id(), buyer);
        assertEquals(Result.OK, leased.result());
        return leased.lease();
    }

    // ---- flood fill ---------------------------------------------------------------------------------------

    @Test void floodFillFollowsFourNeighboursOnly() {
        Set<ChunkPos> owned = new LinkedHashSet<>(List.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(1, 1), new ChunkPos(1, 2),
                new ChunkPos(2, 3), new ChunkPos(5, 5)));
        List<ChunkPos> group = EstateService.group(owned, new ChunkPos(0, 0), 64);
        assertEquals(new ChunkPos(0, 0), group.get(0), "starts where the player stands");
        assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(1, 1), new ChunkPos(1, 2)), Set.copyOf(group),
                "the diagonal (2,3) and the far (5,5) are not connected");
        assertTrue(EstateService.group(owned, new ChunkPos(9, 9), 64).isEmpty(), "an unowned start gives nothing");
    }

    @Test void floodFillStopsAtTheCapAndHereListsOneChunk() {
        row(10);
        claims.claim(stranger, "Other", 10, 0);
        List<ChunkPos> capped = EstateService.group(Set.copyOf(service.groupAt(owner, WORLD, 0, 0, false)), new ChunkPos(4, 0), 3);
        assertEquals(3, capped.size());
        assertEquals(new ChunkPos(4, 0), capped.get(0));
        assertEquals(10, service.groupAt(owner, WORLD, 3, 0, false).size(), "the stranger's chunk at x=10 is not part of the group");
        service.settings(new EstateService.Settings("money", List.of("money"), 4, 10.0, true, 1.0, 1_000_000.0, true, 1, 90,
                Set.of("build"), 24 * HOUR, 100, 30 * DAY));
        assertEquals(4, service.groupAt(owner, WORLD, 0, 0, false).size(), "max-chunks-per-listing caps the group");
        assertEquals(List.of(new ChunkPos(7, 0)), service.groupAt(owner, WORLD, 7, 0, true), "here lists only the current chunk");
        Outcome listed = list(owner, EstateListing.Type.SALE, 50_00, 0, 7, true);
        assertEquals(Result.OK, listed.result());
        assertEquals(List.of(new ChunkPos(7, 0)), listed.listing().chunks());
    }

    @Test void listingRefusals() {
        row(3);
        claims.claim(stranger, "Other", 20, 0);
        assertEquals(Result.NOT_IN_CLAIM, list(owner, EstateListing.Type.SALE, 50_00, 0, 50, false).result());
        assertEquals(Result.NOT_OWNER, list(owner, EstateListing.Type.SALE, 50_00, 0, 20, false).result());
        assertEquals(Result.PRICE_TOO_LOW, list(owner, EstateListing.Type.SALE, 50, 0, 0, false).result());
        assertEquals(Result.DAYS_OUT_OF_RANGE, list(owner, EstateListing.Type.RENT, 50_00, 91, 0, false).result());
        platform.allowList = false;
        assertEquals(Result.CANCELLED, list(owner, EstateListing.Type.SALE, 50_00, 0, 0, false).result());
        platform.allowList = true;
        Outcome first = list(owner, EstateListing.Type.SALE, 50_00, 0, 0, false);
        assertEquals(Result.OK, first.result());
        assertEquals(EstateListing.Status.ACTIVE, store.listings.get(first.listing().id()));
        assertEquals(Result.ALREADY_LISTED, list(owner, EstateListing.Type.RENT, 50_00, 7, 2, true).result(), "a listed chunk cannot be listed again");
    }

    @Test void leasedClaimsCannotBeListedByOwnerOrTenant() {
        Lease lease = rentedOut(3, 100_00, 7);
        assertEquals(Result.TENANT, list(buyer, EstateListing.Type.SALE, 50_00, 0, 1, false).result(), "the tenant cannot list");
        AtomicReference<Outcome> unlisted = new AtomicReference<>();
        assertNotNull(lease);
        assertEquals(Result.LEASED, list(owner, EstateListing.Type.SALE, 50_00, 0, 1, false).result(), "the owner cannot list leased claims");
        service.leave(lease.id(), buyer, unlisted::set);
        assertEquals(Result.OK, unlisted.get().result());
        assertEquals(Result.OK, list(owner, EstateListing.Type.SALE, 50_00, 0, 1, false).result(), "free again after the lease ended");
    }

    // ---- sale ---------------------------------------------------------------------------------------------

    @Test void saleTransfersEveryClaimAndPaysTheOwnerMinusTax() {
        row(5);
        bank.balances.put(buyer, 1_000_00L);
        Outcome listed = list(owner, EstateListing.Type.SALE, 500_00, 0, 2, false);
        assertEquals(5, listed.listing().chunks().size());
        assertEquals(Result.OWN_LISTING, buy(listed.listing().id(), owner).result());
        Outcome bought = buy(listed.listing().id(), buyer);
        assertEquals(Result.OK, bought.result());
        for (int x = 0; x < 5; x++) assertEquals(buyer, claims.ownerAt(x, 0), "chunk " + x);
        assertEquals(500_00L, bank.balance(buyer));
        assertEquals(450_00L, bank.balance(owner), "10% tax");
        assertEquals(50_00L, bank.taxSink);
        assertEquals(EstateListing.Status.SOLD, store.listings.get(listed.listing().id()));
        assertNull(service.get(listed.listing().id()));
        assertTrue(platform.told(owner).contains("estate.sold-notice"));
        assertEquals(Result.NOT_FOUND, buy(listed.listing().id(), stranger).result(), "sold only once");
        assertTrue(platform.regionCalls >= 5, "every transfer ran through the region scheduler");
    }

    @Test void failedThirdTransferOfFiveUndoesEverything() {
        row(5);
        bank.balances.put(buyer, 1_000_00L);
        Outcome listed = list(owner, EstateListing.Type.SALE, 500_00, 0, 0, false);
        claims.failTransferAt = 3;
        Outcome bought = buy(listed.listing().id(), buyer);
        assertEquals(Result.CLAIM_FAILED, bought.result());
        for (int x = 0; x < 5; x++) assertEquals(owner, claims.ownerAt(x, 0), "chunk " + x + " is the owner's again");
        assertEquals(1_000_00L, bank.balance(buyer), "full refund");
        assertEquals(0L, bank.balance(owner), "the owner got nothing");
        assertEquals(EstateListing.Status.CANCELLED, store.listings.get(listed.listing().id()));
        assertTrue(platform.told(owner).contains("estate.listing-failed-notice"));
        assertEquals(2 + 2 + 1, claims.transfers, "two moved, the third failed, two moved back");
    }

    @Test void saleNeedsTheMoney() {
        row(2);
        bank.balances.put(buyer, 10_00L);
        Outcome listed = list(owner, EstateListing.Type.SALE, 500_00, 0, 0, false);
        Outcome bought = buy(listed.listing().id(), buyer);
        assertEquals(Result.PAYMENT_FAILED, bought.result());
        assertEquals(Status.INSUFFICIENT_FUNDS, bought.payment().status());
        assertEquals(owner, claims.ownerAt(0, 0));
        assertEquals(EstateListing.Status.ACTIVE, store.listings.get(listed.listing().id()), "back on the market");
        assertNotNull(service.get(listed.listing().id()));
    }

    // ---- lease ----------------------------------------------------------------------------------------------

    @Test void leaseRenewGraceAndEndTimeline() {
        long start = clock.get();
        Lease lease = rentedOut(3, 100_00, 7);
        assertEquals(start + 7 * DAY, lease.paidUntil());
        for (int x = 0; x < 3; x++) assertEquals(start + 7 * DAY + 24 * HOUR, claims.trustAt(x, 0, buyer), "trust until paid_until + grace");
        assertEquals(1_000_00L - 100_00L, bank.balance(buyer));
        assertEquals(90_00L, bank.balance(owner));
        assertEquals(Lease.Status.ACTIVE, store.leases.get(lease.id()));

        clock.set(start + 7 * DAY + 1);
        service.check();
        assertEquals(Lease.Status.ACTIVE, lease.status(), "renewed");
        assertEquals(start + 14 * DAY, lease.paidUntil());
        assertEquals(start + 14 * DAY, store.paidUntil.get(lease.id()));
        assertEquals(start + 14 * DAY + 24 * HOUR, claims.trustAt(1, 0, buyer), "trust extended");
        assertEquals(800_00L, bank.balance(buyer));
        assertEquals(180_00L, bank.balance(owner));
        assertTrue(platform.told(buyer).contains("estate.renewed"));
        assertTrue(platform.told(owner).contains("estate.rent-received"));

        bank.balances.put(buyer, 5_00L);
        clock.set(start + 14 * DAY + 1);
        service.check();
        assertEquals(Lease.Status.GRACE, lease.status(), "cannot pay: grace");
        assertEquals(Lease.Status.GRACE, store.leases.get(lease.id()));
        assertTrue(platform.told(buyer).contains("estate.grace-unpaid"));
        assertTrue(platform.told(owner).contains("estate.grace-owner"));
        assertNotNull(claims.trustAt(0, 0, buyer), "trust stays during grace");

        clock.set(start + 14 * DAY + 23 * HOUR);
        service.check();
        assertEquals(Lease.Status.GRACE, lease.status(), "still inside the grace time");

        clock.set(start + 14 * DAY + 24 * HOUR);
        service.check();
        assertEquals(Lease.Status.ENDED, lease.status());
        assertEquals(Lease.Status.ENDED, store.leases.get(lease.id()));
        for (int x = 0; x < 3; x++) assertNull(claims.trustAt(x, 0, buyer), "trust revoked");
        assertEquals(EstateListing.Status.ENDED, store.listings.get(lease.listingId()));
        assertTrue(platform.told(buyer).contains("estate.lease-ended-unpaid"));
        assertTrue(platform.told(owner).contains("estate.lease-ended-owner"));
        assertEquals(List.of(EstateService.EndReason.UNPAID), platform.ended);
        assertEquals(5_00L, bank.balance(buyer), "nothing charged after the failure");
    }

    @Test void graceIsLeftByPayingAgain() {
        long start = clock.get();
        Lease lease = rentedOut(1, 100_00, 7);
        bank.balances.put(buyer, 0L);
        clock.set(start + 7 * DAY + 1);
        service.check();
        assertEquals(Lease.Status.GRACE, lease.status());
        bank.balances.put(buyer, 100_00L);
        clock.set(start + 7 * DAY + 2 * HOUR);
        service.check();
        assertEquals(Lease.Status.ACTIVE, lease.status(), "paid during grace");
        assertEquals(start + 14 * DAY, lease.paidUntil(), "the period keeps its rhythm");
    }

    @Test void evictEndsAtPaidUntilWithoutCharging() {
        long start = clock.get();
        Lease lease = rentedOut(2, 100_00, 7);
        AtomicReference<Outcome> result = new AtomicReference<>();
        service.evict(lease.id(), buyer, result::set);
        assertEquals(Result.NOT_OWNER, result.get().result(), "only the owner evicts");
        service.evict(lease.id(), owner, result::set);
        assertEquals(Result.OK, result.get().result());
        assertEquals(0, result.get().amount(), "not at once");
        assertTrue(platform.told(buyer).contains("estate.evict-notice"));
        service.evict(lease.id(), owner, result::set);
        assertEquals(Result.ALREADY_EVICTED, result.get().result());

        clock.set(start + 6 * DAY);
        service.check();
        assertEquals(Lease.Status.ACTIVE, lease.status(), "the paid period is honoured");
        long balance = bank.balance(buyer);
        clock.set(start + 7 * DAY);
        service.check();
        assertEquals(Lease.Status.ENDED, lease.status());
        assertEquals(balance, bank.balance(buyer), "no new period was charged");
        assertNull(claims.trustAt(0, 0, buyer));
        assertTrue(platform.told(buyer).contains("estate.lease-ended-evicted"));
        assertEquals(List.of(EstateService.EndReason.EVICTED), platform.ended);
    }

    @Test void autorenewOffGoesToGraceThenEnds() {
        long start = clock.get();
        Lease lease = rentedOut(1, 100_00, 7);
        AtomicReference<Outcome> result = new AtomicReference<>();
        service.autorenew(lease.id(), owner, result::set);
        assertEquals(Result.NOT_TENANT, result.get().result());
        service.autorenew(lease.listingId(), buyer, result::set);
        assertEquals(Result.OK, result.get().result());
        assertEquals(0, result.get().amount(), "now off");
        assertFalse(lease.autorenew());
        long balance = bank.balance(buyer);
        clock.set(start + 7 * DAY);
        service.check();
        assertEquals(Lease.Status.GRACE, lease.status());
        assertEquals(balance, bank.balance(buyer), "not charged with auto-renew off");
        assertTrue(platform.told(buyer).contains("estate.grace"));
        clock.set(start + 8 * DAY);
        service.check();
        assertEquals(Lease.Status.ENDED, lease.status());
        assertNull(claims.trustAt(0, 0, buyer));
    }

    @Test void tenantLeavesWithoutRefund() {
        Lease lease = rentedOut(2, 100_00, 7);
        long balance = bank.balance(buyer);
        AtomicReference<Outcome> result = new AtomicReference<>();
        service.leave(lease.id(), stranger, result::set);
        assertEquals(Result.NOT_TENANT, result.get().result());
        service.leave(lease.id(), buyer, result::set);
        assertEquals(Result.OK, result.get().result());
        assertEquals(Lease.Status.ENDED, lease.status());
        assertEquals(balance, bank.balance(buyer), "no refund");
        assertNull(claims.trustAt(0, 0, buyer));
        assertNull(claims.trustAt(1, 0, buyer));
        assertTrue(platform.told(owner).contains("estate.lease-left"));
        assertEquals(EstateListing.Status.ENDED, store.listings.get(lease.listingId()));
        service.leave(lease.id(), buyer, result::set);
        assertEquals(Result.NO_LEASE, result.get().result());
    }

    @Test void ownerUnlistRefundsTheUnusedTime() {
        long start = clock.get();
        Lease lease = rentedOut(2, 700_00, 7);
        long tenantBefore = bank.balance(buyer);
        long ownerBefore = bank.balance(owner);
        clock.set(start + 3 * DAY + 12 * HOUR);
        AtomicReference<Outcome> result = new AtomicReference<>();
        service.unlist(lease.listingId(), stranger, false, result::set);
        assertEquals(Result.NOT_OWNER, result.get().result());

        bank.balances.put(owner, 100_00L);
        service.unlist(lease.listingId(), owner, false, result::set);
        assertEquals(Result.REFUND_NOT_AFFORDABLE, result.get().result(), "the owner cannot pay 350.00 back");
        assertEquals(350_00L, result.get().amount());
        assertEquals(Lease.Status.ACTIVE, lease.status());
        bank.balances.put(owner, ownerBefore);

        service.unlist(lease.listingId(), owner, false, result::set);
        assertEquals(Result.OK, result.get().result());
        assertEquals(350_00L, result.get().amount(), "half the period left, 100% refund");
        assertEquals(tenantBefore + 350_00L, bank.balance(buyer));
        assertEquals(ownerBefore - 350_00L, bank.balance(owner));
        assertEquals(Lease.Status.ENDED, lease.status());
        assertEquals(EstateListing.Status.CANCELLED, store.listings.get(lease.listingId()));
        assertNull(claims.trustAt(0, 0, buyer));
        assertTrue(platform.told(buyer).contains("estate.lease-ended-unlisted"));
        assertEquals(List.of(EstateService.EndReason.UNLISTED), platform.ended);
    }

    @Test void unlistRefundFollowsThePercentage() {
        service.settings(new EstateService.Settings("money", List.of("money"), 64, 0.0, true, 1.0, 1_000_000.0, true, 1, 90,
                Set.of("build"), 24 * HOUR, 50, 30 * DAY));
        long start = clock.get();
        Lease lease = rentedOut(1, 100_00, 10);
        clock.set(start + 5 * DAY);
        AtomicReference<Outcome> result = new AtomicReference<>();
        service.unlist(lease.listingId(), owner, false, result::set);
        assertEquals(25_00L, result.get().amount(), "50% of the unused half");
    }

    @Test void unlistWithoutTenantAndListingExpiry() {
        row(4);
        Outcome first = list(owner, EstateListing.Type.SALE, 50_00, 0, 0, true);
        Outcome second = list(owner, EstateListing.Type.RENT, 50_00, 7, 3, true);
        AtomicReference<Outcome> result = new AtomicReference<>();
        service.unlist(first.listing().id(), owner, false, result::set);
        assertEquals(Result.OK, result.get().result());
        assertEquals(EstateListing.Status.CANCELLED, store.listings.get(first.listing().id()));
        clock.addAndGet(30 * DAY);
        service.check();
        assertEquals(EstateListing.Status.EXPIRED, store.listings.get(second.listing().id()));
        assertTrue(platform.told(owner).contains("estate.listing-expired"));
        assertTrue(service.active().isEmpty());
    }

    @Test void claimRemovedMidLeaseEndsItWithRefund() {
        long start = clock.get();
        Lease lease = rentedOut(3, 700_00, 7);
        long tenantBefore = bank.balance(buyer);
        clock.set(start + DAY);
        claims.remove(1, 0);
        service.claimLost(WORLD, 1, 0, owner);
        assertEquals(Lease.Status.ENDED, lease.status());
        assertEquals(tenantBefore + 600_00L, bank.balance(buyer), "six of seven days back");
        assertEquals(EstateListing.Status.CANCELLED, store.listings.get(lease.listingId()));
        assertNull(claims.trustAt(0, 0, buyer), "trust gone on the claims that are left");
        assertNull(claims.trustAt(2, 0, buyer));
        assertTrue(platform.told(buyer).contains("estate.lease-ended-claim"));
        assertTrue(platform.told(owner).contains("estate.listing-ended-claim"));
        assertEquals(List.of(EstateService.EndReason.CLAIM_LOST), platform.ended);
    }

    @Test void claimTransferredAwayEndsAnActiveListing() {
        row(2);
        Outcome listed = list(owner, EstateListing.Type.SALE, 50_00, 0, 0, false);
        service.claimLost(WORLD, 5, 5, owner);
        assertEquals(EstateListing.Status.ACTIVE, listed.listing().status(), "another chunk changes nothing");
        service.claimLost(WORLD, 1, 0, stranger);
        assertEquals(EstateListing.Status.ACTIVE, listed.listing().status(), "only the owner's own loss counts");
        service.claimLost(WORLD, 1, 0, owner);
        assertEquals(EstateListing.Status.CANCELLED, store.listings.get(listed.listing().id()));
    }
}
