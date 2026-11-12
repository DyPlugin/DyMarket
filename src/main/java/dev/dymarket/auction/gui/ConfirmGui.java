package dev.dymarket.auction.gui;

import dev.dymarket.auction.AuctionModule;
import dev.dymarket.auction.AuctionService;
import dev.dymarket.auction.Listing;
import dev.dymarket.command.Commands;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.Map;

/** Asks before a purchase: the item in the middle, confirm on the left, cancel (back to the list) on the right. */
public final class ConfirmGui extends AuctionWindow {
    private static final int ITEM = 13, CONFIRM = 11, CANCEL = 15;

    private final Listing listing;
    private final int page;
    private final String sort;
    private final String search;

    public ConfirmGui(AuctionModule module, Player viewer, Listing listing, int page, String sort, String search) {
        super(module, viewer);
        this.listing = listing;
        this.page = page;
        this.sort = sort;
        this.search = search;
    }

    public void show() {
        open(27, text("auction-gui.title-confirm", Map.of()));
    }

    @Override
    protected void fill() {
        Map<String, String> values = module.service().values(listing, module.plugin().currencies().get(listing.currency()));
        inventory.setItem(ITEM, listingItem(listing, "auction-gui.confirm-item-lore"));
        inventory.setItem(CONFIRM, button(Material.LIME_STAINED_GLASS_PANE, "auction-gui.confirm", "auction-gui.confirm-lore", values));
        inventory.setItem(CANCEL, button(Material.RED_STAINED_GLASS_PANE, "auction-gui.deny", null, Map.of()));
        fillBottom(0);
    }

    @Override
    public void click(int slot, ClickType type) {
        if (slot == CONFIRM && claim()) {
            later(() -> {
                viewer.closeInventory();
                buy(module, viewer, listing);
            });
        } else if (slot == CANCEL && claim()) {
            later(() -> AuctionGui.open(module, viewer, page, sort, search));
        }
    }

    /** Buys through the service (state check, payment and delivery happen there) and tells the player. */
    public static void buy(AuctionModule module, Player player, Listing listing) {
        boolean bypass = player.hasPermission("dymarket.bypass.ratelimit");
        module.service().buy(listing.id(), player.getUniqueId(), player.getName(), bypass, outcome -> {
            if (outcome.result() == AuctionService.Result.OK) {
                Map<String, String> values = module.service().values(listing, module.plugin().currencies().get(listing.currency()));
                Commands.reply(module.plugin(), player, "auction.bought", values);
            } else {
                module.failure(player, outcome, Commands.values("{id}", listing.id()));
            }
        });
    }
}
