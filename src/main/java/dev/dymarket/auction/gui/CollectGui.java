package dev.dymarket.auction.gui;

import dev.dymarket.auction.AuctionModule;
import dev.dymarket.auction.AuctionService;
import dev.dymarket.auction.CollectEntry;
import dev.dymarket.command.Commands;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The collection box: expired, cancelled and removed listings and undelivered purchases. A click takes one item. */
public final class CollectGui extends AuctionWindow {
    private final List<CollectEntry> entries;
    private final int page;
    private final Map<Integer, CollectEntry> slots = new HashMap<>();
    private int pages = 1;
    private int base;

    public CollectGui(AuctionModule module, Player viewer, List<CollectEntry> entries, int page) {
        super(module, viewer);
        this.entries = entries;
        this.page = page;
    }

    /** Reads the box on DyMarket-IO, then opens the window on the player's thread. */
    public static void open(AuctionModule module, Player player, int page) {
        module.service().collectBox(player.getUniqueId(), entries -> {
            if (entries == null) {
                Commands.reply(module.plugin(), player, "general.storage-error");
                return;
            }
            module.plugin().scheduler().run(player, () -> new CollectGui(module, player, entries, page).show());
        });
    }

    public void show() {
        base = (module.rows() - 1) * 9;
        pages = Math.max(1, (entries.size() + base - 1) / base);
        open(module.rows() * 9, text("auction-gui.title-collect", Commands.values("{count}", String.valueOf(entries.size()))));
    }

    @Override
    protected void fill() {
        int current = Math.min(page, pages - 1);
        int from = current * base;
        for (int i = 0; i < base && from + i < entries.size(); i++) {
            CollectEntry entry = entries.get(from + i);
            ItemStack item = module.decode(entry.item());
            if (item == null) item = new ItemStack(Material.BARRIER);
            String reason = text("auction-gui.reason-" + entry.reason().toLowerCase(Locale.ROOT).replace('_', '-'), Map.of());
            inventory.setItem(i, decorate(item, null, lines("auction-gui.collect-item-lore", Commands.values("{reason}", reason)), true));
            slots.put(i, entry);
        }
        if (entries.isEmpty()) inventory.setItem((base / 9 / 2) * 9 + 4, button(Material.PAPER, "auction-gui.collect-empty", null, Map.of()));
        if (current > 0) inventory.setItem(base, button(Material.ARROW, "auction-gui.previous", null, Map.of()));
        inventory.setItem(base + 3, button(Material.OAK_DOOR, "auction-gui.back", null, Map.of()));
        if (!entries.isEmpty()) inventory.setItem(base + 5, button(Material.HOPPER, "auction-gui.collect-all", null, Map.of()));
        if (current + 1 < pages) inventory.setItem(base + 8, button(Material.ARROW, "auction-gui.next", null, Map.of()));
        fillBottom(base);
    }

    @Override
    public void click(int slot, ClickType type) {
        int current = Math.min(page, pages - 1);
        if (slot < base) {
            CollectEntry entry = slots.get(slot);
            if (entry == null || !claim()) return;
            module.service().collect(viewer.getUniqueId(), entry.id(), outcome -> after(outcome, current));
            return;
        }
        switch (slot - base) {
            case 0 -> { if (current > 0 && claim()) later(() -> new CollectGui(module, viewer, entries, current - 1).show()); }
            case 3 -> { if (claim()) later(() -> AuctionGui.open(module, viewer, 0, module.defaultSort(), null)); }
            case 5 -> { if (!entries.isEmpty() && claim()) module.service().collectAll(viewer.getUniqueId(), outcome -> after(outcome, 0)); }
            case 8 -> { if (current + 1 < pages && claim()) later(() -> new CollectGui(module, viewer, entries, current + 1).show()); }
            default -> { }
        }
    }

    /** Reports the result and shows the box again while the player still looks at it. */
    private void after(AuctionService.Outcome outcome, int reopenPage) {
        if (outcome.result() == AuctionService.Result.OK) {
            Commands.reply(module.plugin(), viewer, outcome.remaining() > 0 ? "auction.collect-partial" : "auction.collected",
                    Commands.values("{count}", String.valueOf(outcome.count()), "{remaining}", String.valueOf(outcome.remaining())));
        } else if (outcome.result() != AuctionService.Result.NOT_FOUND) {
            module.failure(viewer, outcome, Map.of());
        }
        if (!viewer.isOnline()) return;
        module.plugin().scheduler().run(viewer, () -> {
            if (viewer.getOpenInventory().getTopInventory().getHolder() == this) open(module, viewer, reopenPage);
        });
    }
}
