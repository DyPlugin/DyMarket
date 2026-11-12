package dev.dymarket.estate.gui;

import dev.dymarket.estate.EstateListing;
import dev.dymarket.estate.EstateModule;
import dev.dymarket.gui.GuiHolder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** An estate window: {@link GuiHolder} plus messages, buttons and listing items from the estate module. */
public abstract class EstateWindow extends GuiHolder {
    protected final EstateModule module;

    protected EstateWindow(EstateModule module, Player viewer) {
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
        return decorate(new ItemStack(material), text(nameKey, values), loreKey == null ? List.of() : lines(loreKey, values), false);
    }

    /** Grass block for a sale, oak door for rent; lore with world, chunk count, price, owner and days, then {@code footer}. */
    protected ItemStack listingItem(EstateListing listing, String footerKey) {
        Map<String, String> values = module.describe(viewer, listing);
        boolean sale = listing.type() == EstateListing.Type.SALE;
        List<String> lore = lines(sale ? "estate-gui.sale-lore" : "estate-gui.rent-lore", values);
        if (footerKey != null) lore.add(text(footerKey, values));
        return decorate(new ItemStack(sale ? Material.GRASS_BLOCK : Material.OAK_DOOR),
                text(sale ? "estate-gui.name-sale" : "estate-gui.name-rent", values), lore, false);
    }
}
