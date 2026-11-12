package dev.dymarket.api.event;

import dev.dymarket.auction.Listing;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired before a seller (or a staff member with {@code dymarket.admin.ah}) takes a listing off sale with
 * {@code /ah cancel} or the GUI; cancelling it keeps the listing on sale. Fired on the calling thread (the
 * player's region thread on Folia, or the console thread); {@link #isAsynchronous()} tells which. Staff
 * removals through {@code /dymarket ah remove|purge} do not fire it.
 */
public class AuctionCancelEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Listing listing;
    private final UUID actor;
    private final boolean admin;
    private boolean cancelled;

    public AuctionCancelEvent(boolean async, Listing listing, UUID actor, boolean admin) {
        super(async);
        this.listing = listing;
        this.actor = actor;
        this.admin = admin;
    }

    public Listing getListing() { return listing; }
    /** Who cancels; null for the console. */
    public UUID getActor() { return actor; }
    /** True when someone other than the seller cancels with admin rights. */
    public boolean isAdmin() { return admin; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
