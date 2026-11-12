package dev.dymarket.api.event;

import dev.dymarket.estate.ChunkPos;
import dev.dymarket.estate.EstateListing;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.math.BigDecimal;
import java.util.List;

/**
 * Fired before a player lists a group of claims for sale or rent, before anything is stored; cancelling it stops
 * the listing. Fired on the owner's own thread (the region thread on Folia).
 */
public class EstateListEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player owner;
    private final EstateListing.Type type;
    private final String world;
    private final List<ChunkPos> chunks;
    private final BigDecimal price;
    private final String currency;
    private final int days;
    private boolean cancelled;

    public EstateListEvent(boolean async, Player owner, EstateListing.Type type, String world, List<ChunkPos> chunks, BigDecimal price,
                           String currency, int days) {
        super(async);
        this.owner = owner;
        this.type = type;
        this.world = world;
        this.chunks = List.copyOf(chunks);
        this.price = price;
        this.currency = currency;
        this.days = days;
    }

    public Player getOwner() { return owner; }
    public EstateListing.Type getType() { return type; }
    public String getWorld() { return world; }
    /** The chunks of the group, starting with the one the owner stands in. */
    public List<ChunkPos> getChunks() { return chunks; }
    /** The sale price, or the rent per period. */
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    /** Days per rent period; 0 for a sale. */
    public int getDays() { return days; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
