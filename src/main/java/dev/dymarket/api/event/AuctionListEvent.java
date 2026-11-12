package dev.dymarket.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;

/**
 * Fired before a player lists an item in the auction house, before the item leaves the hand; cancelling it stops
 * the listing and the item stays where it is. Fired on the seller's own thread (the region thread on Folia).
 */
public class AuctionListEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player seller;
    private final ItemStack item;
    private final BigDecimal price;
    private final String currency;
    private boolean cancelled;

    public AuctionListEvent(boolean async, Player seller, ItemStack item, BigDecimal price, String currency) {
        super(async);
        this.seller = seller;
        this.item = item;
        this.price = price;
        this.currency = currency;
    }

    public Player getSeller() { return seller; }
    /** A copy of the item (with the listed amount); changing it changes nothing. */
    public ItemStack getItem() { return item.clone(); }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
