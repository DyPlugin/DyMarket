package dev.dymarket.auction.gui;

import dev.dymarket.auction.AuctionModule;
import dev.dymarket.auction.Listing;
import dev.dymarket.gui.GuiHolder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** An auction window: {@link GuiHolder} plus messages, buttons and listing items from the auction module. */
abstract class AuctionWindow extends GuiHolder {
    protected final AuctionModule module;

    protected AuctionWindow(AuctionModule module, Player viewer) {
        super(viewer);
        this.module = module;
    }

    /** Runs on the viewer's thread on the next tick (opening a window from inside a click is not allowed). */
    protected void later(Runnable task) {
        module.plugin().scheduler().run(viewer, task);
    }

    protected String text(String key, Map<String, String> values) {
        return module.plugin().messages().get(viewer, key, values);
    }

    /** A message list with placeholders filled in; the values are inserted after colouring, so they stay as typed. */
    protected List<String> lines(String key, Map<String, String> values) {
        List<String> out = new ArrayList<>();
        for (String line : module.plugin().messages().getList(viewer, key)) {
            String filled = line;
            for (Map.Entry<String, String> entry : values.entrySet()) filled = filled.replace(entry.getKey(), entry.getValue());
            out.add(filled);
        }
        return out;
    }

    protected ItemStack button(Material material, String nameKey, String loreKey, Map<String, String> values) {
        ItemStack item = new ItemStack(material);
        return decorate(item, text(nameKey, values), loreKey == null ? List.of() : lines(loreKey, values), false);
    }

    /** The listing's item with the price, seller, time left and id below its own lore. */
    protected ItemStack listingItem(Listing listing, String loreKey) {
        ItemStack icon = module.icon(listing);
        if (icon == null) icon = new ItemStack(Material.BARRIER);
        Map<String, String> values = module.service().values(listing, module.plugin().currencies().get(listing.currency()));
        values.put("{time}", module.remaining(viewer, listing.expires() - module.service().now()));
        return decorate(icon, null, lines(loreKey, values), true);
    }
}
