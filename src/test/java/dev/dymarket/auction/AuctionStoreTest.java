package dev.dymarket.auction;

import dev.dymarket.storage.SqliteStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The SQLite side of the auction house against a real database file in a temporary folder. */
class AuctionStoreTest {
    @TempDir File folder;
    SqliteStore sqlite;
    AuctionStore store;
    final UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID();

    @BeforeEach void open() throws Exception {
        sqlite = new SqliteStore(new File(folder, "data.db"), Logger.getLogger("test"));
        sqlite.open();
        store = new AuctionStore(sqlite);
        store.prepare();
    }

    @AfterEach void close() {
        sqlite.close();
    }

    private Listing listing(String id, long created) {
        return new Listing(id, seller, "Seller", new byte[]{2, 7, 9}, 16, 12_345, "money", created, created + 1000, 500,
                ListingStatus.ACTIVE, null);
    }

    @Test void listingsRoundTrip() throws Exception {
        store.insert(listing("bbb", 20));
        store.insert(listing("aaa", 10));
        List<Listing> active = store.loadActive();
        assertEquals(List.of("aaa", "bbb"), active.stream().map(Listing::id).toList(), "oldest first");
        Listing read = active.get(0);
        assertEquals(seller, read.seller());
        assertEquals("Seller", read.sellerName());
        assertArrayEquals(new byte[]{2, 7, 9}, read.item());
        assertEquals(16, read.amount());
        assertEquals(12_345, read.price());
        assertEquals("money", read.currency());
        assertEquals(1010, read.expires());
        assertEquals(500, read.taxBasisPoints());
        assertEquals(ListingStatus.ACTIVE, read.status());
        assertNull(read.buyer());
        assertThrows(java.sql.SQLException.class, () -> store.insert(listing("aaa", 30)), "ids are unique");
    }

    @Test void transitionsOnlyApplyFromTheExpectedStatus() throws Exception {
        store.insert(listing("aaa", 10));
        assertTrue(store.transition("aaa", ListingStatus.ACTIVE, ListingStatus.SOLD, buyer));
        assertFalse(store.transition("aaa", ListingStatus.ACTIVE, ListingStatus.SOLD, UUID.randomUUID()), "sold only once");
        Listing sold = store.find("aaa");
        assertEquals(ListingStatus.SOLD, sold.status());
        assertEquals(buyer, sold.buyer());
        assertTrue(store.loadActive().isEmpty());
        assertTrue(store.transition("aaa", ListingStatus.SOLD, ListingStatus.ACTIVE, null), "a refunded sale goes back on sale");
        assertEquals(1, store.loadActive().size());
        assertFalse(store.transition("zzz", ListingStatus.ACTIVE, ListingStatus.SOLD, buyer));
    }

    @Test void retiringMovesTheItemToTheCollectionBoxInOneStep() throws Exception {
        store.insert(listing("aaa", 10));
        assertTrue(store.transitionToCollect("aaa", ListingStatus.EXPIRED, seller, CollectEntry.EXPIRED, 777));
        assertFalse(store.transitionToCollect("aaa", ListingStatus.EXPIRED, seller, CollectEntry.EXPIRED, 778), "only once");
        assertEquals(ListingStatus.EXPIRED, store.find("aaa").status());
        List<CollectEntry> box = store.collectBox(seller);
        assertEquals(1, box.size());
        assertArrayEquals(new byte[]{2, 7, 9}, box.get(0).item());
        assertEquals(CollectEntry.EXPIRED, box.get(0).reason());
        assertEquals(777, box.get(0).timestamp());
    }

    @Test void collectionBoxTakeAndTakeAll() throws Exception {
        long first = store.addCollect(buyer, new byte[]{1}, CollectEntry.FULL_INVENTORY, 1);
        store.addCollect(buyer, new byte[]{2}, CollectEntry.PURCHASE, 2);
        store.addCollect(seller, new byte[]{3}, CollectEntry.CANCELLED, 3);
        assertEquals(2, store.collectCount(buyer));
        assertNull(store.takeCollect(first, seller), "only the owner takes an entry");
        CollectEntry taken = store.takeCollect(first, buyer);
        assertArrayEquals(new byte[]{1}, taken.item());
        assertNull(store.takeCollect(first, buyer), "taken once");
        List<CollectEntry> rest = store.takeAllCollect(buyer);
        assertEquals(1, rest.size());
        assertEquals(CollectEntry.PURCHASE, rest.get(0).reason());
        assertEquals(0, store.collectCount(buyer));
        assertEquals(1, store.collectCount(seller), "other boxes untouched");
    }

    @Test void mergeMovesListingsAndTheCollectionBox() throws Exception {
        store.insert(listing("aaa", 10));
        store.addCollect(seller, new byte[]{1}, CollectEntry.EXPIRED, 1);
        sqlite.mergeOwnership(seller, buyer, "Buyer");
        Listing moved = store.loadActive().get(0);
        assertEquals(buyer, moved.seller());
        assertEquals("Buyer", moved.sellerName());
        assertEquals(1, store.collectCount(buyer));
        assertEquals(0, store.collectCount(seller));
    }

    @Test void prepareAddsTheTaxColumnToOlderTables() throws Exception {
        try (Statement statement = sqlite.connection().createStatement()) {
            statement.execute("DROP TABLE auction_listings");
            statement.execute("CREATE TABLE auction_listings(id TEXT PRIMARY KEY, seller TEXT, seller_name TEXT, item BLOB,"
                    + " amount INTEGER, price INTEGER, currency TEXT, created INTEGER, expires INTEGER, status TEXT, buyer TEXT)");
        }
        store.prepare();
        store.prepare();
        store.insert(listing("aaa", 10));
        assertEquals(500, store.loadActive().get(0).taxBasisPoints());
    }
}
