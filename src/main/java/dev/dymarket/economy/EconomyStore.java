package dev.dymarket.economy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the economy needs from storage. Every method runs on the DyMarket-IO thread. */
public interface EconomyStore {
    record StoredAccount(String name, Map<String, Long> balances) { }

    record AccountSnapshot(UUID id, String name, Map<String, Long> balances) { }

    /** The stored account, or null when the player has none yet. */
    StoredAccount loadAccount(UUID id) throws Exception;

    boolean externalIdExists(String externalId) throws Exception;

    /** A logged transaction by id, or null. */
    Transaction transaction(String id) throws Exception;

    /** Writes balances, new transactions and reversal marks in one database transaction. */
    void save(List<AccountSnapshot> accounts, List<Transaction> transactions, Map<String, String> reversals) throws Exception;
}
