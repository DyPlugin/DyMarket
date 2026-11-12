package dev.dymarket.estate;

import dev.dymarket.DyMarket;
import dev.dymarket.api.event.EstateLeaseEndEvent;
import dev.dymarket.api.event.EstateLeaseStartEvent;
import dev.dymarket.api.event.EstateListEvent;
import dev.dymarket.api.event.EstateSaleEvent;
import dev.dymarket.command.Commands;
import dev.dymarket.command.ModuleStubCommand;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.economy.EconomyService;
import dev.dymarket.economy.TransactionKind;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.estate.gui.EstateWindow;
import dev.dymarket.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Wires the estate module into DyMarket: storage, the service, /estate, /dymarket estate, the placeholder, the
 * DyClaim listener and the renewal task. Also the server side of {@link EstateService.Platform} (threads,
 * messages, API events) and the {@link EstateService.Bank} backed by the economy core.
 */
public final class EstateModule implements EstateService.Platform {
    public static final String MODULE = "estate";

    private final DyMarket plugin;
    private final EstateStore store;
    private final EstateService service;
    private final AtomicBoolean checking = new AtomicBoolean();
    private Scheduler.Task checkTask;
    private Listener claimListener;

    private EstateModule(DyMarket plugin, ClaimBridge claims) {
        this.plugin = plugin;
        this.store = new EstateStore(plugin.store());
        this.service = new EstateService(store, new EconomyBank(plugin.economy()), this, claims, plugin::currencies,
                System::currentTimeMillis, plugin.getLogger());
    }

    /**
     * True when DyClaim is enabled and new enough to have the write API (transferClaim and timed trust). Uses
     * reflection only, so this class never links DyClaim when it is missing or old.
     */
    public static boolean dyClaimSupported() {
        try {
            Class<?> api = Class.forName("dev.dyclaim.api.DyClaimAPI");
            api.getMethod("transferClaim", UUID.class, UUID.class, String.class);
            api.getMethod("setTrust", UUID.class, UUID.class, Set.class, long.class);
            api.getMethod("removeTrust", UUID.class, UUID.class);
            return Boolean.TRUE.equals(api.getMethod("isAvailable").invoke(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException missing) {
            return false;
        }
    }

    /** Creates the module over DyClaim; call only after {@link #dyClaimSupported()} returned true. */
    public static EstateModule create(DyMarket plugin) {
        return new EstateModule(plugin, new DyClaimBridge());
    }

    /** Loads open listings and leases (waiting once for DyMarket-IO, like the database start-up) and registers everything. */
    public boolean start() {
        readSettings();
        try {
            plugin.io().call(() -> {
                store.prepare();
                service.load(store.loadListings(), store.loadLeases());
                return null;
            });
        } catch (Exception failure) {
            plugin.getLogger().log(Level.WARNING, "The estate module could not load its listings and stays off", failure);
            return false;
        }
        Commands.register(plugin, new EstateCommand(plugin, this), "estate");
        plugin.registerAdminCommand(MODULE, new EstateAdminCommand(plugin, this));
        plugin.registerPlaceholder("estate_listings", player -> String.valueOf(service.activeCount()));
        claimListener = new ClaimListener(service);
        plugin.getServer().getPluginManager().registerEvents(claimListener, plugin);
        startTask();
        plugin.setModuleLoaded(MODULE, true);
        plugin.getLogger().info("Estate module loaded with " + service.activeCount() + " listings and " + service.allLeases().size() + " leases.");
        return true;
    }

    /** Turns the module off: /estate answers "not loaded" again and open estate windows close. */
    public void stop(boolean disabling) {
        if (checkTask != null) checkTask.cancel();
        checkTask = null;
        if (claimListener != null) HandlerList.unregisterAll(claimListener);
        claimListener = null;
        plugin.setModuleLoaded(MODULE, false);
        plugin.registerAdminCommand(MODULE, null);
        plugin.registerPlaceholder("estate_listings", null);
        if (!disabling) Commands.register(plugin, new ModuleStubCommand(plugin, MODULE), "estate");
        for (Player player : Bukkit.getOnlinePlayers()) {
            Runnable close = () -> {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof EstateWindow) player.closeInventory();
            };
            if (!Scheduler.FOLIA) close.run();
            else if (!disabling) plugin.scheduler().run(player, close);
        }
    }

    public void reload() {
        readSettings();
        startTask();
    }

    /** After /dymarket merge (DyMarket-IO): listings and leases in memory follow the stored ones. */
    public void merged(UUID from, UUID to) {
        var account = plugin.economy().cached(to);
        service.merged(from, to, account == null ? null : account.name());
    }

    /** Renewals, grace and listing expiry every check-interval-minutes; the work runs on DyMarket-IO. */
    private void startTask() {
        if (checkTask != null) checkTask.cancel();
        long period = Math.max(1, plugin.getConfig().getLong("estate.rent.check-interval-minutes", 10)) * 1200L;
        checkTask = plugin.scheduler().runGlobalTimer(() -> {
            if (!checking.compareAndSet(false, true)) return;
            plugin.io().execute(() -> {
                try {
                    service.check();
                } finally {
                    checking.set(false);
                }
            });
        }, Math.min(period, 1200L), period);
    }

    private void readSettings() {
        FileConfiguration config = plugin.getConfig();
        String currency = config.getString("estate.currency", "money");
        List<String> allowed = config.getStringList("estate.allowed-currencies");
        if (allowed.isEmpty()) allowed = List.of(currency);
        Set<String> rights = new LinkedHashSet<>();
        for (String right : config.getStringList("estate.rent.rights")) rights.add(right.trim().toLowerCase(Locale.ROOT));
        service.settings(new EstateService.Settings(currency, List.copyOf(allowed), Math.max(1, config.getInt("estate.max-chunks-per-listing", 64)),
                config.getDouble("estate.tax-percent", 5.0), config.getBoolean("estate.sale.enabled", true),
                config.getDouble("estate.sale.min-price", 1.0), config.getDouble("estate.sale.max-price", 1_000_000_000.0),
                config.getBoolean("estate.rent.enabled", true), Math.max(1, config.getInt("estate.rent.min-days", 1)),
                Math.max(1, config.getInt("estate.rent.max-days", 90)), Set.copyOf(rights),
                Math.max(0, config.getLong("estate.rent.grace-hours", 24)) * EstateService.HOUR,
                Math.max(0, Math.min(100, config.getInt("estate.rent.refund-on-owner-cancel-percent", 100))),
                Math.max(0, config.getLong("estate.listing-expires-days", 30)) * EstateService.DAY));
    }

    // ---- accessors and rank values ----------------------------------------------------------------------------

    public EstateService service() { return service; }
    public DyMarket plugin() { return plugin; }

    /** Highest {@code dymarket.estate.limit.<n>} wins; {@code unlimited} or no such permission means no limit (-1). */
    public int limit(Player player) {
        if (player.hasPermission("dymarket.estate.limit.unlimited")) return -1;
        int best = -1;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            if (!info.getValue() || !node.startsWith("dymarket.estate.limit.")) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring("dymarket.estate.limit.".length())));
            } catch (NumberFormatException notNumber) {
                // Another node under this prefix.
            }
        }
        return best;
    }

    /** The configured estate tax; {@code dymarket.bypass.tax} pays none. */
    public int taxBasisPoints(Player player) {
        if (player.hasPermission("dymarket.bypass.tax")) return 0;
        return (int) Math.round(Math.max(0, Math.min(100, service.settings().taxPercent())) * 100);
    }

    // ---- Platform -----------------------------------------------------------------------------------------------

    @Override
    public void io(Runnable task) {
        plugin.io().execute(task);
    }

    @Override
    public void region(String worldName, int chunkX, int chunkZ, Runnable task) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            // DyClaim answers "no such claim" for an unloaded world; nothing needs a region thread then.
            task.run();
            return;
        }
        boolean owned = Scheduler.FOLIA ? Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ) : Bukkit.isPrimaryThread();
        if (owned) {
            task.run();
            return;
        }
        try {
            plugin.scheduler().run(new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8), task);
        } catch (RuntimeException schedulerGone) {
            plugin.getLogger().warning("Could not schedule a claim change at chunk " + chunkX + "," + chunkZ + " in " + worldName
                    + " (the server is stopping): " + schedulerGone.getMessage());
        }
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
        if (player == null || !player.isOnline()) {
            store.run();
            return;
        }
        try {
            plugin.scheduler().run(player, () -> plugin.messages().send(player, key, copy), store);
        } catch (RuntimeException schedulerGone) {
            store.run();
        }
    }

    private static boolean async() {
        return !Bukkit.isPrimaryThread();
    }

    @Override
    public boolean callList(EstateService.ListRequest request, List<ChunkPos> chunks) {
        if (EstateListEvent.getHandlerList().getRegisteredListeners().length == 0) return true;
        Player player = Bukkit.getPlayer(request.owner());
        Currency currency = plugin.currencies().get(request.currency());
        if (player == null || currency == null) return true;
        EstateListEvent event = new EstateListEvent(async(), player, request.type(), request.world(), chunks, currency.toMajor(request.price()),
                currency.id(), request.type() == EstateListing.Type.RENT ? request.days() : 0);
        plugin.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Override
    public boolean callSale(EstateListing listing, UUID buyer) {
        if (EstateSaleEvent.getHandlerList().getRegisteredListeners().length == 0) return true;
        EstateSaleEvent event = new EstateSaleEvent(async(), listing, buyer);
        plugin.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Override
    public boolean callLeaseStart(EstateListing listing, UUID tenant) {
        if (EstateLeaseStartEvent.getHandlerList().getRegisteredListeners().length == 0) return true;
        EstateLeaseStartEvent event = new EstateLeaseStartEvent(async(), listing, tenant);
        plugin.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Override
    public void callLeaseEnd(Lease lease, EstateService.EndReason reason) {
        if (EstateLeaseEndEvent.getHandlerList().getRegisteredListeners().length == 0) return;
        try {
            plugin.getServer().getPluginManager().callEvent(new EstateLeaseEndEvent(async(), lease, reason));
        } catch (RuntimeException listenerFailure) {
            plugin.getLogger().warning("A listener of EstateLeaseEndEvent failed: " + listenerFailure.getMessage());
        }
    }

    // ---- replies --------------------------------------------------------------------------------------------------

    /** Tells the sender why an estate action failed (anything but OK). Safe on any thread. */
    public void failure(CommandSender sender, EstateService.Outcome outcome, Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(extra);
        EstateListing listing = outcome.listing();
        Lease lease = outcome.lease();
        if (listing != null) values.putIfAbsent("{id}", listing.id());
        if (lease != null) values.putIfAbsent("{id}", lease.listingId());
        values.putIfAbsent("{id}", "");
        EstateService.Settings settings = service.settings();
        Currency currency = plugin.currencies().get(listing != null ? listing.currency() : values.getOrDefault("{currency_id}", settings.defaultCurrency()));
        String key = switch (outcome.result()) {
            case DISABLED -> "general.feature-disabled";
            case NOT_IN_CLAIM -> "estate.not-in-claim";
            case NOT_OWNER -> lease != null ? "estate.not-lease-owner" : "estate.not-owner";
            case TENANT -> "estate.tenant-cannot-list";
            case ALREADY_LISTED -> "estate.already-listed";
            case LEASED -> "estate.leased-claim";
            case CURRENCY_NOT_ALLOWED -> "estate.currency-not-allowed";
            case UNKNOWN_CURRENCY -> "general.unknown-currency";
            case PRICE_TOO_LOW -> "estate.price-too-low";
            case PRICE_TOO_HIGH -> "estate.price-too-high";
            case DAYS_OUT_OF_RANGE -> "estate.days-out-of-range";
            case LIMIT_REACHED -> "estate.limit-reached";
            case CANCELLED -> "estate.cancelled-by-plugin";
            case NOT_FOUND -> "estate.not-found";
            case NOT_AVAILABLE -> "estate.not-available";
            case WRONG_TYPE -> listing != null && listing.type() == EstateListing.Type.RENT ? "estate.wrong-type-rent" : "estate.wrong-type-sale";
            case OWN_LISTING -> "estate.own-listing";
            case CLAIM_FAILED -> "estate.claim-failed";
            case NO_LEASE -> "estate.no-lease";
            case NOT_TENANT -> "estate.not-tenant";
            case ALREADY_EVICTED -> "estate.already-evicted";
            case REFUND_NOT_AFFORDABLE -> "estate.refund-not-affordable";
            case PAYMENT_FAILED -> null;
            default -> "general.storage-error";
        };
        if (key == null) {
            TransactionResult payment = outcome.payment() == null ? TransactionResult.of(TransactionResult.Status.FAILED) : outcome.payment();
            Commands.failure(plugin, sender, payment, currency, values);
            return;
        }
        values.putIfAbsent("{currencies}", String.join(", ", settings.allowedCurrencies()));
        values.putIfAbsent("{min}", String.valueOf(settings.minDays()));
        values.putIfAbsent("{max}", String.valueOf(settings.maxDays()));
        values.putIfAbsent("{currency}", currency == null ? "" : currency.plural());
        if (outcome.result() == EstateService.Result.REFUND_NOT_AFFORDABLE && currency != null)
            values.put("{refund}", CurrencyFormat.format(currency, outcome.amount()));
        Commands.reply(plugin, sender, key, values);
    }

    /** "1d 4h" or "3h 20m" in the reader's language. */
    public String remaining(CommandSender reader, long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        long days = minutes / 1440, hours = minutes / 60 % 24, rest = minutes % 60;
        if (days > 0) return plugin.messages().get(reader, "estate.time-long",
                Commands.values("{days}", String.valueOf(days), "{hours}", String.valueOf(hours)));
        return plugin.messages().get(reader, "estate.time-short",
                Commands.values("{hours}", String.valueOf(hours), "{minutes}", String.valueOf(rest)));
    }

    /** The listing's placeholders plus {time} (until expiry) and {type} in the reader's language. */
    public Map<String, String> describe(CommandSender reader, EstateListing listing) {
        Map<String, String> values = service.values(listing, plugin.currencies().get(listing.currency()));
        values.put("{type}", plugin.messages().get(reader, listing.type() == EstateListing.Type.SALE ? "estate.type-sale" : "estate.type-rent"));
        values.put("{time}", listing.expires() <= 0 ? "-" : remaining(reader, listing.expires() - service.now()));
        return values;
    }

    /** The lease's placeholders plus {time} (until the paid period ends) and {autorenew} in the reader's language. */
    public Map<String, String> describe(CommandSender reader, Lease lease) {
        Map<String, String> values = service.values(lease, plugin.currencies().get(lease.currency()));
        values.put("{time}", lease.status() == Lease.Status.GRACE
                ? plugin.messages().get(reader, "estate.in-grace") : remaining(reader, lease.paidUntil() - service.now()));
        values.put("{autorenew}", plugin.messages().get(reader, lease.autorenew() ? "estate.state-on" : "estate.state-off"));
        return values;
    }

    // ---- Bank -----------------------------------------------------------------------------------------------------

    /** Payments through the economy core, logged as ESTATE_SALE or ESTATE_RENT; refunds as REFUND. */
    static final class EconomyBank implements EstateService.Bank {
        private final EconomyService economy;

        EconomyBank(EconomyService economy) {
            this.economy = economy;
        }

        @Override
        public TransactionResult charge(UUID payer, Currency currency, long price, long tax, TransactionKind kind, String note) {
            long balance = economy.balance(payer, currency);
            if (balance < price) return TransactionResult.of(TransactionResult.Status.INSUFFICIENT_FUNDS, balance);
            long share = price - tax;
            TransactionResult paid = share > 0 ? economy.withdraw(payer, currency, share, kind, note) : null;
            if (paid != null && !paid.success()) return paid;
            if (tax <= 0) return paid;
            TransactionResult taxed = economy.withdraw(payer, currency, tax, kind, note + " tax");
            if (!taxed.success() && paid != null) economy.deposit(payer, currency, share, TransactionKind.REFUND, note + " refund");
            return taxed;
        }

        @Override
        public TransactionResult credit(UUID receiver, Currency currency, long amount, TransactionKind kind, String note) {
            return economy.deposit(receiver, currency, amount, kind, note);
        }

        @Override
        public TransactionResult refund(UUID receiver, Currency currency, long amount, String note) {
            return economy.deposit(receiver, currency, amount, TransactionKind.REFUND, note);
        }

        @Override
        public long balance(UUID player, Currency currency) {
            return economy.balance(player, currency);
        }

        @Override
        public long refundFromOwner(UUID owner, UUID tenant, Currency currency, long amount, String note) {
            long move = Math.min(amount, economy.balance(owner, currency));
            if (move <= 0) return 0;
            TransactionResult moved = economy.transfer(owner, tenant, currency, move, TransactionKind.REFUND, note);
            return moved.success() ? move : 0;
        }
    }
}
