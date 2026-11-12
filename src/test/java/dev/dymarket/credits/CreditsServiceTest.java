package dev.dymarket.credits;

import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyRegistry;
import dev.dymarket.economy.EconomyService;
import dev.dymarket.economy.EconomyStore;
import dev.dymarket.economy.Transaction;
import dev.dymarket.economy.TransactionKind;
import dev.dymarket.economy.TransactionResult.Status;
import dev.dymarket.storage.IoThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CreditsServiceTest {
    static final Currency MONEY = new Currency("money", "Money", "Money", "$", false, 2, Locale.US, 0, 1_000_000_000, true, true, "internal");
    static final Currency CREDITS = new Currency("credits", "Credit", "Credits", "C", true, 0, Locale.US, 0, 1_000_000, true, false, "internal");

    final Map<String, Transaction> written = new ConcurrentHashMap<>();
    final EconomyStore store = new EconomyStore() {
        @Override public StoredAccount loadAccount(UUID id) { return null; }
        @Override public boolean externalIdExists(String id) { return written.values().stream().anyMatch(t -> id.equals(t.externalId())); }
        @Override public Transaction transaction(String id) { return written.get(id); }
        @Override public void save(List<AccountSnapshot> accounts, List<Transaction> transactions, Map<String, String> reversals) {
            transactions.forEach(t -> written.put(t.id(), t));
        }
    };
    final IoThread io = new IoThread(Logger.getLogger("test"));
    final EconomyService economy = new EconomyService(store, io, new CurrencyRegistry(List.of(MONEY, CREDITS)), Logger.getLogger("test"));
    volatile CreditsService.Settings settings = new CreditsService.Settings(true, 1000.0, 2, 10);
    final CreditsService credits = new CreditsService(economy, () -> settings);
    final UUID player = UUID.randomUUID(), friend = UUID.randomUUID();

    @AfterEach void stop() { io.shutdown(); }

    @Test void topUpsAreIdempotentAndAddTheBonus() {
        CreditsService.TopUp first = credits.topUp(player, 150, "tebex-42");
        assertTrue(first.success());
        assertEquals(15, first.bonusAmount(), "10 percent of 150");
        assertEquals(TransactionKind.TOPUP, first.delivery().transaction().kind());
        assertEquals(TransactionKind.BONUS, first.bonus().transaction().kind());
        assertEquals(165, economy.balance(player, CREDITS));
        CreditsService.TopUp again = credits.topUp(player, 150, "tebex-42");
        assertEquals(Status.ALREADY_APPLIED, again.delivery().status());
        assertNull(again.bonus());
        economy.flush();
        assertEquals(Status.ALREADY_APPLIED, credits.topUp(player, 150, "tebex-42").delivery().status(), "also after a write");
        assertEquals(165, economy.balance(player, CREDITS));
    }

    @Test void noBonusWhenTheBonusIsZero() {
        settings = new CreditsService.Settings(true, 1000.0, 1, 0);
        CreditsService.TopUp done = credits.topUp(player, 7, "order-7");
        assertNull(done.bonus());
        assertEquals(7, economy.balance(player, CREDITS));
    }

    @Test void exchangeUsesTheRateAndTheMinimum() {
        economy.deposit(player, CREDITS, 5, TransactionKind.GIVE, null);
        assertEquals(Status.INVALID_AMOUNT, credits.exchange(player, 1).status(), "below the minimum of 2");
        assertTrue(credits.exchange(player, 3).success());
        assertEquals(2, economy.balance(player, CREDITS));
        assertEquals(300_000, economy.balance(player, MONEY), "3 credits x 1000.00");
        assertEquals(Status.INSUFFICIENT_FUNDS, credits.exchange(player, 4).status());
        settings = new CreditsService.Settings(false, 1000.0, 1, 0);
        assertEquals(Status.FAILED, credits.exchange(player, 2).status(), "exchange switched off");
        assertEquals(2, economy.balance(player, CREDITS));
    }

    @Test void creditsCanBeSentBetweenPlayers() {
        economy.deposit(player, CREDITS, 10, TransactionKind.GIVE, null);
        assertTrue(credits.send(player, friend, 4).success());
        assertEquals(6, economy.balance(player, CREDITS));
        assertEquals(4, economy.balance(friend, CREDITS));
        assertEquals(Status.INSUFFICIENT_FUNDS, credits.send(player, friend, 7).status());
    }
}
