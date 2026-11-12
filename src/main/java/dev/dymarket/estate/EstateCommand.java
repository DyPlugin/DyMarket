package dev.dymarket.estate;

import dev.dymarket.DyMarket;
import dev.dymarket.command.Commands;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.estate.gui.EstateConfirmGui;
import dev.dymarket.estate.gui.EstateGui;
import dev.dymarket.util.CommandWords;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * /estate: sell, rent, unlist, list, info, buy, lease, autorenew, leave, evict and mine (English or Turkish words).
 * {@code list} opens the market window for players; every window action is also a command. Runs on the sender's
 * own thread; storage, payments and claim changes go through {@link EstateService}.
 */
public final class EstateCommand implements CommandExecutor, TabCompleter {
    private static final int PAGE_SIZE = 10;
    private static final List<String> SUBCOMMANDS = List.of("sell", "rent", "unlist", "list", "info", "buy", "lease", "autorenew", "leave", "evict", "mine");

    private final DyMarket plugin;
    private final EstateModule module;

    public EstateCommand(DyMarket plugin, EstateModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private EstateService service() {
        return module.service();
    }

    /** The world and chunk a player stands in. */
    private record Spot(String world, int x, int z) {
        static Spot of(Player player) {
            Location location = player.getLocation();
            return new Spot(location.getWorld() == null ? "" : location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dymarket.estate")) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) EstateGui.open(module, player, 0);
            else plugin.messages().send(sender, "estate.usage");
            return true;
        }
        switch (CommandWords.canonical(args[0])) {
            case "sell" -> offer(sender, args, EstateListing.Type.SALE);
            case "rent" -> offer(sender, args, EstateListing.Type.RENT);
            case "unlist" -> unlist(sender, args);
            case "list" -> list(sender, args.length >= 2 ? Commands.page(args[1], 1) : 1);
            case "info" -> info(sender, args);
            case "buy" -> take(sender, label, args, EstateListing.Type.SALE);
            case "lease" -> take(sender, label, args, EstateListing.Type.RENT);
            case "autorenew" -> autorenew(sender, args);
            case "leave" -> leave(sender, label, args);
            case "evict" -> evict(sender, args);
            case "mine" -> mine(sender);
            default -> plugin.messages().send(sender, "estate.usage");
        }
        return true;
    }

    // ---- sell and rent ------------------------------------------------------------------------------------------

    /** /estate sell &lt;price&gt; [currency] [here] and /estate rent &lt;price&gt; &lt;days&gt; [currency] [here]. */
    private void offer(CommandSender sender, String[] args, EstateListing.Type type) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        boolean sale = type == EstateListing.Type.SALE;
        if (!player.hasPermission(sale ? "dymarket.estate.sell" : "dymarket.estate.rent")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        String usage = sale ? "estate.sell-usage" : "estate.rent-usage";
        int fixed = sale ? 2 : 3;
        if (args.length < fixed) {
            plugin.messages().send(sender, usage);
            return;
        }
        EstateService.Settings settings = service().settings();
        boolean here = false;
        String currencyId = null;
        for (int i = fixed; i < args.length; i++) {
            if (CommandWords.is(args[i], "here")) here = true;
            else if (currencyId == null) currencyId = args[i].toLowerCase(Locale.ROOT);
            else {
                plugin.messages().send(sender, usage);
                return;
            }
        }
        Currency currency = plugin.currencies().get(currencyId == null ? settings.defaultCurrency() : currencyId);
        if (currency == null) {
            plugin.messages().send(sender, "general.unknown-currency", Commands.values("{currency}", currencyId == null ? settings.defaultCurrency() : currencyId,
                    "{currencies}", String.join(", ", settings.allowedCurrencies())));
            return;
        }
        OptionalLong price = CurrencyFormat.parse(currency, args[1]);
        if (price.isEmpty() || price.getAsLong() <= 0) {
            plugin.messages().send(sender, "general.invalid-amount", Map.of("{amount}", args[1]));
            return;
        }
        int days = 0;
        if (!sale) {
            try {
                days = Integer.parseInt(args[2]);
            } catch (NumberFormatException notNumber) {
                days = -1;
            }
        }
        Spot spot = Spot.of(player);
        int limit = module.limit(player);
        int tax = module.taxBasisPoints(player);
        EstateService.ListRequest request = new EstateService.ListRequest(player.getUniqueId(), player.getName(), spot.world(), spot.x(), spot.z(),
                type, currency.id(), price.getAsLong(), days, here, limit, tax);
        Currency used = currency;
        service().list(request, outcome -> {
            if (outcome.ok()) {
                Map<String, String> values = service().values(outcome.listing(), used);
                values.put("{tax}", percent(tax));
                Commands.reply(plugin, sender, sale ? "estate.listed-sale" : "estate.listed-rent", values);
                return;
            }
            if (outcome.result() == EstateService.Result.STORAGE_ERROR) {
                Commands.reply(plugin, sender, "estate.list-failed");
                return;
            }
            Map<String, String> values = Commands.values("{limit}", String.valueOf(limit), "{currency}", used.plural(), "{currency_id}", used.id());
            if (outcome.result() == EstateService.Result.PRICE_TOO_LOW) values.put("{min}", CurrencyFormat.format(used, service().minPrice(used, type)));
            if (outcome.result() == EstateService.Result.PRICE_TOO_HIGH) values.put("{max}", CurrencyFormat.format(used, service().maxPrice(used, type)));
            module.failure(sender, outcome, values);
        });
    }

    private static String percent(int basisPoints) {
        if (basisPoints % 100 == 0) return String.valueOf(basisPoints / 100);
        return String.valueOf(basisPoints / 100.0);
    }

    // ---- unlist -------------------------------------------------------------------------------------------------

    private void unlist(CommandSender sender, String[] args) {
        Player player = sender instanceof Player online ? online : null;
        EstateListing listing = find(sender, args.length >= 2 ? args[1] : null);
        if (listing == null) return;
        boolean admin = sender.hasPermission("dymarket.admin.estate");
        service().unlist(listing.id(), player == null ? null : player.getUniqueId(), admin, outcome -> {
            if (!outcome.ok()) {
                module.failure(sender, outcome, Commands.values("{id}", listing.id()));
                return;
            }
            Currency currency = plugin.currencies().get(listing.currency());
            Map<String, String> values = service().values(listing, currency);
            if (outcome.lease() != null) {
                values.put("{tenant}", outcome.lease().tenantName());
                values.put("{refund}", currency == null ? String.valueOf(outcome.amount()) : CurrencyFormat.format(currency, outcome.amount()));
                Commands.reply(plugin, sender, "estate.unlisted-refund", values);
            } else {
                Commands.reply(plugin, sender, listing.status() == EstateListing.Status.REMOVED ? "estate.removed" : "estate.unlisted", values);
            }
        });
    }

    /** A listing by id, or the one the player stands in; tells the sender when there is none. */
    private EstateListing find(CommandSender sender, String id) {
        if (id != null) {
            EstateListing listing = service().get(id);
            if (listing == null || !listing.status().open()) plugin.messages().send(sender, "estate.not-found", Commands.values("{id}", id));
            return listing == null || !listing.status().open() ? null : listing;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "estate.usage");
            return null;
        }
        Spot spot = Spot.of(player);
        EstateListing listing = service().listingAt(spot.world(), spot.x(), spot.z());
        if (listing == null) plugin.messages().send(sender, "estate.not-here");
        return listing;
    }

    // ---- browsing -----------------------------------------------------------------------------------------------

    private void list(CommandSender sender, int page) {
        if (sender instanceof Player player) {
            EstateGui.open(module, player, page - 1);
            return;
        }
        List<EstateListing> shown = service().active();
        if (shown.isEmpty()) {
            plugin.messages().send(sender, "estate.list-empty");
            return;
        }
        shown.sort(Comparator.comparingLong(EstateListing::created).reversed());
        int pages = (shown.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > pages) {
            plugin.messages().send(sender, "general.invalid-page", Commands.values("{pages}", String.valueOf(pages)));
            return;
        }
        plugin.messages().send(sender, "estate.list-header", Commands.values("{page}", String.valueOf(page), "{pages}", String.valueOf(pages)));
        for (EstateListing listing : shown.subList((page - 1) * PAGE_SIZE, Math.min(shown.size(), page * PAGE_SIZE))) line(sender, listing);
    }

    private void line(CommandSender sender, EstateListing listing) {
        plugin.messages().send(sender, listing.type() == EstateListing.Type.SALE ? "estate.list-line-sale" : "estate.list-line-rent",
                module.describe(sender, listing));
    }

    private void info(CommandSender sender, String[] args) {
        EstateListing listing = find(sender, args.length >= 2 ? args[1] : null);
        if (listing == null) return;
        Map<String, String> values = module.describe(sender, listing);
        values.put("{tax}", percent(listing.taxBasisPoints()));
        plugin.messages().send(sender, "estate.info-header", values);
        plugin.messages().send(sender, "estate.info-type", values);
        plugin.messages().send(sender, "estate.info-place", values);
        plugin.messages().send(sender, listing.type() == EstateListing.Type.SALE ? "estate.info-price-sale" : "estate.info-price-rent", values);
        plugin.messages().send(sender, "estate.info-owner", values);
        Lease lease = service().leaseOf(listing);
        if (lease != null) plugin.messages().send(sender, "estate.info-tenant", module.describe(sender, lease));
        else if (listing.expires() > 0) plugin.messages().send(sender, "estate.info-expires", values);
    }

    private void mine(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        List<EstateListing> listings = service().byOwner(player.getUniqueId());
        List<Lease> renting = service().leasesAsTenant(player.getUniqueId());
        if (listings.isEmpty() && renting.isEmpty()) {
            plugin.messages().send(sender, "estate.mine-empty");
            return;
        }
        int limit = module.limit(player);
        plugin.messages().send(sender, "estate.mine-header", Commands.values("{count}", String.valueOf(listings.size()),
                "{limit}", limit < 0 ? "∞" : String.valueOf(limit)));
        listings.sort(Comparator.comparingLong(EstateListing::created).reversed());
        for (EstateListing listing : listings) {
            Lease lease = service().leaseOf(listing);
            if (lease != null) plugin.messages().send(sender, "estate.mine-landlord", module.describe(sender, lease));
            else line(sender, listing);
        }
        for (Lease lease : renting) plugin.messages().send(sender, "estate.mine-tenant", module.describe(sender, lease));
    }

    // ---- buy and lease ------------------------------------------------------------------------------------------

    /** /estate buy [id] [confirm] and /estate lease [id] [confirm]; without an id, the listing the player stands in. */
    private void take(CommandSender sender, String label, String[] args, EstateListing.Type type) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        boolean confirmed = args.length >= 2 && CommandWords.is(args[args.length - 1], "confirm");
        String id = args.length >= 2 && !CommandWords.is(args[1], "confirm") ? args[1] : null;
        EstateListing listing = find(sender, id);
        if (listing == null) return;
        if (listing.type() != type) {
            module.failure(sender, EstateService.Outcome.of(EstateService.Result.WRONG_TYPE, listing), Map.of());
            return;
        }
        if (listing.owner().equals(player.getUniqueId())) {
            module.failure(sender, EstateService.Outcome.of(EstateService.Result.OWN_LISTING, listing), Map.of());
            return;
        }
        if (!confirmed) {
            boolean turkish = Commands.turkish(plugin, sender);
            Map<String, String> values = module.describe(sender, listing);
            values.put("{command}", "/" + label + " " + args[0] + " " + listing.id() + " " + CommandWords.display("confirm", turkish));
            plugin.messages().send(sender, type == EstateListing.Type.SALE ? "estate.confirm-buy" : "estate.confirm-lease", values);
            return;
        }
        EstateConfirmGui.take(module, player, listing);
    }

    // ---- lease actions ------------------------------------------------------------------------------------------

    /**
     * The lease meant by the command: by id, the one the player stands in, or the player's only one. Tells the
     * player when there is none or more than one.
     */
    private Lease findLease(Player player, String id, boolean asTenant) {
        if (id != null) {
            Lease lease = service().lease(id);
            if (lease == null) plugin.messages().send(player, "estate.no-lease");
            return lease;
        }
        Spot spot = Spot.of(player);
        Lease here = service().leaseAt(spot.world(), spot.x(), spot.z());
        if (here != null && (asTenant ? here.tenant() : here.owner()).equals(player.getUniqueId())) return here;
        List<Lease> mine = asTenant ? service().leasesAsTenant(player.getUniqueId()) : service().leasesAsOwner(player.getUniqueId());
        if (mine.size() == 1) return mine.get(0);
        if (mine.isEmpty()) {
            plugin.messages().send(player, "estate.no-lease");
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (Lease lease : mine) ids.add(lease.listingId());
        plugin.messages().send(player, "estate.which-lease", Commands.values("{leases}", String.join(", ", ids)));
        return null;
    }

    private void autorenew(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        Lease lease = findLease(player, args.length >= 2 ? args[1] : null, true);
        if (lease == null) return;
        service().autorenew(lease.id(), player.getUniqueId(), outcome -> {
            if (outcome.ok()) Commands.reply(plugin, sender, outcome.amount() == 1 ? "estate.autorenew-on" : "estate.autorenew-off",
                    Commands.values("{id}", lease.listingId()));
            else module.failure(sender, outcome, Map.of());
        });
    }

    private void leave(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        boolean confirmed = args.length >= 2 && CommandWords.is(args[args.length - 1], "confirm");
        String id = args.length >= 2 && !CommandWords.is(args[1], "confirm") ? args[1] : null;
        Lease lease = findLease(player, id, true);
        if (lease == null) return;
        if (!lease.tenant().equals(player.getUniqueId())) {
            module.failure(sender, EstateService.Outcome.of(EstateService.Result.NOT_TENANT, lease), Map.of());
            return;
        }
        if (!confirmed) {
            boolean turkish = Commands.turkish(plugin, sender);
            Map<String, String> values = module.describe(sender, lease);
            values.put("{command}", "/" + label + " " + args[0] + " " + lease.listingId() + " " + CommandWords.display("confirm", turkish));
            plugin.messages().send(sender, "estate.leave-confirm", values);
            return;
        }
        service().leave(lease.id(), player.getUniqueId(), outcome -> {
            if (outcome.ok()) Commands.reply(plugin, sender, "estate.left", Commands.values("{id}", lease.listingId()));
            else module.failure(sender, outcome, Map.of());
        });
    }

    private void evict(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return;
        }
        Lease lease = findLease(player, args.length >= 2 ? args[1] : null, false);
        if (lease == null) return;
        service().evict(lease.id(), player.getUniqueId(), outcome -> {
            if (outcome.ok()) Commands.reply(plugin, sender, outcome.amount() == 1 ? "estate.evicted-now" : "estate.evicted",
                    module.describe(sender, lease));
            else module.failure(sender, outcome, Map.of());
        });
    }

    // ---- TAB ----------------------------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("dymarket.estate")) return List.of();
        boolean turkish = Commands.turkish(plugin, sender);
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String word : SUBCOMMANDS) {
                if (word.equals("sell") && !sender.hasPermission("dymarket.estate.sell")) continue;
                if (word.equals("rent") && !sender.hasPermission("dymarket.estate.rent")) continue;
                options.add(CommandWords.display(word, turkish));
            }
            return Commands.complete(args[0], options);
        }
        String sub = CommandWords.canonical(args[0]);
        String last = args[args.length - 1];
        List<String> extras = new ArrayList<>(service().settings().allowedCurrencies());
        extras.add(CommandWords.display("here", turkish));
        switch (sub) {
            case "sell" -> {
                if (args.length >= 3 && args.length <= 4) return Commands.complete(last, extras);
            }
            case "rent" -> {
                if (args.length == 3) return Commands.complete(last, List.of(String.valueOf(service().settings().minDays()), "7", "30"));
                if (args.length >= 4 && args.length <= 5) return Commands.complete(last, extras);
            }
            case "buy", "lease" -> {
                if (args.length == 2) {
                    EstateListing.Type type = sub.equals("buy") ? EstateListing.Type.SALE : EstateListing.Type.RENT;
                    List<String> ids = new ArrayList<>();
                    for (EstateListing listing : service().active()) if (listing.type() == type) ids.add(listing.id());
                    return limit(Commands.complete(last, ids));
                }
                if (args.length == 3) return Commands.complete(last, List.of(CommandWords.display("confirm", turkish)));
            }
            case "info" -> {
                if (args.length == 2) return ids(last, service().active());
            }
            case "unlist" -> {
                if (args.length == 2 && sender instanceof Player player) return ids(last, service().byOwner(player.getUniqueId()));
            }
            case "autorenew", "leave", "evict" -> {
                if (args.length == 2 && sender instanceof Player player) {
                    List<Lease> leases = sub.equals("evict") ? service().leasesAsOwner(player.getUniqueId()) : service().leasesAsTenant(player.getUniqueId());
                    List<String> ids = new ArrayList<>();
                    for (Lease lease : leases) ids.add(lease.listingId());
                    return Commands.complete(last, ids);
                }
                if (args.length == 3 && sub.equals("leave")) return Commands.complete(last, List.of(CommandWords.display("confirm", turkish)));
            }
            case "list" -> {
                if (args.length == 2) return Commands.complete(last, List.of("1", "2", "3"));
            }
            default -> { }
        }
        return List.of();
    }

    private static List<String> ids(String typed, List<EstateListing> source) {
        List<String> ids = new ArrayList<>();
        for (EstateListing listing : source) ids.add(listing.id());
        return limit(Commands.complete(typed, ids));
    }

    private static List<String> limit(List<String> matches) {
        return matches.size() > 30 ? matches.subList(0, 30) : matches;
    }
}
