package dev.dymarket.auction.gui;

import dev.dymarket.auction.AuctionModule;
import dev.dymarket.auction.AuctionService;
import dev.dymarket.auction.Listing;
import dev.dymarket.command.Commands;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The viewer's own active listings; a click cancels one and the item comes back. */
public final class MyListingsGui extends AuctionWindow {
    private final int page;
    private final Map<Integer, Listing> slots = new HashMap<>();
    private int pages = 1;
    private int base;

    public MyListingsGui(AuctionModule module, Player viewer, int page) {
        super(module, viewer);
        this.page = page;
    }

    public void show() {
        base = (module.rows() - 1) * 9;
        List<Listing> mine = listings();
        pages = Math.max(1, (mine.size() + base - 1) / base);
        open(module.rows() * 9, text("auction-gui.title-mine", Commands.values("{page}", String.valueOf(Math.min(page, pages - 1) + 1),
                "{pages}", String.valueOf(pages))));
    }

    private List<Listing> listings() {
        List<Listing> mine = module.service().bySeller(viewer.getUniqueId());
        mine.sort(Comparator.comparingLong(Listing::created).reversed());
        return mine;
    }

    @Override
    protected void fill() {
        List<Listing> mine = listings();
        int current = Math.min(page, pages - 1);
        int from = current * base;
        for (int i = 0; i < base && from + i < mine.size(); i++) {
            inventory.setItem(i, listingItem(mine.get(from + i), "auction-gui.mine-item-lore"));
            slots.put(i, mine.get(from + i));
        }
        if (mine.isEmpty()) inventory.setItem((base / 9 / 2) * 9 + 4, button(Material.PAPER, "auction-gui.mine-empty", null, Map.of()));
        if (current > 0) inventory.setItem(base, button(Material.ARROW, "auction-gui.previous", null, Map.of()));
        inventory.setItem(base + 4, button(Material.OAK_DOOR, "auction-gui.back", null, Map.of()));
        if (current + 1 < pages) inventory.setItem(base + 8, button(Material.ARROW, "auction-gui.next", null, Map.of()));
        fillBottom(base);
    }

    @Override
    public void click(int slot, ClickType type) {
        int current = Math.min(page, pages - 1);
        if (slot < base) {
            Listing listing = slots.get(slot);
            if (listing == null || !claim()) return;
            module.service().cancel(listing.id(), viewer.getUniqueId(), false, outcome -> {
                if (outcome.result() == AuctionService.Result.OK)
                    Commands.reply(module.plugin(), viewer, "auction.cancelled", Commands.values("{id}", listing.id(), "{item}", AuctionService.label(listing)));
                else module.failure(viewer, outcome, Commands.values("{id}", listing.id()));
                if (viewer.isOnline()) module.plugin().scheduler().run(viewer, () -> {
                    if (viewer.getOpenInventory().getTopInventory().getHolder() == this) new MyListingsGui(module, viewer, current).show();
                });
            });
            return;
        }
        switch (slot - base) {
            case 0 -> { if (current > 0 && claim()) later(() -> new MyListingsGui(module, viewer, current - 1).show()); }
            case 4 -> { if (claim()) later(() -> AuctionGui.open(module, viewer, 0, module.defaultSort(), null)); }
            case 8 -> { if (current + 1 < pages && claim()) later(() -> new MyListingsGui(module, viewer, current + 1).show()); }
            default -> { }
        }
    }
}
