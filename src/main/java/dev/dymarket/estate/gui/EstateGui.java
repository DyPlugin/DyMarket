package dev.dymarket.estate.gui;

import dev.dymarket.command.Commands;
import dev.dymarket.estate.EstateListing;
import dev.dymarket.estate.EstateModule;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The estate market: one item per listing (grass block for sale, oak door for rent) on the top five rows, then
 * previous page, my estate and next page. A click opens {@link EstateConfirmGui}.
 */
public final class EstateGui extends EstateWindow {
    private static final int BASE = 45;

    private final int page;
    private final Map<Integer, EstateListing> slots = new HashMap<>();
    private int pages = 1;

    public EstateGui(EstateModule module, Player viewer, int page) {
        super(module, viewer);
        this.page = Math.max(0, page);
    }

    public static void open(EstateModule module, Player player, int page) {
        new EstateGui(module, player, page).show();
    }

    public void show() {
        List<EstateListing> shown = listings();
        pages = Math.max(1, (shown.size() + BASE - 1) / BASE);
        open(54, text("estate-gui.title", Commands.values("{page}", String.valueOf(Math.min(page, pages - 1) + 1), "{pages}", String.valueOf(pages))));
    }

    private List<EstateListing> listings() {
        List<EstateListing> list = module.service().active();
        list.sort(Comparator.comparingLong(EstateListing::created).reversed());
        return list;
    }

    @Override
    protected void fill() {
        List<EstateListing> shown = listings();
        int current = Math.min(page, pages - 1);
        int from = current * BASE;
        for (int i = 0; i < BASE && from + i < shown.size(); i++) {
            EstateListing listing = shown.get(from + i);
            String footer = listing.owner().equals(viewer.getUniqueId()) ? "estate-gui.own-listing"
                    : listing.type() == EstateListing.Type.SALE ? "estate-gui.click-buy" : "estate-gui.click-lease";
            inventory.setItem(i, listingItem(listing, footer));
            slots.put(i, listing);
        }
        if (shown.isEmpty()) inventory.setItem(22, button(Material.PAPER, "estate-gui.empty", null, Map.of()));
        if (current > 0) inventory.setItem(BASE, button(Material.ARROW, "estate-gui.previous", null, Map.of()));
        int mine = module.service().byOwner(viewer.getUniqueId()).size() + module.service().leasesAsTenant(viewer.getUniqueId()).size();
        inventory.setItem(BASE + 4, button(Material.CHEST, "estate-gui.mine", "estate-gui.mine-lore", Commands.values("{count}", String.valueOf(mine))));
        if (current + 1 < pages) inventory.setItem(BASE + 8, button(Material.ARROW, "estate-gui.next", null, Map.of()));
        fillBottom(BASE);
    }

    @Override
    public void click(int slot, ClickType type) {
        int current = Math.min(page, pages - 1);
        if (slot < BASE) {
            EstateListing listing = slots.get(slot);
            if (listing == null || listing.owner().equals(viewer.getUniqueId()) || !claim()) return;
            later(() -> new EstateConfirmGui(module, viewer, listing, current).show());
            return;
        }
        switch (slot - BASE) {
            case 0 -> { if (current > 0 && claim()) later(() -> open(module, viewer, current - 1)); }
            case 4 -> { if (claim()) later(() -> new MyEstateGui(module, viewer, 0).show()); }
            case 8 -> { if (current + 1 < pages && claim()) later(() -> open(module, viewer, current + 1)); }
            default -> { }
        }
    }
}
