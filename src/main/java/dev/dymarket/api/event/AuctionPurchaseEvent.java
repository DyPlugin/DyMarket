package dev.dymarket.api.event;

import dev.dymarket.auction.Listing;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired before a player buys an auction listing, before any money moves; cancelling it stops the purchase. Fired
 * on the buyer's own thread (the region thread on Folia). The listing may still be bought by someone else at the
 * same moment; only one purchase succeeds.
 */
public class AuctionPurchaseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Listing listing;
    private final UUID buyer;
    private boolean cancelled;

    public AuctionPurchaseEvent(boolean async, Listing listing, UUID buyer) {
        super(async);
        this.listing = listing;
        this.buyer = buyer;
    }

    public Listing getListing() { return listing; }
    public UUID getBuyer() { return buyer; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
