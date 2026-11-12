package dev.dymarket.credits;

import dev.dymarket.economy.Currency;
import dev.dymarket.economy.EconomyService;
import dev.dymarket.economy.TransactionKind;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.economy.TransactionResult.Status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The web-credit bridge: idempotent top-ups from web stores (RCON), the top-up bonus, player-to-player credit
 * transfers and the credit to money exchange.
 */
public final class CreditsService {
    /** Settings read from config.yml on reload. {@code min} is in whole credits. */
    public record Settings(boolean exchangeEnabled, double rate, double min, double bonusPercent) { }

    /** A top-up: the delivery itself and the bonus on top (null when there is none). */
    public record TopUp(TransactionResult delivery, TransactionResult bonus, long bonusAmount) {
        public boolean success() { return delivery.success(); }
    }

    private final EconomyService economy;
    private final Supplier<Settings> settings;

    public CreditsService(EconomyService economy, Supplier<Settings> settings) {
        this.economy = economy;
        this.settings = settings;
    }

    public Currency credits() {
        return economy.currencies().credits();
    }

    public boolean enabled() {
        return credits() != null;
    }

    /** Smallest exchange in credit minor units. */
    public long minimumExchange() {
        Currency credits = credits();
        return credits == null ? 0 : Math.max(1, credits.toMinor(settings.get().min()));
    }

    /** Money received for an amount of credits (minor units of each). */
    public long exchangeValue(long creditsMinor) {
        Currency credits = credits(), money = economy.currencies().primary();
        if (credits == null || money == null) return 0;
        BigDecimal value = credits.toMajor(creditsMinor).multiply(BigDecimal.valueOf(settings.get().rate()));
        return money.toMinor(value.setScale(money.decimals(), RoundingMode.DOWN));
    }

    public TransactionResult exchange(UUID player, long creditsMinor) {
        Currency credits = credits(), money = economy.currencies().primary();
        if (credits == null || money == null || credits.id().equals(money.id())) return TransactionResult.of(Status.UNKNOWN_CURRENCY);
        if (!settings.get().exchangeEnabled()) return TransactionResult.of(Status.FAILED);
        if (creditsMinor < minimumExchange()) return TransactionResult.of(Status.INVALID_AMOUNT);
        long value = exchangeValue(creditsMinor);
        if (value <= 0) return TransactionResult.of(Status.INVALID_AMOUNT);
        return economy.convert(player, credits, creditsMinor, money, value, "credits exchange");
    }

    public TransactionResult send(UUID from, UUID to, long creditsMinor) {
        Currency credits = credits();
        if (credits == null) return TransactionResult.of(Status.UNKNOWN_CURRENCY);
        return economy.transfer(from, to, credits, creditsMinor, TransactionKind.PAY, "credits");
    }

    /**
     * Delivers a web store order once per transaction id. A repeated id changes nothing and reports
     * {@code ALREADY_APPLIED}. The bonus ({@code topup-bonus-percent}) is added as a separate {@code BONUS}
     * transaction. Reads storage, so run it off the server threads.
     */
    public TopUp topUp(UUID player, long creditsMinor, String txid) {
        Currency credits = credits();
        if (credits == null) return new TopUp(TransactionResult.of(Status.UNKNOWN_CURRENCY), null, 0);
        TransactionResult delivery = economy.give(player, credits, creditsMinor, TransactionKind.TOPUP, "topup " + txid, txid);
        if (!delivery.success()) return new TopUp(delivery, null, 0);
        long bonus = BigDecimal.valueOf(creditsMinor).multiply(BigDecimal.valueOf(settings.get().bonusPercent()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValue();
        if (bonus <= 0) return new TopUp(delivery, null, 0);
        TransactionResult bonusResult = economy.deposit(player, credits, bonus, TransactionKind.BONUS, "bonus " + txid);
        return new TopUp(delivery, bonusResult, bonusResult.success() ? bonus : 0);
    }
}
