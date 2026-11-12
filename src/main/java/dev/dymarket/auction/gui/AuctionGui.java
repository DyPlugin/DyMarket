package dev.dymarket.auction.gui;

import dev.dymarket.auction.AuctionModule;
import dev.dymarket.auction.Listing;
import dev.dymarket.command.Commands;
import dev.dymarket.economy.Currency;
import dev.dymarket.util.CommandWords;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The auction house window: listings on every row but the last, then previous page, sort, my listings,
 * collection box (with its count), a search hint and next page. A click on a listing buys it, through
 * {@link ConfirmGui} when {@code auction.confirm-purchase} is on.
 */
public final class AuctionGui extends AuctionWindow {
    public static final List<String> SORTS = List.of("newest", "price-asc", "price-desc");

    private final int page;
    private final String sort;
    private final String search;
    private final int collectCount;
    private final Map<Integer, Listing> slots = new HashMap<>();
    private int pages = 1;
    private int base;

    public AuctionGui(AuctionModule module, Player viewer, int page, String sort, String search, int collectCount) {
        super(module, viewer);
        this.page = page;
        this.sort = SORTS.contains(sort) ? sort : "newest";
        this.search = search == null || search.isBlank() ? null : search;
        this.collectCount = collectCount;
    }

    /** Reads the collection-box count on DyMarket-IO, then opens the window on the player's thread. */
    public static void open(AuctionModule module, Player player, int page, String sort, String search) {
        module.service().collectCount(player.getUniqueId(), count ->
                module.plugin().scheduler().run(player, () -> new AuctionGui(module, player, page, sort, search, count).show()));
    }

    public void show() {
        int rows = module.rows();
        base = (rows - 1) * 9;
        List<Listing> shown = listings();
        pages = Math.max(1, (shown.size() + base - 1) / base);
        String title = search == null
                ? text("auction-gui.title", Commands.values("{page}", String.valueOf(Math.min(page, pages - 1) + 1), "{pages}", String.valueOf(pages)))
                : text("auction-gui.title-search", Commands.values("{text}", search));
        open(rows * 9, title);
    }

    /** Active, not expired listings matching the search, in the chosen order. */
    List<Listing> listings() {
        long now = module.service().now();
        String needle = search == null ? null : CommandWords.plain(search);
        List<Listing> list = new ArrayList<>();
        for (Listing listing : module.service().active()) {
            if (listing.expired(now)) continue;
            if (needle != null && !listing.search().contains(needle) && !CommandWords.plain(listing.sellerName()).contains(needle)) continue;
            list.add(listing);
        }
        list.sort(comparator(sort));
        return list;
    }

    private Comparator<Listing> comparator(String mode) {
        Comparator<Listing> newest = Comparator.comparingLong(Listing::created).reversed();
        Comparator<Listing> price = Comparator.comparingDouble(this::majorPrice);
        return switch (mode) {
            case "price-asc" -> price.thenComparing(newest);
            case "price-desc" -> price.reversed().thenComparing(newest);
            default -> newest;
        };
    }

    private double majorPrice(Listing listing) {
        Currency currency = module.plugin().currencies().get(listing.currency());
        return currency == null ? listing.price() : currency.toDouble(listing.price());
    }

    @Override
    protected void fill() {
        List<Listing> shown = listings();
        int current = Math.min(page, pages - 1);
        int from = current * base;
        for (int i = 0; i < base && from + i < shown.size(); i++) {
            Listing listing = shown.get(from + i);
            boolean own = listing.seller().equals(viewer.getUniqueId());
            inventory.setItem(i, listingItem(listing, own ? "auction-gui.item-lore-own" : "auction-gui.item-lore"));
            slots.put(i, listing);
        }
        if (shown.isEmpty()) inventory.setItem((base / 9 / 2) * 9 + 4, button(Material.PAPER, "auction-gui.empty", null, Map.of()));
        if (current > 0) inventory.setItem(base, button(Material.ARROW, "auction-gui.previous", null, Map.of()));
        inventory.setItem(base + 2, button(Material.COMPARATOR, "auction-gui.sort", "auction-gui.sort-lore",
                Commands.values("{sort}", text("auction-gui.sort-" + sort, Map.of()))));
        inventory.setItem(base + 3, button(Material.CHEST, "auction-gui.mine", "auction-gui.mine-lore",
                Commands.values("{count}", String.valueOf(module.service().bySeller(viewer.getUniqueId()).size()))));
        inventory.setItem(base + 4, button(Material.ENDER_CHEST, "auction-gui.collect", "auction-gui.collect-lore",
                Commands.values("{count}", String.valueOf(collectCount))));
        inventory.setItem(base + 5, button(Material.OAK_SIGN, "auction-gui.search", "auction-gui.search-lore", Map.of()));
        if (current + 1 < pages) inventory.setItem(base + 8, button(Material.ARROW, "auction-gui.next", null, Map.of()));
        fillBottom(base);
    }

    @Override
    public void click(int slot, ClickType type) {
        int current = Math.min(page, pages - 1);
        if (slot < base) {
            Listing listing = slots.get(slot);
            if (listing == null || listing.seller().equals(viewer.getUniqueId()) || !claim()) return;
            if (module.service().settings().confirmPurchase()) later(() -> new ConfirmGui(module, viewer, listing, current, sort, search).show());
            else later(() -> {
                viewer.closeInventory();
                ConfirmGui.buy(module, viewer, listing);
            });
            return;
        }
        int button = slot - base;
        switch (button) {
            case 0 -> { if (current > 0 && claim()) later(() -> open(module, viewer, current - 1, sort, search)); }
            case 2 -> { if (claim()) later(() -> open(module, viewer, 0, SORTS.get((SORTS.indexOf(sort) + 1) % SORTS.size()), search)); }
            case 3 -> { if (claim()) later(() -> new MyListingsGui(module, viewer, 0).show()); }
            case 4 -> { if (claim()) CollectGui.open(module, viewer, 0); }
            case 5 -> later(() -> {
                viewer.closeInventory();
                module.plugin().messages().send(viewer, "auction.search-hint");
            });
            case 8 -> { if (current + 1 < pages && claim()) later(() -> open(module, viewer, current + 1, sort, search)); }
            default -> { }
        }
    }
}
