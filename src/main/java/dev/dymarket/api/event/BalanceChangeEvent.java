package dev.dymarket.api.event;

import dev.dymarket.economy.TransactionKind;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fired before a balance changes; cancelling it stops the change. Fired on the thread that asked for the change
 * (a region thread for commands and Vault calls, DyMarket's IO thread for web top-ups and admin commands), so
 * {@link #isAsynchronous()} tells which. Listeners must be fast and must not wait on the server thread.
 * A transfer fires one event per account.
 */
public class BalanceChangeEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String currency;
    private final BigDecimal amount;
    private final TransactionKind kind;
    private boolean cancelled;

    public BalanceChangeEvent(boolean async, UUID player, String currency, BigDecimal amount, TransactionKind kind) {
        super(async);
        this.player = player;
        this.currency = currency;
        this.amount = amount;
        this.kind = kind;
    }

    public UUID getPlayer() { return player; }
    public String getCurrency() { return currency; }
    /** The change: positive adds to the balance, negative takes from it. */
    public BigDecimal getAmount() { return amount; }
    public TransactionKind getKind() { return kind; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
