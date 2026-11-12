package dev.dymarket.auction;

import dev.dymarket.DyMarket;
import dev.dymarket.auction.gui.AuctionGui;
import dev.dymarket.command.Commands;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.storage.ItemCodec;
import dev.dymarket.util.CommandWords;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * /ah: opens the auction window; sell, list, mine, collect, cancel, buy, search and info give every window action
 * as a command too (English or Turkish words). Runs on the sender's own thread; storage and payments go through
 * {@link AuctionService} and DyMarket-IO.
 */
public final class AuctionCommand implements CommandExecutor, TabCompleter {
    private static final int PAGE_SIZE = 10;
    private static final List<String> SUBCOMMANDS = List.of("sell", "list", "mine", "collect", "cancel", "buy", "search", "info");

    private final DyMarket plugin;
    private final AuctionModule module;

    public AuctionCommand(DyMarket plugin, AuctionModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private AuctionService service() {
        return module.service();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dymarket.ah")) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) AuctionGui.open(module, player, 0, module.defaultSort(), null);
            else plugin.messages().send(sender, "auction.usage");
            return true;
        }
        String sub = CommandWords.canonical(args[0]);
        switch (sub) {
            case "sell" -> sell(sender, label, args);
            case "list" -> list(sender, service().active(), args.length >= 2 ? Commands.page(args[1], 1) : 1, null);
            case "mine" -> mine(sender);
            case "collect" -> collect(sender);
            case "cancel" -> cancel(sender, args);
            case "buy" -> buy(sender, label, args);
            case "search" -> search(sender, args);
            case "info" -> info(sender, args);
            default -> plugin.messages().send(sender, "auction.usage");
        }
        return true;
    }

    // ---- sell ---------------------------------------------------------------------------------------------------

    private void sell(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        if (!player.hasPermission("dymarket.ah.sell")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "auction.sell-usage");
            return;
        }
        AuctionService.Settings settings = service().settings();
        Currency currency = plugin.currencies().get(settings.defaultCurrency());
        int next = 2;
        if (args.length > next && !args[next].matches("\\d+")) {
            currency = plugin.currencies().get(args[next].toLowerCase(Locale.ROOT));
            if (currency == null) {
                plugin.messages().send(sender, "general.unknown-currency", Commands.values("{currency}", args[next],
                        "{currencies}", String.join(", ", settings.allowedCurrencies())));
                return;
            }
            next++;
        }
        if (currency == null) {
            plugin.messages().send(sender, "general.unknown-currency", Commands.values("{currency}", settings.defaultCurrency(),
                    "{currencies}", String.join(", ", settings.allowedCurrencies())));
            return;
        }
        OptionalLong price = CurrencyFormat.parse(currency, args[1]);
        if (price.isEmpty() || price.getAsLong() <= 0) {
            plugin.messages().send(sender, "general.invalid-amount", Map.of("{amount}", args[1]));
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        boolean empty = hand == null || hand.getType() == Material.AIR || hand.getAmount() <= 0;
        int amount = empty ? 0 : hand.getAmount();
        if (args.length > next) {
            try {
                amount = Integer.parseInt(args[next]);
            } catch (NumberFormatException notNumber) {
                plugin.messages().send(sender, "auction.sell-usage");
                return;
            }
        }
        ItemStack snapshot = empty ? null : hand.clone();
        int count = amount;
        int limit = module.limit(player);
        int tax = module.taxBasisPoints(player);
        ItemStack preview = null;
        if (snapshot != null) {
            preview = snapshot.clone();
            preview.setAmount(Math.max(1, Math.min(count, snapshot.getAmount())));
        }
        AuctionService.SellRequest request = new AuctionService.SellRequest(player.getUniqueId(), player.getName(), currency.id(),
                price.getAsLong(), count, empty ? 0 : hand.getAmount(), empty ? null : hand.getType().name(),
                empty ? List.of() : AuctionModule.lore(hand), limit, tax, preview, () -> take(player, snapshot, count));
        Currency used = currency;
        service().list(request, outcome -> {
            Map<String, String> values = Commands.values("{min}", CurrencyFormat.format(used, service().minPrice(used)),
                    "{max}", CurrencyFormat.format(used, service().maxPrice(used)), "{limit}", String.valueOf(limit),
                    "{currency}", used.plural(), "{item}", snapshot == null ? "" : AuctionModule.name(snapshot));
            if (outcome.result() == AuctionService.Result.INVALID_AMOUNT && snapshot != null)
                values.put("{max}", String.valueOf(snapshot.getAmount()));
            if (outcome.result() == AuctionService.Result.OK) {
                Map<String, String> listed = service().values(outcome.listing(), used);
                listed.put("{tax}", formatPercent(tax));
                Commands.reply(plugin, sender, "auction.listed", listed);
            } else if (outcome.result() == AuctionService.Result.STORAGE_ERROR) {
                Commands.reply(plugin, sender, "auction.list-failed");
            } else {
                module.failure(sender, outcome, values);
            }
        });
    }

    /** On the seller's thread: removes {@code count} items from the hand if it still holds the same item. */
    private byte[] take(Player player, ItemStack snapshot, int count) {
        PlayerInventory inventory = player.getInventory();
        ItemStack now = inventory.getItemInMainHand();
        if (snapshot == null || now == null || !now.isSimilar(snapshot) || now.getAmount() < count) return null;
        ItemStack part = now.clone();
        part.setAmount(count);
        byte[] bytes;
        try {
            bytes = ItemCodec.encode(part);
        } catch (Exception failure) {
            plugin.getLogger().warning("Could not save the item " + now.getType() + " for the auction house: " + failure.getMessage());
            return null;
        }
        if (now.getAmount() == count) {
            inventory.setItemInMainHand(null);
        } else {
            ItemStack rest = now.clone();
            rest.setAmount(now.getAmount() - count);
            inventory.setItemInMainHand(rest);
        }
        return bytes;
    }

    private static String formatPercent(int basisPoints) {
        if (basisPoints % 100 == 0) return String.valueOf(basisPoints / 100);
        return String.valueOf(basisPoints / 100.0);
    }

    // ---- browsing -----------------------------------------------------------------------------------------------

    private void list(CommandSender sender, List<Listing> source, int page, String search) {
        long now = service().now();
        String needle = search == null ? null : CommandWords.plain(search);
        List<Listing> shown = new ArrayList<>();
        for (Listing listing : source) {
            if (listing.expired(now)) continue;
            if (needle != null && !listing.search().contains(needle) && !CommandWords.plain(listing.sellerName()).contains(needle)) continue;
            shown.add(listing);
        }
        if (shown.isEmpty()) {
            if (search == null) plugin.messages().send(sender, "auction.list-empty");
            else plugin.messages().send(sender, "auction.search-empty", Commands.values("{text}", search));
            return;
        }
        shown.sort(Comparator.comparingLong(Listing::created).reversed());
        int pages = (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > pages) {
            plugin.messages().send(sender, "general.invalid-page", Commands.values("{pages}", String.valueOf(pages)));
            return;
        }
        plugin.messages().send(sender, "auction.list-header", Commands.values("{page}", String.valueOf(page), "{pages}", String.valueOf(pages)));
        for (Listing listing : shown.subList((page - 1) * PAGE_SIZE, Math.min(shown.size(), page * PAGE_SIZE))) line(sender, listing);
    }

    private void line(CommandSender sender, Listing listing) {
        Map<String, String> values = service().values(listing, plugin.currencies().get(listing.currency()));
        values.put("{time}", module.remaining(sender, listing.expires() - service().now()));
        plugin.messages().send(sender, "auction.list-line", values);
    }

    private void mine(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        List<Listing> mine = service().bySeller(player.getUniqueId());
        if (mine.isEmpty()) {
            plugin.messages().send(sender, "auction.mine-empty");
            return;
        }
        int limit = module.limit(player);
        plugin.messages().send(sender, "auction.mine-header", Commands.values("{count}", String.valueOf(mine.size()),
                "{limit}", limit < 0 ? "∞" : String.valueOf(limit)));
        mine.sort(Comparator.comparingLong(Listing::created).reversed());
        for (Listing listing : mine) line(sender, listing);
    }

    private void search(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "auction.search-usage");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (sender instanceof Player player) AuctionGui.open(module, player, 0, module.defaultSort(), text);
        else list(sender, service().active(), 1, text);
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "auction.info-usage");
            return;
        }
        Listing listing = service().get(args[1]);
        if (listing == null) {
            plugin.messages().send(sender, "auction.not-found", Commands.values("{id}", args[1]));
            return;
        }
        Map<String, String> values = service().values(listing, plugin.currencies().get(listing.currency()));
        values.put("{time}", module.remaining(sender, listing.expires() - service().now()));
        values.put("{tax}", formatPercent(listing.taxBasisPoints()));
        for (String key : List.of("auction.info-header", "auction.info-item", "auction.info-price", "auction.info-seller", "auction.info-expires"))
            plugin.messages().send(sender, key, values);
    }

    // ---- actions ------------------------------------------------------------------------------------------------

    private void collect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        service().collectAll(player.getUniqueId(), outcome -> {
            if (outcome.result() == AuctionService.Result.OK)
                Commands.reply(plugin, sender, outcome.remaining() > 0 ? "auction.collect-partial" : "auction.collected",
                        Commands.values("{count}", String.valueOf(outcome.count()), "{remaining}", String.valueOf(outcome.remaining())));
            else module.failure(sender, outcome, Map.of());
        });
    }

    private void cancel(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "auction.cancel-usage");
            return;
        }
        Player player = sender instanceof Player online ? online : null;
        boolean admin = sender.hasPermission("dymarket.admin.ah");
        String id = args[1];
        service().cancel(id, player == null ? null : player.getUniqueId(), admin, outcome -> {
            if (outcome.result() == AuctionService.Result.OK)
                Commands.reply(plugin, sender, "auction.cancelled", Commands.values("{id}", outcome.listing().id(),
                        "{item}", AuctionService.label(outcome.listing())));
            else module.failure(sender, outcome, Commands.values("{id}", id));
        });
    }

    private void buy(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "auction.buy-usage");
            return;
        }
        Listing listing = service().get(args[1]);
        if (listing == null) {
            plugin.messages().send(sender, "auction.not-found", Commands.values("{id}", args[1]));
            return;
        }
        boolean confirmed = args.length >= 3 && CommandWords.is(args[2], "confirm");
        if (service().settings().confirmPurchase() && !confirmed && !listing.seller().equals(player.getUniqueId())) {
            boolean turkish = Commands.turkish(plugin, sender);
            Map<String, String> values = service().values(listing, plugin.currencies().get(listing.currency()));
            values.put("{command}", "/" + label + " " + args[0] + " " + listing.id() + " " + CommandWords.display("confirm", turkish));
            plugin.messages().send(sender, "auction.confirm", values);
            return;
        }
        dev.dymarket.auction.gui.ConfirmGui.buy(module, player, listing);
    }

    // ---- TAB ----------------------------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("dymarket.ah")) return List.of();
        boolean turkish = Commands.turkish(plugin, sender);
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String word : SUBCOMMANDS) {
                if (word.equals("sell") && !sender.hasPermission("dymarket.ah.sell")) continue;
                options.add(CommandWords.display(word, turkish));
            }
            return Commands.complete(args[0], options);
        }
        String sub = CommandWords.canonical(args[0]);
        switch (sub) {
            case "sell" -> {
                if (args.length == 3) return Commands.complete(args[2], service().settings().allowedCurrencies());
            }
            case "cancel" -> {
                if (args.length == 2) {
                    List<Listing> source = sender.hasPermission("dymarket.admin.ah") ? service().active()
                            : sender instanceof Player player ? service().bySeller(player.getUniqueId()) : List.of();
                    return ids(args[1], source);
                }
            }
            case "buy", "info" -> {
                if (args.length == 2) return ids(args[1], service().active());
                if (args.length == 3 && sub.equals("buy")) return Commands.complete(args[2], List.of(CommandWords.display("confirm", turkish)));
            }
            case "list" -> {
                if (args.length == 2) return Commands.complete(args[1], List.of("1", "2", "3"));
            }
            default -> { }
        }
        return List.of();
    }

    private static List<String> ids(String typed, List<Listing> source) {
        List<String> ids = new ArrayList<>();
        for (Listing listing : source) ids.add(listing.id());
        List<String> matches = Commands.complete(typed, ids);
        return matches.size() > 30 ? matches.subList(0, 30) : matches;
    }
}
