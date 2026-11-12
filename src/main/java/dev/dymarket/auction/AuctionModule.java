package dev.dymarket.auction;

import dev.dymarket.DyMarket;
import dev.dymarket.api.event.AuctionCancelEvent;
import dev.dymarket.api.event.AuctionExpireEvent;
import dev.dymarket.api.event.AuctionListEvent;
import dev.dymarket.api.event.AuctionPurchaseEvent;
import dev.dymarket.gui.GuiHolder;
import dev.dymarket.command.Commands;
import dev.dymarket.command.ModuleStubCommand;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.economy.EconomyService;
import dev.dymarket.economy.TransactionKind;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.scheduler.Scheduler;
import dev.dymarket.storage.ItemCodec;
import dev.dymarket.util.Colors;
import dev.dymarket.util.CommandWords;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Wires the auction house into DyMarket: storage, the service, /ah, /dymarket ah, placeholders and the expiry
 * sweep. Also the server side of {@link AuctionService.Platform} (threads, inventories, messages, API events) and
 * the {@link AuctionService.Bank} backed by the economy core.
 */
public final class AuctionModule implements AuctionService.Platform {
    public static final String MODULE = "auction";

    private final DyMarket plugin;
    private final AuctionStore store;
    private final AuctionService service;
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private Scheduler.Task sweepTask;

    public AuctionModule(DyMarket plugin) {
        this.plugin = plugin;
        this.store = new AuctionStore(plugin.store());
        this.service = new AuctionService(store, new EconomyBank(plugin.economy()), this, plugin.rateLimiter(), plugin::currencies,
                System::currentTimeMillis, plugin.getLogger());
    }

    /** Loads the active listings (waiting once for DyMarket-IO, like the database start-up) and registers everything. */
    public boolean start() {
        readSettings();
        try {
            List<Listing> active = plugin.io().call(() -> {
                store.prepare();
                List<Listing> loaded = store.loadActive();
                for (Listing listing : loaded) describe(listing);
                return loaded;
            });
            service.load(active);
        } catch (Exception failure) {
            plugin.getLogger().log(Level.WARNING, "The auction house could not load its listings and stays off", failure);
            return false;
        }
        AuctionCommand command = new AuctionCommand(plugin, this);
        Commands.register(plugin, command, "ah");
        plugin.registerAdminCommand(MODULE, new AuctionAdminCommand(plugin, this));
        plugin.registerPlaceholder("ah_listings", player -> String.valueOf(service.activeCount()));
        plugin.registerPlaceholder("ah_mine", player -> player == null ? "0" : String.valueOf(service.bySeller(player.getUniqueId()).size()));
        // Every minute on the global scheduler; the work itself runs on DyMarket-IO.
        sweepTask = plugin.scheduler().runGlobalTimer(() -> {
            if (!sweeping.compareAndSet(false, true)) return;
            plugin.io().execute(() -> {
                try {
                    service.sweep();
                } finally {
                    sweeping.set(false);
                }
            });
        }, 1200L, 1200L);
        plugin.setModuleLoaded(MODULE, true);
        plugin.getLogger().info("Auction house loaded with " + service.activeCount() + " active listings.");
        return true;
    }

    /** Turns the module off: /ah answers "not loaded" again and open auction windows close. */
    public void stop(boolean disabling) {
        if (sweepTask != null) sweepTask.cancel();
        sweepTask = null;
        plugin.setModuleLoaded(MODULE, false);
        plugin.registerAdminCommand(MODULE, null);
        plugin.registerPlaceholder("ah_listings", null);
        plugin.registerPlaceholder("ah_mine", null);
        if (!disabling) Commands.register(plugin, new ModuleStubCommand(plugin, MODULE), "ah");
        for (Player player : Bukkit.getOnlinePlayers()) {
            Runnable close = () -> {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) player.closeInventory();
            };
            if (!Scheduler.FOLIA) close.run();
            else if (!disabling) plugin.scheduler().run(player, close);
        }
    }

    public void reload() {
        readSettings();
    }

    /** After /dymarket merge (DyMarket-IO): listings in memory follow the stored ones. */
    public void merged(UUID from, UUID to) {
        var account = plugin.economy().cached(to);
        service.merged(from, to, account == null ? null : account.name());
    }

    private void readSettings() {
        FileConfiguration config = plugin.getConfig();
        Set<String> blacklist = new HashSet<>();
        for (String name : config.getStringList("auction.blacklist")) blacklist.add(name.trim().toUpperCase(Locale.ROOT));
        List<String> lore = new ArrayList<>();
        for (String text : config.getStringList("auction.block-items-with-lore-containing")) if (!text.isBlank()) lore.add(text);
        List<String> allowed = config.getStringList("auction.allowed-currencies");
        String currency = config.getString("auction.currency", "money");
        if (allowed.isEmpty()) allowed = List.of(currency);
        service.settings(new AuctionService.Settings(Math.max(1, config.getLong("auction.duration-hours", 48)) * 3_600_000L,
                config.getDouble("auction.tax-percent", 5.0), config.getDouble("auction.min-price", 1.0),
                config.getDouble("auction.max-price", 100_000_000.0), config.getInt("auction.max-listings", 7), currency,
                List.copyOf(allowed), Set.copyOf(blacklist), List.copyOf(lore), config.getBoolean("auction.confirm-purchase", true),
                config.getBoolean("auction.broadcast-sales", false), config.getInt("security.rate-limit.ah-buy-per-minute", 20)));
    }

    // ---- accessors and rank values ----------------------------------------------------------------------------

    public AuctionService service() { return service; }
    public DyMarket plugin() { return plugin; }

    public int rows() {
        return Math.max(2, Math.min(6, plugin.getConfig().getInt("auction.gui.rows", 6)));
    }

    public String defaultSort() {
        return plugin.getConfig().getString("auction.gui.sort", "newest");
    }

    /** Highest {@code dymarket.ah.limit.<n>} wins; {@code unlimited} or {@code max-listings: 0} means no limit (-1). */
    public int limit(Player player) {
        if (player.hasPermission("dymarket.ah.limit.unlimited")) return -1;
        int best = -1;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            if (!info.getValue() || !node.startsWith("dymarket.ah.limit.")) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring("dymarket.ah.limit.".length())));
            } catch (NumberFormatException notNumber) {
                // Another node under this prefix.
            }
        }
        if (best >= 0) return best;
        int max = service.settings().maxListings();
        return max <= 0 ? -1 : max;
    }

    /** Lowest {@code dymarket.ah.tax.<percent>} wins over the configured tax; {@code dymarket.bypass.tax} pays none. */
    public int taxBasisPoints(Player player) {
        if (player.hasPermission("dymarket.bypass.tax")) return 0;
        double rate = Double.NaN;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            if (!info.getValue() || !node.startsWith("dymarket.ah.tax.")) continue;
            try {
                double value = Double.parseDouble(node.substring("dymarket.ah.tax.".length()));
                if (Double.isFinite(value)) rate = Double.isNaN(rate) ? value : Math.min(rate, value);
            } catch (NumberFormatException notNumber) {
                // Another node under this prefix.
            }
        }
        if (Double.isNaN(rate)) rate = service.settings().taxPercent();
        return (int) Math.round(Math.max(0, Math.min(100, rate)) * 100);
    }

    // ---- items --------------------------------------------------------------------------------------------------

    /** A fresh copy of the listing's item, decoded once and cached; null when it cannot be read on this server. */
    public ItemStack icon(Listing listing) {
        Object cached = listing.icon();
        if (cached instanceof ItemStack item) return item.clone();
        ItemStack decoded = decode(listing.item());
        if (decoded == null) return null;
        listing.icon(decoded);
        return decoded.clone();
    }

    public ItemStack decode(byte[] data) {
        try {
            return ItemCodec.decode(data);
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().warning("Could not read a stored auction item: " + failure.getMessage());
            return null;
        }
    }

    /** "Diamond Sword" from DIAMOND_SWORD. */
    public static String pretty(String material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** The custom name without colours, or the readable material name. */
    @SuppressWarnings("deprecation")
    public static String name(ItemStack item) {
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta != null && meta.hasDisplayName()) {
            String name = Colors.strip(meta.getDisplayName());
            if (name != null && !name.isBlank()) return name;
        }
        return pretty(item.getType().name());
    }

    @SuppressWarnings("deprecation")
    public static List<String> lore(ItemStack item) {
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta == null || !meta.hasLore() || meta.getLore() == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (String line : meta.getLore()) lines.add(Colors.strip(line));
        return lines;
    }

    /** True when the whole stack fits into the player's storage slots. */
    static boolean fits(PlayerInventory inventory, ItemStack item) {
        int room = 0;
        int max = Math.max(1, item.getMaxStackSize());
        for (ItemStack slot : inventory.getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) room += max;
            else if (slot.isSimilar(item)) room += Math.max(0, max - slot.getAmount());
            if (room >= item.getAmount()) return true;
        }
        return false;
    }

    // ---- Platform -----------------------------------------------------------------------------------------------

    @Override
    public void io(Runnable task) {
        plugin.io().execute(task);
    }

    @Override
    public void onPlayer(UUID id, Runnable task, Runnable offline) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) {
            offline.run();
            return;
        }
        try {
            plugin.scheduler().run(player, task, offline);
        } catch (RuntimeException schedulerGone) {
            // The plugin is shutting down; keep the item in storage instead.
            offline.run();
        }
    }

    @Override
    public boolean give(UUID id, byte[] data) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) return false;
        ItemStack item = decode(data);
        if (item == null || !fits(player.getInventory(), item)) return false;
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        for (ItemStack rest : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), rest);
        return true;
    }

    @Override
    public void giveOrDrop(UUID id, byte[] data) {
        Player player = Bukkit.getPlayer(id);
        ItemStack item = decode(data);
        if (player == null || item == null) return;
        for (ItemStack rest : player.getInventory().addItem(item).values())
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
    }

    @Override
    public void notify(UUID id, String key, Map<String, String> values, boolean keep) {
        Map<String, String> copy = Map.copyOf(values);
        Runnable store = () -> {
            if (!keep) return;
            plugin.io().execute(() -> {
                try {
                    plugin.store().addNotice(id, key, copy);
                } catch (Exception failure) {
                    plugin.getLogger().warning("Could not store a notice: " + failure.getMessage());
                }
            });
        };
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            store.run();
            return;
        }
        onPlayer(id, () -> plugin.messages().send(player, key, copy), store);
    }

    @Override
    public void broadcast(String key, Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(values);
        plugin.scheduler().runGlobal(() -> {
            plugin.messages().send(Bukkit.getConsoleSender(), key, copy);
            for (Player player : Bukkit.getOnlinePlayers()) plugin.scheduler().run(player, () -> plugin.messages().send(player, key, copy));
        });
    }

    @Override
    public void describe(Listing listing) {
        ItemStack item = icon(listing);
        if (item == null) {
            listing.describe("UNKNOWN", "?", "");
            return;
        }
        String material = item.getType().name();
        String name = name(item);
        listing.describe(material, name, CommandWords.plain(material.replace('_', ' ') + " " + name));
    }

    private static boolean async() {
        return !Bukkit.isPrimaryThread();
    }

    @Override
    public boolean callList(AuctionService.SellRequest request) {
        if (AuctionListEvent.getHandlerList().getRegisteredListeners().length == 0) return true;
        Player player = Bukkit.getPlayer(request.seller());
        Currency currency = plugin.currencies().get(request.currency());
        if (player == null || currency == null || !(request.preview() instanceof ItemStack item)) return true;
        AuctionListEvent event = new AuctionListEvent(async(), player, item, currency.toMajor(request.price()), currency.id());
        plugin.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Override
    public boolean callPurchase(Listing listing, UUID buyer) {
        if (AuctionPurchaseEvent.getHandlerList().getRegisteredListeners().length == 0) return true;
        AuctionPurchaseEvent event = new AuctionPurchaseEvent(async(), listing, buyer);
        plugin.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Override
    public boolean callCancel(Listing listing, UUID actor, boolean admin) {
        if (AuctionCancelEvent.getHandlerList().getRegisteredListeners().length == 0) return true;
        AuctionCancelEvent event = new AuctionCancelEvent(async(), listing, actor, admin && !listing.seller().equals(actor));
        plugin.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Override
    public void callExpire(Listing listing) {
        if (AuctionExpireEvent.getHandlerList().getRegisteredListeners().length == 0) return;
        plugin.getServer().getPluginManager().callEvent(new AuctionExpireEvent(async(), listing));
    }

    // ---- replies --------------------------------------------------------------------------------------------------

    /** Tells the sender why an auction action failed (anything but OK). Safe on any thread. */
    public void failure(CommandSender sender, AuctionService.Outcome outcome, Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(extra);
        Listing listing = outcome.listing();
        if (listing != null) {
            values.putIfAbsent("{id}", listing.id());
            values.putIfAbsent("{item}", AuctionService.label(listing));
        }
        values.putIfAbsent("{id}", "");
        String key = switch (outcome.result()) {
            case CURRENCY_NOT_ALLOWED -> "auction.currency-not-allowed";
            case UNKNOWN_CURRENCY -> "general.unknown-currency";
            case PRICE_TOO_LOW -> "auction.price-too-low";
            case PRICE_TOO_HIGH -> "auction.price-too-high";
            case EMPTY_HAND -> "auction.empty-hand";
            case INVALID_AMOUNT -> "auction.invalid-item-amount";
            case BLACKLISTED -> "auction.blacklisted";
            case LORE_BLOCKED -> "auction.lore-blocked";
            case LIMIT_REACHED -> "auction.limit-reached";
            case CANCELLED -> "auction.cancelled-by-plugin";
            case NOT_FOUND -> "auction.not-found";
            case NOT_AVAILABLE -> "auction.not-available";
            case OWN_LISTING -> "auction.own-listing";
            case NOT_OWNER -> "auction.not-owner";
            case RATE_LIMITED -> "general.rate-limited";
            case EMPTY -> "auction.collect-empty";
            case INVENTORY_FULL -> "auction.collect-full";
            case PAYMENT_FAILED -> null;
            default -> "general.storage-error";
        };
        if (key == null) {
            Currency currency = listing == null ? null : plugin.currencies().get(listing.currency());
            TransactionResult payment = outcome.payment() == null ? TransactionResult.of(TransactionResult.Status.FAILED) : outcome.payment();
            Commands.failure(plugin, sender, payment, currency, values);
            return;
        }
        values.putIfAbsent("{currencies}", String.join(", ", service.settings().allowedCurrencies()));
        values.putIfAbsent("{count}", String.valueOf(outcome.remaining()));
        Commands.reply(plugin, sender, key, values);
    }

    /** "1d 4h" or "3h 20m" in the reader's language. */
    public String remaining(CommandSender reader, long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        long days = minutes / 1440, hours = minutes / 60 % 24, rest = minutes % 60;
        if (days > 0) return plugin.messages().get(reader, "auction.time-long",
                Commands.values("{days}", String.valueOf(days), "{hours}", String.valueOf(hours)));
        return plugin.messages().get(reader, "auction.time-short",
                Commands.values("{hours}", String.valueOf(hours), "{minutes}", String.valueOf(rest)));
    }

    /** The price of a listing, formatted. */
    public String price(Listing listing) {
        Currency currency = plugin.currencies().get(listing.currency());
        return currency == null ? listing.price() + " " + listing.currency() : CurrencyFormat.format(currency, listing.price());
    }

    // ---- Bank -----------------------------------------------------------------------------------------------------

    /** Purchases through the economy core: the buyer pays the seller's share as AH_SALE and the tax as AH_TAX. */
    static final class EconomyBank implements AuctionService.Bank {
        private final EconomyService economy;

        EconomyBank(EconomyService economy) {
            this.economy = economy;
        }

        @Override
        public TransactionResult charge(UUID buyer, Currency currency, long price, long tax, String note) {
            long balance = economy.balance(buyer, currency);
            if (balance < price) return TransactionResult.of(TransactionResult.Status.INSUFFICIENT_FUNDS, balance);
            long share = price - tax;
            TransactionResult paid = share > 0 ? economy.withdraw(buyer, currency, share, TransactionKind.AH_SALE, note) : null;
            if (paid != null && !paid.success()) return paid;
            if (tax <= 0) return paid;
            TransactionResult taxed = economy.withdraw(buyer, currency, tax, TransactionKind.AH_TAX, note);
            if (!taxed.success() && paid != null) economy.deposit(buyer, currency, share, TransactionKind.REFUND, note + " refund");
            return taxed;
        }

        @Override
        public TransactionResult credit(UUID seller, Currency currency, long amount, String note) {
            return economy.deposit(seller, currency, amount, TransactionKind.AH_SALE, note);
        }

        @Override
        public TransactionResult refund(UUID buyer, Currency currency, long amount, String note) {
            return economy.deposit(buyer, currency, amount, TransactionKind.REFUND, note);
        }
    }
}
