package dev.dymarket.economy;

import dev.dymarket.economy.TransactionResult.Status;
import dev.dymarket.storage.IoThread;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Balances in memory, written behind to storage. Thread-safe: each account is changed only while holding its
 * monitor, and a transfer takes both monitors in UUID order, so no two operations can interleave on one account
 * and no lock order can deadlock. No operation leaves a balance below zero or above the currency's maximum;
 * such operations fail without changing anything. Every change is logged as a {@link Transaction}.
 */
public class EconomyService {
    /** Asked before every change (for {@code BalanceChangeEvent}); false cancels it. Delta is signed. */
    public interface ChangeGuard {
        boolean allow(UUID player, Currency currency, long delta, TransactionKind kind);
    }

    private final EconomyStore store;
    private final IoThread io;
    private final Logger logger;
    private volatile CurrencyRegistry currencies;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Transaction> pending = new ConcurrentLinkedQueue<>();
    /** Logged but not yet written transactions, by id, so rollback and the log see them at once. */
    private final Map<String, Transaction> unsaved = new ConcurrentHashMap<>();
    private final Map<String, String> unsavedExternal = new ConcurrentHashMap<>();
    /** Original transaction id to the id of the transaction that reversed it, not yet written. */
    private final Map<String, String> reversals = new ConcurrentHashMap<>();
    private final Map<String, ExternalEconomy> externals = new ConcurrentHashMap<>();
    private final Object externalLock = new Object();
    private final Object rollbackLock = new Object();
    private final Object flushLock = new Object();
    private volatile ChangeGuard guard = (player, currency, delta, kind) -> true;
    private volatile Consumer<Transaction> listener = transaction -> { };

    public EconomyService(EconomyStore store, IoThread io, CurrencyRegistry currencies, Logger logger) {
        this.store = store;
        this.io = io;
        this.currencies = currencies;
        this.logger = logger;
    }

    public CurrencyRegistry currencies() { return currencies; }
    public void currencies(CurrencyRegistry registry) { this.currencies = registry; }
    public void guard(ChangeGuard guard) { this.guard = guard == null ? (p, c, d, k) -> true : guard; }
    /** Called after every logged transaction, outside the account locks (staff alerts). */
    public void listener(Consumer<Transaction> listener) { this.listener = listener == null ? t -> { } : listener; }

    public void external(String currency, ExternalEconomy economy) {
        if (economy == null) externals.remove(currency);
        else externals.put(currency, economy);
    }

    // ---- accounts -------------------------------------------------------------------------------------------

    /** The account, loaded from storage on first use (waiting for the IO thread when called elsewhere). */
    public Account account(UUID id) {
        Account account = accounts.get(id);
        if (account != null) {
            account.touch();
            return account;
        }
        EconomyStore.StoredAccount stored;
        try {
            stored = io.call(() -> store.loadAccount(id));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not load the account of " + id + ": " + failure.getMessage(), failure);
        }
        Account created = new Account(id, stored == null ? null : stored.name(), stored == null ? null : stored.balances());
        Account existing = accounts.putIfAbsent(id, created);
        return existing != null ? existing : created;
    }

    /** The account when it is already in memory, else null. Never touches storage. */
    public Account cached(UUID id) {
        return accounts.get(id);
    }

    public boolean loaded(UUID id) {
        return accounts.containsKey(id);
    }

    /** Loads an account and gives it a row for each currency (the starting balance) and the current name. */
    public void ensure(UUID id, String name) {
        Account account = account(id);
        synchronized (account) {
            if (name != null && !name.equals(account.name())) {
                account.name(name);
                account.dirty(true);
            }
            for (Currency currency : currencies.all())
                if (!currency.external() && !account.has(currency.id())) account.put(currency, currency.startingBalance());
        }
    }

    public long balance(UUID id, Currency currency) {
        if (currency.external()) {
            ExternalEconomy external = externals.get(currency.id());
            return external != null && external.available() ? external.balance(id) : 0;
        }
        Account account = account(id);
        synchronized (account) {
            return account.get(currency);
        }
    }

    /** Balance from memory only; -1 when the account is not loaded. */
    public long cachedBalance(UUID id, Currency currency) {
        if (currency.external()) return balance(id, currency);
        Account account = accounts.get(id);
        if (account == null) return -1;
        synchronized (account) {
            return account.get(currency);
        }
    }

    // ---- operations -----------------------------------------------------------------------------------------

    public TransactionResult deposit(UUID to, Currency currency, long amount, TransactionKind kind, String note) {
        return move(null, to, currency, amount, kind, note, null, false);
    }

    public TransactionResult withdraw(UUID from, Currency currency, long amount, TransactionKind kind, String note) {
        return move(from, null, currency, amount, kind, note, null, false);
    }

    /** Player-to-player transfers of a currency that is not transferable are refused for {@code PAY}. */
    public TransactionResult transfer(UUID from, UUID to, Currency currency, long amount, TransactionKind kind, String note) {
        if (currency == null) return TransactionResult.of(Status.UNKNOWN_CURRENCY);
        if (kind == TransactionKind.PAY && !currency.transferable()) return TransactionResult.of(Status.NOT_TRANSFERABLE);
        return move(from, to, currency, amount, kind, note, null, false);
    }

    /**
     * A deposit with an external id (a web store order). The same id is applied once: a second call returns
     * {@code ALREADY_APPLIED} and changes nothing. Checks storage, so call it off the server threads.
     */
    public TransactionResult give(UUID to, Currency currency, long amount, TransactionKind kind, String note, String externalId) {
        if (externalId == null || externalId.isBlank()) return move(null, to, currency, amount, kind, note, null, false);
        synchronized (externalLock) {
            try {
                if (unsavedExternal.containsKey(externalId) || io.call(() -> store.externalIdExists(externalId)))
                    return TransactionResult.of(Status.ALREADY_APPLIED);
            } catch (Exception failure) {
                logger.warning("Could not check transaction id " + externalId + ": " + failure.getMessage());
                return TransactionResult.of(Status.UNAVAILABLE);
            }
            return move(null, to, currency, amount, kind, note, externalId, false);
        }
    }

    /** Sets a balance exactly, logged as {@code SET} with the difference. */
    public TransactionResult set(UUID id, Currency currency, long amount, String note) {
        if (currency == null) return TransactionResult.of(Status.UNKNOWN_CURRENCY);
        if (amount < 0 || amount > currency.maxBalance()) return TransactionResult.of(amount < 0 ? Status.INVALID_AMOUNT : Status.MAX_BALANCE);
        if (currency.external()) {
            long delta = amount - balance(id, currency);
            if (delta == 0) return TransactionResult.success(amount, List.of());
            TransactionResult result = delta > 0 ? move(null, id, currency, delta, TransactionKind.SET, note, null, false)
                    : move(id, null, currency, -delta, TransactionKind.SET, note, null, false);
            return result.success() ? TransactionResult.success(amount, result.transactions()) : result;
        }
        Account current = loadOrNull(id);
        if (current == null) return TransactionResult.of(Status.UNAVAILABLE);
        long before;
        synchronized (current) {
            before = current.get(currency);
        }
        if (before != amount && !guard.allow(id, currency, amount - before, TransactionKind.SET)) return TransactionResult.of(Status.CANCELLED);
        while (true) {
            Account account = loadOrNull(id);
            if (account == null) return TransactionResult.of(Status.UNAVAILABLE);
            Transaction logged;
            synchronized (account) {
                if (account.evicted()) continue;
                long old = account.get(currency);
                if (old == amount) return TransactionResult.success(amount, List.of());
                account.put(currency, amount);
                boolean up = amount > old;
                logged = new Transaction(Transaction.newId(), System.currentTimeMillis(), TransactionKind.SET, currency.id(),
                        up ? null : id, up ? id : null, Math.abs(amount - old), up ? 0 : amount, up ? amount : 0, note, null, null);
                record(logged);
            }
            listener.accept(logged);
            return TransactionResult.success(amount, List.of(logged));
        }
    }

    /**
     * Sets an imported balance, logged as {@code IMPORT}, only when the player's balance is still zero or the
     * untouched starting balance. Returns false when the player already has money of their own in DyMarket.
     */
    public boolean importBalance(UUID id, String name, Currency currency, long amount) {
        if (currency == null || currency.external() || amount < 0) return false;
        while (true) {
            Account account = loadOrNull(id);
            if (account == null) return false;
            Transaction logged;
            synchronized (account) {
                if (account.evicted()) continue;
                long old = account.get(currency);
                if (old != 0 && old != currency.startingBalance()) return false;
                long value = Math.min(amount, currency.maxBalance());
                if (name != null && account.name() == null) account.name(name);
                account.put(currency, value);
                if (old == value) return true;
                boolean up = value > old;
                logged = new Transaction(Transaction.newId(), System.currentTimeMillis(), TransactionKind.IMPORT, currency.id(),
                        up ? null : id, up ? id : null, Math.abs(value - old), up ? 0 : value, up ? value : 0, "import", null, null);
                record(logged);
            }
            listener.accept(logged);
            return true;
        }
    }

    /**
     * Turns one currency into another on the same account in one step (credits to money). Both changes are
     * checked before either happens and are logged as two {@code EXCHANGE} transactions.
     */
    public TransactionResult convert(UUID id, Currency from, long fromAmount, Currency to, long toAmount, String note) {
        if (from == null || to == null) return TransactionResult.of(Status.UNKNOWN_CURRENCY);
        if (fromAmount <= 0 || toAmount <= 0 || from.id().equals(to.id())) return TransactionResult.of(Status.INVALID_AMOUNT);
        if (!guard.allow(id, from, -fromAmount, TransactionKind.EXCHANGE) || !guard.allow(id, to, toAmount, TransactionKind.EXCHANGE))
            return TransactionResult.of(Status.CANCELLED);
        if (from.external() || to.external()) {
            TransactionResult taken = move(id, null, from, fromAmount, TransactionKind.EXCHANGE, note, null, false);
            if (!taken.success()) return taken;
            TransactionResult added = move(null, id, to, toAmount, TransactionKind.EXCHANGE, note, null, false);
            if (added.success()) return TransactionResult.success(added.balance(), concat(taken.transactions(), added.transactions()));
            move(null, id, from, fromAmount, TransactionKind.REFUND, "exchange failed", null, false);
            return added;
        }
        while (true) {
            Account account = loadOrNull(id);
            if (account == null) return TransactionResult.of(Status.UNAVAILABLE);
            List<Transaction> logged = new ArrayList<>();
            long result;
            synchronized (account) {
                if (account.evicted()) continue;
                long fromBalance = account.get(from), toBalance = account.get(to);
                if (fromBalance < fromAmount) return TransactionResult.of(Status.INSUFFICIENT_FUNDS, fromBalance);
                if (toBalance > to.maxBalance() - toAmount) return TransactionResult.of(Status.MAX_BALANCE, toBalance);
                account.put(from, fromBalance - fromAmount);
                account.put(to, toBalance + toAmount);
                long now = System.currentTimeMillis();
                logged.add(new Transaction(Transaction.newId(), now, TransactionKind.EXCHANGE, from.id(), id, null, fromAmount,
                        fromBalance - fromAmount, 0, note, null, null));
                logged.add(new Transaction(Transaction.newId(), now, TransactionKind.EXCHANGE, to.id(), null, id, toAmount,
                        0, toBalance + toAmount, note, null, null));
                logged.forEach(this::record);
                result = toBalance + toAmount;
            }
            logged.forEach(listener);
            return TransactionResult.success(result, logged);
        }
    }

    /**
     * Undoes a transaction with the inverse one and marks the original as reversed. Refused when already
     * reversed or when the inverse would make a balance negative, unless {@code force} clamps it at zero.
     */
    public TransactionResult rollback(String id, boolean force) {
        synchronized (rollbackLock) {
            Transaction original = unsaved.get(id);
            try {
                if (original == null) original = io.call(() -> store.transaction(id));
            } catch (Exception failure) {
                logger.warning("Could not read transaction " + id + ": " + failure.getMessage());
                return TransactionResult.of(Status.UNAVAILABLE);
            }
            if (original == null) return TransactionResult.of(Status.NOT_FOUND);
            if (original.reversedBy() != null || reversals.containsKey(id)) return TransactionResult.of(Status.ALREADY_REVERSED);
            Currency currency = currencies.get(original.currency());
            if (currency == null) return TransactionResult.of(Status.UNKNOWN_CURRENCY);
            TransactionResult result = move(original.to(), original.from(), currency, original.amount(), TransactionKind.ROLLBACK,
                    "rollback " + id, null, force);
            if (result.success()) {
                String reversal = result.transaction().id();
                reversals.put(id, reversal);
                unsaved.computeIfPresent(id, (key, value) -> value.withReversedBy(reversal));
            }
            return result;
        }
    }

    /**
     * Moves every internal balance of {@code from} to {@code to} and leaves {@code from} at zero, one
     * {@code MERGE} transaction per currency. Fails without changes when any sum would pass the maximum.
     */
    public TransactionResult merge(UUID from, UUID to) {
        if (from.equals(to)) return TransactionResult.of(Status.SAME_ACCOUNT);
        while (true) {
            Account source = loadOrNull(from), target = loadOrNull(to);
            if (source == null || target == null) return TransactionResult.of(Status.UNAVAILABLE);
            List<Transaction> logged = new ArrayList<>();
            TransactionResult result = withLocks(source, target, () -> {
                if (source.evicted() || target.evicted()) return null;
                for (Currency currency : currencies.all()) {
                    if (currency.external()) continue;
                    if (target.get(currency) > currency.maxBalance() - source.get(currency))
                        return TransactionResult.of(Status.MAX_BALANCE);
                }
                long now = System.currentTimeMillis();
                for (Currency currency : currencies.all()) {
                    if (currency.external()) continue;
                    long amount = source.get(currency);
                    source.put(currency, 0);
                    if (amount <= 0) continue;
                    long after = target.get(currency) + amount;
                    target.put(currency, after);
                    Transaction transaction = new Transaction(Transaction.newId(), now, TransactionKind.MERGE, currency.id(), from, to,
                            amount, 0, after, "merge", null, null);
                    record(transaction);
                    logged.add(transaction);
                }
                return TransactionResult.success(0, logged);
            });
            if (result == null) continue;
            logged.forEach(listener);
            return result;
        }
    }

    private TransactionResult move(UUID from, UUID to, Currency currency, long amount, TransactionKind kind, String note,
                                   String externalId, boolean clamp) {
        if (currency == null) return TransactionResult.of(Status.UNKNOWN_CURRENCY);
        if (amount <= 0) return TransactionResult.of(Status.INVALID_AMOUNT);
        if (from == null && to == null) return TransactionResult.of(Status.INVALID_AMOUNT);
        if (from != null && from.equals(to)) return TransactionResult.of(Status.SAME_ACCOUNT);
        if (from != null && !guard.allow(from, currency, -amount, kind)) return TransactionResult.of(Status.CANCELLED);
        if (to != null && !guard.allow(to, currency, amount, kind)) return TransactionResult.of(Status.CANCELLED);
        if (currency.external()) return moveExternal(from, to, currency, amount, kind, note, externalId);
        while (true) {
            Account source = from == null ? null : loadOrNull(from);
            Account target = to == null ? null : loadOrNull(to);
            if (from != null && source == null || to != null && target == null) return TransactionResult.of(Status.UNAVAILABLE);
            Transaction[] logged = new Transaction[1];
            TransactionResult result = withLocks(source, target, () -> {
                if (source != null && source.evicted() || target != null && target.evicted()) return null;
                long fromBalance = source == null ? 0 : source.get(currency);
                long toBalance = target == null ? 0 : target.get(currency);
                long moved = amount;
                if (source != null && fromBalance < moved) {
                    if (!clamp || fromBalance <= 0) return TransactionResult.of(Status.INSUFFICIENT_FUNDS, fromBalance);
                    moved = fromBalance;
                }
                if (target != null && toBalance > currency.maxBalance() - moved) {
                    if (!clamp || toBalance >= currency.maxBalance()) return TransactionResult.of(Status.MAX_BALANCE, toBalance);
                    moved = currency.maxBalance() - toBalance;
                }
                if (source != null) source.put(currency, fromBalance - moved);
                if (target != null) target.put(currency, toBalance + moved);
                logged[0] = new Transaction(Transaction.newId(), System.currentTimeMillis(), kind, currency.id(), from, to, moved,
                        source == null ? 0 : fromBalance - moved, target == null ? 0 : toBalance + moved, note, externalId, null);
                record(logged[0]);
                return TransactionResult.success(source != null ? fromBalance - moved : toBalance + moved, List.of(logged[0]));
            });
            if (result == null) continue;
            if (logged[0] != null) listener.accept(logged[0]);
            return result;
        }
    }

    private TransactionResult moveExternal(UUID from, UUID to, Currency currency, long amount, TransactionKind kind, String note,
                                           String externalId) {
        ExternalEconomy external = externals.get(currency.id());
        if (external == null || !external.available()) return TransactionResult.of(Status.UNAVAILABLE);
        if (from != null && !external.withdraw(from, amount)) return TransactionResult.of(Status.INSUFFICIENT_FUNDS, external.balance(from));
        if (to != null && !external.deposit(to, amount)) {
            if (from != null) external.deposit(from, amount);
            return TransactionResult.of(Status.FAILED);
        }
        long fromAfter = from == null ? 0 : external.balance(from), toAfter = to == null ? 0 : external.balance(to);
        Transaction transaction = new Transaction(Transaction.newId(), System.currentTimeMillis(), kind, currency.id(), from, to,
                amount, fromAfter, toAfter, note, externalId, null);
        record(transaction);
        listener.accept(transaction);
        return TransactionResult.success(from != null ? fromAfter : toAfter, List.of(transaction));
    }

    private void record(Transaction transaction) {
        unsaved.put(transaction.id(), transaction);
        if (transaction.externalId() != null) unsavedExternal.put(transaction.externalId(), transaction.id());
        pending.add(transaction);
    }

    private Account loadOrNull(UUID id) {
        try {
            return account(id);
        } catch (RuntimeException failure) {
            logger.warning(failure.getMessage());
            return null;
        }
    }

    /** Runs the body holding both monitors, taken in UUID order; a null body result means "retry". */
    private static <R> R withLocks(Account x, Account y, Supplier<R> body) {
        Account first = x == null ? y : x;
        Account second = x == null ? null : y;
        if (second != null && first.id().compareTo(second.id()) > 0) {
            Account swap = first;
            first = second;
            second = swap;
        }
        synchronized (first) {
            if (second == null) return body.get();
            synchronized (second) {
                return body.get();
            }
        }
    }

    private static List<Transaction> concat(List<Transaction> a, List<Transaction> b) {
        List<Transaction> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    // ---- log and persistence --------------------------------------------------------------------------------

    /** A logged transaction, including ones not written yet. Reads storage, so call it off the server threads. */
    public Transaction transaction(String id) throws Exception {
        Transaction transaction = unsaved.get(id);
        if (transaction == null) transaction = io.call(() -> store.transaction(id));
        if (transaction != null && transaction.reversedBy() == null && reversals.containsKey(id))
            transaction = transaction.withReversedBy(reversals.get(id));
        return transaction;
    }

    /** Transactions waiting to be written. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Writes changed balances, new transactions and reversal marks in one storage transaction. On failure
     * everything stays queued for the next attempt.
     */
    public void flush() {
        synchronized (flushLock) {
            List<EconomyStore.AccountSnapshot> snapshots = new ArrayList<>();
            List<Account> taken = new ArrayList<>();
            for (Account account : accounts.values()) {
                synchronized (account) {
                    if (!account.dirty()) continue;
                    snapshots.add(new EconomyStore.AccountSnapshot(account.id(), account.name(), account.snapshot()));
                    account.dirty(false);
                    taken.add(account);
                }
            }
            List<Transaction> transactions = new ArrayList<>();
            for (Transaction next; (next = pending.poll()) != null; ) transactions.add(next);
            Map<String, String> marks = new HashMap<>(reversals);
            if (snapshots.isEmpty() && transactions.isEmpty() && marks.isEmpty()) return;
            try {
                io.call(() -> {
                    store.save(snapshots, transactions, marks);
                    return null;
                });
                for (Transaction transaction : transactions) {
                    unsaved.remove(transaction.id());
                    if (transaction.externalId() != null) unsavedExternal.remove(transaction.externalId());
                }
                marks.forEach(reversals::remove);
            } catch (Exception failure) {
                logger.warning("Could not save balances (will retry): " + failure.getMessage());
                for (Account account : taken) synchronized (account) {
                    account.dirty(true);
                }
                List<Transaction> later = new ArrayList<>();
                for (Transaction next; (next = pending.poll()) != null; ) later.add(next);
                pending.addAll(transactions);
                pending.addAll(later);
            }
        }
    }

    /**
     * Drops saved, idle accounts from memory once more than {@code max} are cached, oldest first.
     * {@code keep} protects accounts of online players.
     */
    public void evictIdle(Predicate<UUID> keep, int max) {
        if (accounts.size() <= max) return;
        List<Account> candidates = new ArrayList<>(accounts.values());
        candidates.sort(Comparator.comparingLong(Account::lastAccess));
        for (Account account : candidates) {
            if (accounts.size() <= max) return;
            if (keep.test(account.id())) continue;
            synchronized (account) {
                if (account.dirty()) continue;
                account.evict();
                accounts.remove(account.id(), account);
            }
        }
    }

    public int cachedCount() {
        return accounts.size();
    }

    /** UUIDs of cached accounts whose stored name matches, ignoring case (Locale.ROOT). */
    public UUID cachedByName(String name) {
        for (Account account : accounts.values())
            if (account.name() != null && account.name().equalsIgnoreCase(name)) return account.id();
        return null;
    }
}
