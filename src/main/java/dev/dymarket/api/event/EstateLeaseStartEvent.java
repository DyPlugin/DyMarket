package dev.dymarket.api.event;

import dev.dymarket.estate.EstateListing;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired before a player rents an estate listing, before the first period is charged and before any trust is
 * granted; cancelling it stops the lease. Fired on the tenant's own thread (the region thread on Folia).
 */
public class EstateLeaseStartEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final EstateListing listing;
    private final UUID tenant;
    private boolean cancelled;

    public EstateLeaseStartEvent(boolean async, EstateListing listing, UUID tenant) {
        super(async);
        this.listing = listing;
        this.tenant = tenant;
    }

    public EstateListing getListing() { return listing; }
    public UUID getTenant() { return tenant; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
