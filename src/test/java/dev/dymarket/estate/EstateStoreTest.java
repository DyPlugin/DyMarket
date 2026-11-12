package dev.dymarket.estate;

import dev.dymarket.storage.SqliteStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The SQLite side of the estate module against a real database file in a temporary folder. */
class EstateStoreTest {
    @TempDir File folder;
    SqliteStore sqlite;
    EstateStore store;
    final UUID owner = UUID.randomUUID(), tenant = UUID.randomUUID();

    @BeforeEach void open() throws Exception {
        sqlite = new SqliteStore(new File(folder, "data.db"), Logger.getLogger("test"));
        sqlite.open();
        store = new EstateStore(sqlite);
        store.prepare();
    }

    @AfterEach void close() {
        sqlite.close();
    }

    private EstateListing listing(String id, EstateListing.Type type, long created) {
        return new EstateListing(id, owner, "Owner", "world", List.of(new ChunkPos(0, 0), new ChunkPos(-1, 0), new ChunkPos(0, 3)), type,
                12_345, "money", type == EstateListing.Type.RENT ? 7 : 0, created, created + 1000, 500, EstateListing.Status.ACTIVE);
    }

    @Test void listingsRoundTrip() throws Exception {
        store.insertListing(listing("bbb", EstateListing.Type.RENT, 20));
        store.insertListing(listing("aaa", EstateListing.Type.SALE, 10));
        List<EstateListing> open = store.loadListings();
        assertEquals(List.of("aaa", "bbb"), open.stream().map(EstateListing::id).toList(), "oldest first");
        EstateListing read = open.get(1);
        assertEquals(owner, read.owner());
        assertEquals("Owner", read.ownerName());
        assertEquals("world", read.world());
        assertEquals(List.of(new ChunkPos(0, 0), new ChunkPos(-1, 0), new ChunkPos(0, 3)), read.chunks());
        assertEquals(EstateListing.Type.RENT, read.type());
        assertEquals(12_345, read.price());
        assertEquals("money", read.currency());
        assertEquals(7, read.days());
        assertEquals(20, read.created());
        assertEquals(1020, read.expires());
        assertEquals(500, read.taxBasisPoints());
        assertEquals(EstateListing.Status.ACTIVE, read.status());
    }

    @Test void listingTransitionsAreConditional() throws Exception {
        store.insertListing(listing("abc", EstateListing.Type.RENT, 10));
        assertTrue(store.transitionListing("abc", EstateListing.Status.ACTIVE, EstateListing.Status.LEASED));
        assertFalse(store.transitionListing("abc", EstateListing.Status.ACTIVE, EstateListing.Status.SOLD), "no longer active");
        assertEquals(1, store.loadListings().size(), "leased listings are loaded too");
        assertTrue(store.transitionListing("abc", EstateListing.Status.LEASED, EstateListing.Status.ENDED));
        assertTrue(store.loadListings().isEmpty());
        assertEquals(EstateListing.Status.ENDED, store.findListing("abc").status());
    }

    @Test void leasesRoundTripAndUpdate() throws Exception {
        Lease lease = new Lease("lea", "abc", owner, "Owner", tenant, "Tenant", "world", List.of(new ChunkPos(4, -2)), 700, "credits", 7,
                100, 100 + 7 * EstateService.DAY, true, false, 250, Lease.Status.ACTIVE);
        store.insertLease(lease);
        Lease read = store.loadLeases().get(0);
        assertEquals("lea", read.id());
        assertEquals("abc", read.listingId());
        assertEquals(owner, read.owner());
        assertEquals("Owner", read.ownerName());
        assertEquals(tenant, read.tenant());
        assertEquals("Tenant", read.tenantName());
        assertEquals(List.of(new ChunkPos(4, -2)), read.chunks());
        assertEquals(700, read.price());
        assertEquals("credits", read.currency());
        assertEquals(7, read.days());
        assertEquals(100, read.started());
        assertEquals(100 + 7 * EstateService.DAY, read.paidUntil());
        assertTrue(read.autorenew());
        assertFalse(read.evicted());
        assertEquals(250, read.taxBasisPoints());

        lease.paidUntil(5_000);
        lease.autorenew(false);
        lease.evicted(true);
        lease.status(Lease.Status.GRACE);
        store.updateLease(lease);
        read = store.loadLeases().get(0);
        assertEquals(5_000, read.paidUntil());
        assertFalse(read.autorenew());
        assertTrue(read.evicted());
        assertEquals(Lease.Status.GRACE, read.status());

        lease.status(Lease.Status.ENDED);
        store.updateLease(lease);
        assertTrue(store.loadLeases().isEmpty(), "ended leases are not loaded");
        assertEquals(Lease.Status.ENDED, store.findLease("lea").status());
    }

    @Test void prepareIsRepeatableAndMergeMovesEstateRows() throws Exception {
        store.prepare();
        store.insertListing(listing("abc", EstateListing.Type.SALE, 10));
        store.insertLease(new Lease("lea", "abc", owner, "Owner", tenant, "Tenant", "world", List.of(new ChunkPos(0, 0)), 700, "money", 7,
                100, 200, true, false, 0, Lease.Status.ACTIVE));
        UUID merged = UUID.randomUUID();
        sqlite.mergeOwnership(owner, merged, "Merged");
        assertEquals(merged, store.findListing("abc").owner());
        assertEquals("Merged", store.findListing("abc").ownerName());
        assertEquals(merged, store.findLease("lea").owner());
    }

    @Test void chunkTextSurvivesDamage() {
        assertEquals(List.of(new ChunkPos(1, 2), new ChunkPos(-3, 4)), ChunkPos.decode("1,2;oops;-3,4;5"));
        assertEquals("1,2;-3,4", ChunkPos.encode(List.of(new ChunkPos(1, 2), new ChunkPos(-3, 4))));
        assertTrue(ChunkPos.decode(null).isEmpty());
    }
}
