package dev.dymarket.api.event;

import dev.dymarket.auction.Listing;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a listing expired and its item went to the seller's collection box. Not cancellable. Fired
 * asynchronously on DyMarket's IO thread; listeners must not touch worlds, entities or inventories directly.
 */
public class AuctionExpireEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Listing listing;

    public AuctionExpireEvent(boolean async, Listing listing) {
        super(async);
        this.listing = listing;
    }

    public Listing getListing() { return listing; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
