package dev.dymarket.economy;

import dev.dymarket.economy.TransactionResult.Status;
import dev.dymarket.storage.IoThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class EconomyServiceTest {
    static final Currency MONEY = new Currency("money", "Money", "Money", "$", false, 2, Locale.US, 10_000, 100_000_000, true, true, "internal");
    static final Currency CREDITS = new Currency("credits", "Credit", "Credits", "C", true, 0, Locale.US, 0, 1_000_000, true, false, "internal");
    static final Currency LOCKED = new Currency("tokens", "Token", "Tokens", "T", true, 0, Locale.US, 0, 1_000, false, false, "internal");

    /** Storage in memory, recording what the economy writes. */
    static final class FakeStore implements EconomyStore {
        final Map<UUID, Map<String, Long>> balances = new ConcurrentHashMap<>();
        final Map<String, Transaction> transactions = new ConcurrentHashMap<>();
        boolean fail;

        @Override public StoredAccount loadAccount(UUID id) {
            Map<String, Long> stored = balances.get(id);
            return stored == null ? null : new StoredAccount(null, new HashMap<>(stored));
        }

        @Override public boolean externalIdExists(String externalId) {
            return transactions.values().stream().anyMatch(t -> externalId.equals(t.externalId()));
        }

        @Override public Transaction transaction(String id) { return transactions.get(id); }

        @Override public void save(List<AccountSnapshot> accounts, List<Transaction> logged, Map<String, String> reversals) throws Exception {
            if (fail) throw new java.sql.SQLException("disk full");
            for (AccountSnapshot snapshot : accounts) balances.computeIfAbsent(snapshot.id(), k -> new HashMap<>()).putAll(snapshot.balances());
            for (Transaction transaction : logged) transactions.put(transaction.id(), transaction);
            reversals.forEach((id, by) -> transactions.computeIfPresent(id, (k, t) -> t.withReversedBy(by)));
        }
    }

    final FakeStore store = new FakeStore();
    final IoThread io = new IoThread(Logger.getLogger("test"));
    final EconomyService economy = new EconomyService(store, io, new CurrencyRegistry(List.of(MONEY, CREDITS, LOCKED)), Logger.getLogger("test"));
    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    @AfterEach void stop() { io.shutdown(); }

    @Test void newAccountsStartWithTheStartingBalance() {
        assertEquals(10_000, economy.balance(alice, MONEY));
        assertEquals(0, economy.balance(alice, CREDITS));
    }

    @Test void depositWithdrawAndTransfer() {
        assertTrue(economy.deposit(alice, MONEY, 5_000, TransactionKind.GIVE, null).success());
        TransactionResult taken = economy.withdraw(alice, MONEY, 2_500, TransactionKind.TAKE, null);
        assertTrue(taken.success());
        assertEquals(12_500, taken.balance());
        TransactionResult paid = economy.transfer(alice, bob, MONEY, 2_500, TransactionKind.PAY, null);
        assertTrue(paid.success());
        assertEquals(10_000, economy.balance(alice, MONEY));
        assertEquals(12_500, economy.balance(bob, MONEY));
        Transaction logged = paid.transaction();
        assertEquals(alice, logged.from());
        assertEquals(bob, logged.to());
        assertEquals(10_000, logged.fromAfter());
        assertEquals(12_500, logged.toAfter());
    }

    @Test void negativeBalancesAndBadAmountsAreRefused() {
        TransactionResult result = economy.withdraw(alice, MONEY, 10_001, TransactionKind.TAKE, null);
        assertEquals(Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(10_000, economy.balance(alice, MONEY), "nothing changed");
        assertEquals(Status.INSUFFICIENT_FUNDS, economy.transfer(alice, bob, MONEY, 20_000, TransactionKind.PAY, null).status());
        assertEquals(10_000, economy.balance(bob, MONEY));
        assertEquals(Status.INVALID_AMOUNT, economy.deposit(alice, MONEY, -5, TransactionKind.GIVE, null).status());
        assertEquals(Status.INVALID_AMOUNT, economy.deposit(alice, MONEY, 0, TransactionKind.GIVE, null).status());
        assertEquals(Status.SAME_ACCOUNT, economy.transfer(alice, alice, MONEY, 1, TransactionKind.PAY, null).status());
        assertEquals(Status.UNKNOWN_CURRENCY, economy.deposit(alice, null, 1, TransactionKind.GIVE, null).status());
        assertEquals(Status.NOT_TRANSFERABLE, economy.transfer(alice, bob, LOCKED, 1, TransactionKind.PAY, null).status());
        assertEquals(0, economy.pendingCount(), "failed operations log nothing");
    }

    @Test void theMaximumBalanceIsNeverPassed() {
        assertEquals(Status.MAX_BALANCE, economy.deposit(alice, MONEY, 100_000_000, TransactionKind.GIVE, null).status());
        assertTrue(economy.deposit(alice, MONEY, 99_990_000, TransactionKind.GIVE, null).success());
        assertEquals(100_000_000, economy.balance(alice, MONEY));
        assertEquals(Status.MAX_BALANCE, economy.transfer(bob, alice, MONEY, 1, TransactionKind.PAY, null).status());
        assertEquals(10_000, economy.balance(bob, MONEY), "the failed transfer took nothing");
        assertEquals(Status.MAX_BALANCE, economy.set(alice, MONEY, 100_000_001, null).status());
    }

    @Test void eightThreadsOfRandomTransfersKeepTheTotal() throws Exception {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID id = UUID.randomUUID();
            players.add(id);
            economy.set(id, MONEY, 1_000_000, null);
        }
        long total = players.stream().mapToLong(id -> economy.balance(id, MONEY)).sum();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int thread = 0; thread < 8; thread++) {
            pool.submit(() -> {
                start.await();
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < 5_000; i++) {
                    UUID from = players.get(random.nextInt(players.size())), to = players.get(random.nextInt(players.size()));
                    economy.transfer(from, to, MONEY, random.nextLong(1, 400_000), TransactionKind.PAY, null);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(total, players.stream().mapToLong(id -> economy.balance(id, MONEY)).sum());
        for (UUID id : players) assertTrue(economy.balance(id, MONEY) >= 0);
    }

    @Test void aGiveWithATransactionIdIsAppliedOnce() {
        assertTrue(economy.give(alice, CREDITS, 500, TransactionKind.TOPUP, null, "order-1").success());
        assertEquals(Status.ALREADY_APPLIED, economy.give(alice, CREDITS, 500, TransactionKind.TOPUP, null, "order-1").status());
        economy.flush();
        assertEquals(Status.ALREADY_APPLIED, economy.give(alice, CREDITS, 500, TransactionKind.TOPUP, null, "order-1").status(),
                "also after the first one was written");
        assertEquals(500, economy.balance(alice, CREDITS));
    }

    @Test void rollbackReversesOnceAndRefusesToGoNegative() throws Exception {
        String pay = economy.transfer(alice, bob, MONEY, 4_000, TransactionKind.PAY, null).transaction().id();
        TransactionResult undone = economy.rollback(pay, false);
        assertTrue(undone.success());
        assertEquals(10_000, economy.balance(alice, MONEY));
        assertEquals(10_000, economy.balance(bob, MONEY));
        assertEquals(Status.ALREADY_REVERSED, economy.rollback(pay, false).status());
        economy.flush();
        assertEquals(undone.transaction().id(), store.transactions.get(pay).reversedBy());
        assertEquals(Status.ALREADY_REVERSED, economy.rollback(pay, false).status());

        String gift = economy.deposit(bob, MONEY, 5_000, TransactionKind.GIVE, null).transaction().id();
        economy.withdraw(bob, MONEY, 14_000, TransactionKind.TAKE, null);
        assertEquals(Status.INSUFFICIENT_FUNDS, economy.rollback(gift, false).status(), "bob only has 1000 left");
        assertEquals(1_000, economy.balance(bob, MONEY));
        assertTrue(economy.rollback(gift, true).success(), "force clamps at zero");
        assertEquals(0, economy.balance(bob, MONEY));
        assertEquals(Status.NOT_FOUND, economy.rollback("missing", false).status());
    }

    @Test void mergeMovesEveryCurrencyAndLeavesTheSourceEmpty() {
        economy.deposit(alice, CREDITS, 70, TransactionKind.GIVE, null);
        economy.deposit(bob, CREDITS, 30, TransactionKind.GIVE, null);
        TransactionResult merged = economy.merge(alice, bob);
        assertTrue(merged.success());
        assertEquals(2, merged.transactions().size());
        assertTrue(merged.transactions().stream().allMatch(t -> t.kind() == TransactionKind.MERGE));
        assertEquals(0, economy.balance(alice, MONEY));
        assertEquals(0, economy.balance(alice, CREDITS));
        assertEquals(20_000, economy.balance(bob, MONEY));
        assertEquals(100, economy.balance(bob, CREDITS));
        assertEquals(Status.SAME_ACCOUNT, economy.merge(bob, bob).status());
    }

    @Test void setLogsTheDifferenceAndConvertIsAtomic() {
        TransactionResult set = economy.set(alice, MONEY, 2_500, null);
        assertEquals(TransactionKind.SET, set.transaction().kind());
        assertEquals(7_500, set.transaction().amount());
        assertEquals(alice, set.transaction().from());
        economy.deposit(alice, CREDITS, 10, TransactionKind.GIVE, null);
        assertEquals(Status.INSUFFICIENT_FUNDS, economy.convert(alice, CREDITS, 11, MONEY, 1_100_000, null).status());
        assertEquals(Status.MAX_BALANCE, economy.convert(alice, CREDITS, 1, MONEY, 100_000_000, null).status());
        assertEquals(10, economy.balance(alice, CREDITS), "failed conversions change nothing");
        TransactionResult converted = economy.convert(alice, CREDITS, 2, MONEY, 200_000, null);
        assertTrue(converted.success());
        assertEquals(2, converted.transactions().size());
        assertEquals(8, economy.balance(alice, CREDITS));
        assertEquals(202_500, economy.balance(alice, MONEY));
    }

    @Test void writesAreRetriedAfterAFailureAndBalancesSurviveEviction() {
        economy.deposit(alice, MONEY, 1_000, TransactionKind.GIVE, null);
        store.fail = true;
        economy.flush();
        assertEquals(1, economy.pendingCount(), "kept for the next attempt");
        store.fail = false;
        economy.flush();
        assertEquals(0, economy.pendingCount());
        assertEquals(11_000L, store.balances.get(alice).get("money"));
        economy.evictIdle(id -> false, 0);
        assertFalse(economy.loaded(alice));
        assertEquals(11_000, economy.balance(alice, MONEY), "reloaded from storage");
    }

    @Test void aGuardCanCancelAChange() {
        economy.guard((player, currency, delta, kind) -> kind != TransactionKind.PAY);
        assertEquals(Status.CANCELLED, economy.transfer(alice, bob, MONEY, 100, TransactionKind.PAY, null).status());
        assertTrue(economy.deposit(alice, MONEY, 100, TransactionKind.GIVE, null).success());
    }

    @Test void importsNeverOverwriteMoneyPlayersAlreadyHave() {
        assertTrue(economy.importBalance(alice, "Alice", MONEY, 55_500), "starting balance may be replaced");
        assertEquals(55_500, economy.balance(alice, MONEY));
        economy.deposit(bob, MONEY, 1, TransactionKind.GIVE, null);
        assertFalse(economy.importBalance(bob, "Bob", MONEY, 99_999));
        assertEquals(10_001, economy.balance(bob, MONEY));
    }
}
