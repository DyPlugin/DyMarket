package dev.dymarket.estate;

import dev.dyclaim.api.Claim;
import dev.dyclaim.api.event.ClaimRemoveEvent;
import dev.dyclaim.api.event.ClaimTransferEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * DyClaim's remove and transfer events. They fire after the change and cannot be cancelled, so they are only
 * notifications: a claim that left its owner ends the owner's listings and leases containing it. A sale made by
 * DyMarket does not count, because its listing is already closed when the transfer event arrives.
 */
final class ClaimListener implements Listener {
    private final EstateService service;

    ClaimListener(EstateService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(ClaimRemoveEvent event) {
        Claim claim = event.getClaim();
        if (claim == null || claim.getOwner() == null) return;
        service.claimLost(claim.getWorld(), claim.getChunkX(), claim.getChunkZ(), claim.getOwner());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTransfer(ClaimTransferEvent event) {
        Claim claim = event.getClaim();
        UUID previous = event.getPreviousOwner();
        if (claim == null || previous == null || previous.equals(event.getNewOwner())) return;
        service.claimLost(claim.getWorld(), claim.getChunkX(), claim.getChunkZ(), previous);
    }
}
