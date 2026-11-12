package dev.dymarket.storage;

import dev.dymarket.economy.EconomyStore;
import dev.dymarket.economy.Transaction;
import dev.dymarket.economy.TransactionKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreTest {
    @TempDir File folder;
    SqliteStore store;
    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    SqliteStore open() throws Exception {
        store = new SqliteStore(new File(folder, "data.db"), Logger.getLogger("test"));
        store.open();
        return store;
    }

    @AfterEach void close() {
        if (store != null) store.close();
    }

    private Transaction transaction(String id, String external) {
        return new Transaction(id, 1000L, TransactionKind.PAY, "money", alice, bob, 500, 9500, 10500, "note", external, null);
    }

    @Test void createsEverySchemaTable() throws Exception {
        open();
        assertEquals(SqliteStore.SCHEMA, store.schemaVersion());
        Set<String> tables = new java.util.HashSet<>();
        try (Statement statement = store.connection().createStatement();
             ResultSet result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (result.next()) tables.add(result.getString(1));
        }
        assertTrue(tables.containsAll(Set.of("accounts", "transactions", "auction_listings", "collect_box", "estate_listings",
                "estate_leases", "players", "meta")), tables.toString());
        store.close();
        open();
        assertEquals(1, store.schemaVersion(), "opening again keeps the schema");
    }

    @Test void aNewerSchemaIsRefused() throws Exception {
        open();
        store.setMeta("schema", "99");
        store.close();
        assertThrows(java.sql.SQLException.class, this::open);
        store = null;
    }

    @Test void balancesAndTransactionsRoundTrip() throws Exception {
        open();
        assertNull(store.loadAccount(alice));
        store.save(List.of(new EconomyStore.AccountSnapshot(alice, "Alice", Map.of("money", 9500L, "credits", 3L))),
                List.of(transaction("t1", "order-1")), Map.of());
        store.save(List.of(new EconomyStore.AccountSnapshot(alice, "Alice", Map.of("money", 9000L))), List.of(), Map.of("t1", "t2"));
        store.close();
        open();
        EconomyStore.StoredAccount loaded = store.loadAccount(alice);
        assertEquals("Alice", loaded.name());
        assertEquals(Map.of("money", 9000L, "credits", 3L), loaded.balances());
        assertEquals(transaction("t1", "order-1").withReversedBy("t2"), store.transaction("t1"));
        assertTrue(store.externalIdExists("order-1"));
        assertFalse(store.externalIdExists("order-2"));
        assertEquals(1, store.transactions(bob, 0, 10).size());
        assertEquals(1, store.transactionCount(alice));
        assertEquals(List.of(new SqliteStore.TopEntry(alice, "Alice", 9000L)), store.top("money", 10, Set.of()));
        assertTrue(store.top("money", 10, Set.of(alice)).isEmpty());
    }

    @Test void externalIdsAreUnique() throws Exception {
        open();
        store.save(List.of(), List.of(transaction("t1", "order-1")), Map.of());
        store.save(List.of(), List.of(transaction("t2", "order-1")), Map.of());
        assertNull(store.transaction("t2"), "the duplicate external id was not stored");
        assertThrows(java.sql.SQLException.class, () -> {
            try (Statement statement = store.connection().createStatement()) {
                statement.executeUpdate("INSERT INTO transactions(id, external_id) VALUES('t3', 'order-1')");
            }
        });
    }

    @Test void playersLanguagesNoticesAndMerge() throws Exception {
        open();
        store.upsertPlayer(alice, "Alice", 5L);
        store.setLanguage(alice, "tr");
        assertEquals("tr", store.player(alice).language());
        assertEquals(alice, store.playerByName("ALICE").id());
        store.setLanguage(alice, "auto");
        assertNull(store.player(alice).language());
        store.addNotice(alice, "estate.rent-ended", Map.of("{player}", "Bob", "{days}", "3"));
        List<SqliteStore.Notice> notices = store.takeNotices(alice);
        assertEquals(1, notices.size());
        assertEquals(Map.of("{player}", "Bob", "{days}", "3"), notices.get(0).values());
        assertTrue(store.takeNotices(alice).isEmpty(), "notices are delivered once");
        try (Statement statement = store.connection().createStatement()) {
            statement.executeUpdate("INSERT INTO auction_listings(id, seller, seller_name, status) VALUES('a1', '" + alice + "', 'Alice', 'ACTIVE')");
        }
        store.mergeOwnership(alice, bob, "Bob");
        try (Statement statement = store.connection().createStatement();
             ResultSet result = statement.executeQuery("SELECT seller, seller_name FROM auction_listings WHERE id = 'a1'")) {
            assertTrue(result.next());
            assertEquals(bob.toString(), result.getString(1));
            assertEquals("Bob", result.getString(2));
        }
        assertTrue(store.player(alice).name().contains("merged into Bob"));
    }

    @Test void backupsKeepTheNewestSeven() throws Exception {
        File database = new File(folder, "data.db");
        Files.writeString(database.toPath(), "x");
        File backups = new File(folder, "backups");
        for (int day = 1; day <= 9; day++) SqliteStore.backup(database, backups, LocalDate.of(2026, 1, day), 7);
        assertEquals(7, backups.list().length);
        assertFalse(new File(backups, "data-2026-01-01.db").exists());
        assertTrue(new File(backups, "data-2026-01-09.db").exists());
    }
}
