package dev.dymarket.api;

import dev.dymarket.DyMarket;
import dev.dymarket.auction.AuctionModule;
import dev.dymarket.auction.Listing;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.TransactionKind;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.economy.TransactionResult.Status;
import dev.dymarket.estate.EstateListing;
import dev.dymarket.estate.EstateModule;
import dev.dymarket.estate.Lease;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * DyMarket for other plugins. The asynchronous methods run on DyMarket's IO thread and never block the
 * caller; the {@code trySync} variants answer on the calling thread (an offline player who is not in memory
 * is loaded first, which waits for the IO thread). Amounts are in major units ("12.50"). Changes made here are
 * logged with the given note and fire {@link dev.dymarket.api.event.BalanceChangeEvent}.
 */
public final class DyMarketAPI {
    private DyMarketAPI() {
    }

    private static DyMarket plugin() {
        DyMarket plugin = DyMarket.getInstance();
        return plugin != null && plugin.isEnabled() && plugin.economy() != null ? plugin : null;
    }

    public static boolean isAvailable() {
        return plugin() != null;
    }

    /** The enabled currencies in config order. */
    public static List<Currency> getCurrencies() {
        DyMarket plugin = plugin();
        return plugin == null ? List.of() : plugin.currencies().all();
    }

    /**
     * The active auction listings, newest first; empty while the auction module is off. A snapshot from memory,
     * safe on any thread. The listing objects are live: their status changes when they are sold, cancelled or
     * expire, so check {@link Listing#status()} before relying on one later.
     */
    public static List<Listing> getAuctionListings() {
        DyMarket plugin = plugin();
        AuctionModule auction = plugin == null ? null : plugin.auction();
        if (auction == null) return List.of();
        List<Listing> active = auction.service().active();
        active.sort(java.util.Comparator.comparingLong(Listing::created).reversed());
        return java.util.Collections.unmodifiableList(active);
    }

    /**
     * The estate listings that can be bought or rented now, newest first; empty while the estate module is off
     * (turned off, or DyClaim 1.3.1+ missing). A snapshot from memory, safe on any thread; the listing objects are
     * live, so check {@link EstateListing#status()} before relying on one later.
     */
    public static List<EstateListing> getEstateListings() {
        DyMarket plugin = plugin();
        EstateModule estate = plugin == null ? null : plugin.estate();
        if (estate == null) return List.of();
        List<EstateListing> active = estate.service().active();
        active.sort(java.util.Comparator.comparingLong(EstateListing::created).reversed());
        return java.util.Collections.unmodifiableList(active);
    }

    /**
     * The running leases (paid or in grace) where the player is the tenant or the owner; empty while the estate
     * module is off. A snapshot from memory, safe on any thread; check {@link Lease#status()} before relying on one later.
     */
    public static List<Lease> getLeases(UUID player) {
        DyMarket plugin = plugin();
        EstateModule estate = plugin == null ? null : plugin.estate();
        if (estate == null || player == null) return List.of();
        List<Lease> leases = estate.service().leasesOf(player);
        leases.sort(java.util.Comparator.comparingLong(Lease::started));
        return java.util.Collections.unmodifiableList(leases);
    }

    /** The balance in major units, or zero when DyMarket or the currency is unavailable. */
    public static BigDecimal getBalance(UUID player, String currency) {
        DyMarket plugin = plugin();
        Currency found = plugin == null ? null : plugin.currencies().get(currency);
        if (found == null) return BigDecimal.ZERO;
        return found.toMajor(plugin.economy().balance(player, found));
    }

    public static TransactionResult depositSync(UUID player, String currency, BigDecimal amount, String note) {
        return run((plugin, found, minor) -> plugin.economy().deposit(player, found, minor, TransactionKind.GIVE, note), currency, amount);
    }

    public static TransactionResult withdrawSync(UUID player, String currency, BigDecimal amount, String note) {
        return run((plugin, found, minor) -> plugin.economy().withdraw(player, found, minor, TransactionKind.TAKE, note), currency, amount);
    }

    public static TransactionResult transferSync(UUID from, UUID to, String currency, BigDecimal amount, String note) {
        return run((plugin, found, minor) -> plugin.economy().transfer(from, to, found, minor, TransactionKind.PAY, note), currency, amount);
    }

    /** Same as {@link #depositSync} but never waits. */
    public static CompletableFuture<TransactionResult> deposit(UUID player, String currency, BigDecimal amount, String note) {
        return async(() -> depositSync(player, currency, amount, note));
    }

    public static CompletableFuture<TransactionResult> withdraw(UUID player, String currency, BigDecimal amount, String note) {
        return async(() -> withdrawSync(player, currency, amount, note));
    }

    public static CompletableFuture<TransactionResult> transfer(UUID from, UUID to, String currency, BigDecimal amount, String note) {
        return async(() -> transferSync(from, to, currency, amount, note));
    }

    /** Alias kept for callers that prefer the "try" naming. */
    public static TransactionResult trySyncDeposit(UUID player, String currency, BigDecimal amount, String note) {
        return depositSync(player, currency, amount, note);
    }

    public static TransactionResult trySyncWithdraw(UUID player, String currency, BigDecimal amount, String note) {
        return withdrawSync(player, currency, amount, note);
    }

    public static TransactionResult trySyncTransfer(UUID from, UUID to, String currency, BigDecimal amount, String note) {
        return transferSync(from, to, currency, amount, note);
    }

    private interface Operation {
        TransactionResult apply(DyMarket plugin, Currency currency, long minor);
    }

    private static TransactionResult run(Operation operation, String currency, BigDecimal amount) {
        DyMarket plugin = plugin();
        if (plugin == null) return TransactionResult.of(Status.UNAVAILABLE);
        Currency found = plugin.currencies().get(currency);
        if (found == null) return TransactionResult.of(Status.UNKNOWN_CURRENCY);
        if (amount == null || amount.signum() <= 0) return TransactionResult.of(Status.INVALID_AMOUNT);
        long minor;
        try {
            minor = found.toMinor(amount);
        } catch (ArithmeticException tooLarge) {
            return TransactionResult.of(Status.INVALID_AMOUNT);
        }
        return operation.apply(plugin, found, minor);
    }

    private static CompletableFuture<TransactionResult> async(Supplier<TransactionResult> task) {
        DyMarket plugin = plugin();
        if (plugin == null) return CompletableFuture.completedFuture(TransactionResult.of(Status.UNAVAILABLE));
        return plugin.io().submit(task::get);
    }
}
