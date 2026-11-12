package dev.dymarket.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Keeps DyMarket windows read-only: every click and drag while one is open is cancelled (also in the player's own
 * inventory, so nothing can be shift-clicked in), and clicks in the window itself go to its {@link GuiHolder}.
 * Inventory events run on the player's own thread, also on Folia.
 */
public final class GuiListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.equals(holder.viewer())) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;
        holder.click(slot, event.getClick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof GuiHolder holder) holder.closed();
    }
}
