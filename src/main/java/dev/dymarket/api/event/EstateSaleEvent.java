package dev.dymarket.api.event;

import dev.dymarket.estate.EstateListing;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired before a player buys an estate sale listing, before any money moves or claims change hands; cancelling it
 * stops the purchase. Fired on the buyer's own thread (the region thread on Folia). Someone else may buy the same
 * listing at the same moment; only one purchase succeeds.
 */
public class EstateSaleEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final EstateListing listing;
    private final UUID buyer;
    private boolean cancelled;

    public EstateSaleEvent(boolean async, EstateListing listing, UUID buyer) {
        super(async);
        this.listing = listing;
        this.buyer = buyer;
    }

    public EstateListing getListing() { return listing; }
    public UUID getBuyer() { return buyer; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
