package dev.dymarket.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Marks a DyMarket window. {@link GuiListener} cancels every click and drag while an inventory with this holder
 * is open, so the display items can never be taken, and routes clicks in the top part to {@link #click}. Other
 * modules can extend it for their own windows. All methods run on the viewer's own thread (inventory events
 * already do on Folia).
 */
public abstract class GuiHolder implements InventoryHolder {
    protected final Player viewer;
    protected Inventory inventory;
    private boolean busy;

    protected GuiHolder(Player viewer) {
        this.viewer = viewer;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player viewer() {
        return viewer;
    }

    /** A click on a slot of this window. */
    public abstract void click(int slot, ClickType type);

    /** The window was closed. */
    public void closed() {
    }

    /** Creates the inventory with the given title, lets {@link #fill} add the items and opens it. */
    protected void open(int size, String title) {
        inventory = Bukkit.createInventory(this, size, title);
        fill();
        viewer.openInventory(inventory);
    }

    protected abstract void fill();

    /** True once: further clicks are ignored while an action started by this window runs. */
    protected boolean claim() {
        if (busy) return false;
        busy = true;
        return true;
    }

    protected static ItemStack filler() {
        return decorate(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), " ", List.of(), false);
    }

    /** Sets the name (when given) and adds lore lines; {@code append} keeps the item's own lore first. */
    @SuppressWarnings("deprecation")
    protected static ItemStack decorate(ItemStack item, String name, List<String> lore, boolean append) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        if (name != null) meta.setDisplayName(name);
        List<String> lines = new ArrayList<>();
        if (append && meta.hasLore() && meta.getLore() != null) lines.addAll(meta.getLore());
        lines.addAll(lore);
        meta.setLore(lines.isEmpty() ? null : lines);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    /** Fills empty slots from {@code firstSlot} to the end with glass. */
    protected void fillBottom(int firstSlot) {
        for (int slot = firstSlot; slot < inventory.getSize(); slot++)
            if (inventory.getItem(slot) == null) inventory.setItem(slot, filler());
    }
}
