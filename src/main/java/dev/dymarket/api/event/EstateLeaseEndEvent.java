package dev.dymarket.api.event;

import dev.dymarket.estate.EstateService;
import dev.dymarket.estate.Lease;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a lease ended (the tenant left, was evicted, did not pay within the grace time, the owner or staff
 * unlisted it, or a claim of the group left the owner). Not cancellable: the trust is already being taken back.
 * Fired on the thread that ended the lease: the tenant's or owner's own thread for commands, DyMarket's IO thread
 * for renewals and refunds (then {@link #isAsynchronous()} is true), or the thread DyClaim fired its event on.
 */
public class EstateLeaseEndEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Lease lease;
    private final EstateService.EndReason reason;

    public EstateLeaseEndEvent(boolean async, Lease lease, EstateService.EndReason reason) {
        super(async);
        this.lease = lease;
        this.reason = reason;
    }

    public Lease getLease() { return lease; }
    public EstateService.EndReason getReason() { return reason; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
