package dev.dymarket.estate.gui;

import dev.dymarket.command.Commands;
import dev.dymarket.estate.EstateListing;
import dev.dymarket.estate.EstateModule;
import dev.dymarket.estate.Lease;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The viewer's own estate: their open listings (a click takes an unrented listing off the market), the leases they
 * rent (a click turns auto-renew on or off). A rented-out listing shows its tenant and the time left in its lore.
 */
public final class MyEstateGui extends EstateWindow {
    private static final int BASE = 45;

    private final int page;
    private final Map<Integer, Object> slots = new HashMap<>();
    private int pages = 1;

    public MyEstateGui(EstateModule module, Player viewer, int page) {
        super(module, viewer);
        this.page = Math.max(0, page);
    }

    public void show() {
        pages = Math.max(1, (entries().size() + BASE - 1) / BASE);
        open(54, text("estate-gui.title-mine", Commands.values("{page}", String.valueOf(Math.min(page, pages - 1) + 1), "{pages}", String.valueOf(pages))));
    }

    /** Listings first (newest first), then the leases the viewer rents. */
    private List<Object> entries() {
        List<EstateListing> listings = module.service().byOwner(viewer.getUniqueId());
        listings.sort(Comparator.comparingLong(EstateListing::created).reversed());
        List<Object> all = new ArrayList<>(listings);
        all.addAll(module.service().leasesAsTenant(viewer.getUniqueId()));
        return all;
    }

    @Override
    protected void fill() {
        List<Object> all = entries();
        int current = Math.min(page, pages - 1);
        int from = current * BASE;
        for (int i = 0; i < BASE && from + i < all.size(); i++) {
            Object entry = all.get(from + i);
            if (entry instanceof EstateListing listing) {
                inventory.setItem(i, listingItem(listing, listing.status() == EstateListing.Status.LEASED ? "estate-gui.leased-hint" : "estate-gui.click-unlist"));
                Lease lease = module.service().leaseOf(listing);
                if (lease != null) inventory.setItem(i, decorate(inventory.getItem(i), null,
                        lines("estate-gui.landlord-lore", module.describe(viewer, lease)), true));
            } else if (entry instanceof Lease lease) {
                Map<String, String> values = module.describe(viewer, lease);
                inventory.setItem(i, button(Material.IRON_DOOR, "estate-gui.lease-name", "estate-gui.tenant-lore", values));
            }
            slots.put(i, entry);
        }
        if (all.isEmpty()) inventory.setItem(22, button(Material.PAPER, "estate-gui.mine-empty", null, Map.of()));
        if (current > 0) inventory.setItem(BASE, button(Material.ARROW, "estate-gui.previous", null, Map.of()));
        inventory.setItem(BASE + 4, button(Material.OAK_DOOR, "estate-gui.back", null, Map.of()));
        if (current + 1 < pages) inventory.setItem(BASE + 8, button(Material.ARROW, "estate-gui.next", null, Map.of()));
        fillBottom(BASE);
    }

    @Override
    public void click(int slot, ClickType type) {
        int current = Math.min(page, pages - 1);
        if (slot < BASE) {
            Object entry = slots.get(slot);
            if (entry instanceof EstateListing listing) {
                if (listing.status() != EstateListing.Status.ACTIVE) {
                    later(() -> {
                        viewer.closeInventory();
                        module.plugin().messages().send(viewer, "estate.unlist-leased-hint", Commands.values("{id}", listing.id()));
                    });
                    return;
                }
                if (!claim()) return;
                module.service().unlist(listing.id(), viewer.getUniqueId(), false, outcome -> {
                    if (outcome.ok()) Commands.reply(module.plugin(), viewer, "estate.unlisted", Commands.values("{id}", listing.id()));
                    else module.failure(viewer, outcome, Commands.values("{id}", listing.id()));
                    reopen(current);
                });
            } else if (entry instanceof Lease lease && claim()) {
                module.service().autorenew(lease.id(), viewer.getUniqueId(), outcome -> {
                    if (outcome.ok()) Commands.reply(module.plugin(), viewer, outcome.amount() == 1 ? "estate.autorenew-on" : "estate.autorenew-off",
                            Commands.values("{id}", lease.listingId()));
                    else module.failure(viewer, outcome, Commands.values("{id}", lease.listingId()));
                    reopen(current);
                });
            }
            return;
        }
        switch (slot - BASE) {
            case 0 -> { if (current > 0 && claim()) later(() -> new MyEstateGui(module, viewer, current - 1).show()); }
            case 4 -> { if (claim()) later(() -> EstateGui.open(module, viewer, 0)); }
            case 8 -> { if (current + 1 < pages && claim()) later(() -> new MyEstateGui(module, viewer, current + 1).show()); }
            default -> { }
        }
    }

    /** Shows the window again on the viewer's thread if it is still open. */
    private void reopen(int current) {
        if (viewer.isOnline()) module.plugin().scheduler().run(viewer, () -> {
            if (viewer.getOpenInventory().getTopInventory().getHolder() == this) new MyEstateGui(module, viewer, current).show();
        });
    }
}
