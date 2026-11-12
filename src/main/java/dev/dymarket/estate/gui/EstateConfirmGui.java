package dev.dymarket.estate.gui;

import dev.dymarket.command.Commands;
import dev.dymarket.estate.EstateListing;
import dev.dymarket.estate.EstateModule;
import dev.dymarket.estate.EstateService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.Map;

/** Asks before buying or renting: the listing in the middle, confirm on the left, cancel (back to the market) on the right. */
public final class EstateConfirmGui extends EstateWindow {
    private static final int ITEM = 13, CONFIRM = 11, CANCEL = 15;

    private final EstateListing listing;
    private final int page;

    public EstateConfirmGui(EstateModule module, Player viewer, EstateListing listing, int page) {
        super(module, viewer);
        this.listing = listing;
        this.page = page;
    }

    private boolean sale() {
        return listing.type() == EstateListing.Type.SALE;
    }

    public void show() {
        open(27, text(sale() ? "estate-gui.title-confirm-buy" : "estate-gui.title-confirm-lease", Map.of()));
    }

    @Override
    protected void fill() {
        Map<String, String> values = module.describe(viewer, listing);
        inventory.setItem(ITEM, listingItem(listing, null));
        inventory.setItem(CONFIRM, button(Material.LIME_STAINED_GLASS_PANE, sale() ? "estate-gui.confirm-buy" : "estate-gui.confirm-lease",
                sale() ? "estate-gui.confirm-buy-lore" : "estate-gui.confirm-lease-lore", values));
        inventory.setItem(CANCEL, button(Material.RED_STAINED_GLASS_PANE, "estate-gui.deny", null, Map.of()));
        fillBottom(0);
    }

    @Override
    public void click(int slot, ClickType type) {
        if (slot == CONFIRM && claim()) {
            later(() -> {
                viewer.closeInventory();
                take(module, viewer, listing);
            });
        } else if (slot == CANCEL && claim()) {
            later(() -> EstateGui.open(module, viewer, page));
        }
    }

    /** Buys or rents through the service (state check, payment and claim changes happen there) and tells the player. */
    public static void take(EstateModule module, Player player, EstateListing listing) {
        boolean sale = listing.type() == EstateListing.Type.SALE;
        if (!player.hasPermission("dymarket.estate")) {
            module.plugin().messages().send(player, "general.no-permission");
            return;
        }
        java.util.function.Consumer<EstateService.Outcome> done = outcome -> {
            if (outcome.ok()) {
                Map<String, String> values = module.service().values(listing, module.plugin().currencies().get(listing.currency()));
                Commands.reply(module.plugin(), player, sale ? "estate.bought" : "estate.leased", values);
            } else {
                module.failure(player, outcome, Commands.values("{id}", listing.id()));
            }
        };
        if (sale) module.service().buy(listing.id(), player.getUniqueId(), player.getName(), done);
        else module.service().lease(listing.id(), player.getUniqueId(), player.getName(), done);
    }
}
