package dev.dymarket;

import dev.dymarket.api.event.BalanceChangeEvent;
import dev.dymarket.auction.AuctionModule;
import dev.dymarket.gui.GuiListener;
import dev.dymarket.command.AdminSubcommand;
import dev.dymarket.command.BalanceCommand;
import dev.dymarket.command.BaltopCommand;
import dev.dymarket.command.Commands;
import dev.dymarket.command.CreditsCommand;
import dev.dymarket.command.DyMarketCommand;
import dev.dymarket.command.ModuleStubCommand;
import dev.dymarket.command.PayCommand;
import dev.dymarket.credits.CreditsService;
import dev.dymarket.economy.AlertService;
import dev.dymarket.economy.BaltopService;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyRegistry;
import dev.dymarket.economy.EconomyService;
import dev.dymarket.economy.RateLimiter;
import dev.dymarket.economy.VaultConsumer;
import dev.dymarket.economy.VaultProvider;
import dev.dymarket.estate.EstateModule;
import dev.dymarket.hook.DyDiscordHook;
import dev.dymarket.lang.Language;
import dev.dymarket.lang.MessageService;
import dev.dymarket.listener.PlayerListener;
import dev.dymarket.scheduler.Scheduler;
import dev.dymarket.storage.IoThread;
import dev.dymarket.storage.SqliteStore;
import dev.dymarket.storage.YamlFiles;
import dev.dymarket.update.ModrinthUpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class DyMarket extends JavaPlugin {
    /** bStats plugin id for DyMarket (https://bstats.org/plugin/bukkit/DyMarket/35262). */
    static final int BSTATS_ID = 35262;
    /** Accounts kept in memory before idle offline ones are dropped. */
    public static final int CACHE_SIZE = 10_000;
    /** Releases whose stock message texts are bundled, so texts nobody edited follow the current wording. */
    static final List<String> LEGACY_VERSIONS = List.of();

    private static DyMarket instance;

    private Scheduler scheduler;
    private IoThread io;
    private SqliteStore store;
    private volatile CurrencyRegistry currencies;
    private EconomyService economy;
    private CreditsService credits;
    private volatile CreditsService.Settings creditSettings;
    private final RateLimiter rateLimiter = new RateLimiter();
    private MessageService messages;
    private BaltopService baltop;
    private DyDiscordHook discord;
    private AlertService alerts;
    private PlayerListener playerListener;
    private VaultProvider vaultProvider;
    private ModrinthUpdateChecker updateChecker;
    private Object placeholders;
    private dev.dymarket.lib.bstats.bukkit.Metrics metrics;
    private volatile AuctionModule auction;
    private volatile EstateModule estate;
    private Scheduler.Task flushTask;
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private final Map<String, Boolean> modules = new ConcurrentHashMap<>();
    private final Map<String, AdminSubcommand> adminCommands = new ConcurrentHashMap<>();
    private final Map<String, Function<OfflinePlayer, String>> modulePlaceholders = new ConcurrentHashMap<>();
    private final List<BiConsumer<UUID, UUID>> mergeListeners = new CopyOnWriteArrayList<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        if (!validateFiles()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        addMissingConfigKeys();
        for (Language language : Language.values()) ensureMessageDefaults(language.fileName());
        if (!SqliteStore.driverPresent()) {
            getLogger().severe("The SQLite driver (org.sqlite.JDBC) is missing from this server; DyMarket needs it and is turning itself off.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        scheduler = new Scheduler(this);
        io = new IoThread(getLogger());
        File database = new File(getDataFolder(), getConfig().getString("storage.file", "data.db"));
        store = new SqliteStore(database, getLogger());
        try {
            // The server thread waits once at start-up; the copy and the opening happen on DyMarket-IO.
            io.call(() -> {
                try {
                    SqliteStore.backup(database, new File(getDataFolder(), "backups"), LocalDate.now(), 7);
                } catch (Exception failure) {
                    getLogger().warning("Could not back up " + database.getName() + ": " + failure.getMessage());
                }
                store.open();
                return null;
            });
        } catch (Exception failure) {
            getLogger().severe("Could not open " + database.getName() + "; DyMarket is turning itself off: " + failure.getMessage());
            io.shutdown();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        currencies = CurrencyRegistry.fromConfig(getConfig().getConfigurationSection("currencies"));
        economy = new EconomyService(store, io, currencies, getLogger());
        economy.guard((player, currency, delta, kind) -> {
            if (BalanceChangeEvent.getHandlerList().getRegisteredListeners().length == 0) return true;
            BalanceChangeEvent event = new BalanceChangeEvent(!Bukkit.isPrimaryThread(), player, currency.id(), currency.toMajor(delta), kind);
            getServer().getPluginManager().callEvent(event);
            return !event.isCancelled();
        });
        messages = new MessageService(this, entry -> io.execute(() -> {
            try {
                store.setLanguage(entry.getKey(), entry.getValue());
            } catch (Exception failure) {
                getLogger().warning("Could not save a language choice: " + failure.getMessage());
            }
        }));
        readCreditSettings();
        credits = new CreditsService(economy, () -> creditSettings);
        baltop = new BaltopService(economy, store, io, getLogger(), () -> getConfig().getInt("baltop.refresh-seconds", 60));
        discord = new DyDiscordHook(getLogger());
        alerts = new AlertService(this, discord);
        economy.listener(alerts::check);
        setupVault();

        Commands.register(this, new BalanceCommand(this), "balance");
        Commands.register(this, new PayCommand(this), "pay");
        Commands.register(this, new BaltopCommand(this), "baltop");
        Commands.register(this, new CreditsCommand(this), "credits");
        Commands.register(this, new DyMarketCommand(this), "dymarket");
        // The auction and estate modules replace these executors when they load.
        Commands.register(this, new ModuleStubCommand(this, "auction"), "ah");
        Commands.register(this, new ModuleStubCommand(this, "estate"), "estate");

        playerListener = new PlayerListener(this);
        getServer().getPluginManager().registerEvents(playerListener, this);
        // DyMarket windows stay read-only even while a module is being switched off.
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        onMerge((from, to) -> {
            AuctionModule module = auction;
            if (module != null) module.merged(from, to);
        });
        onMerge((from, to) -> {
            EstateModule module = estate;
            if (module != null) module.merged(from, to);
        });
        if (getConfig().getBoolean("auction.enabled", true)) startAuction();
        if (getConfig().getBoolean("estate.enabled", true)) startEstate();
        // After /reload or a plugin manager restart, players already online are loaded again.
        for (Player player : getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            String name = player.getName();
            io.execute(() -> playerListener.preload(id, name));
        }
        startFlushTask();
        startPlaceholders();
        startMetrics();
        // DyDiscord may enable after DyMarket; look once the server has finished loading.
        scheduler.runGlobal(() -> discord.start(getServer().getPluginManager().getPlugin("DyDiscord"),
                getConfig().getBoolean("security.alert.discord", true)));
        updateChecker = new ModrinthUpdateChecker(this, messages);
        updateChecker.start();
        getLogger().info("DyMarket " + getDescription().getVersion() + " enabled with " + currencies.ids().size() + " currencies"
                + (Scheduler.FOLIA ? " (Folia)." : "."));
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) updateChecker.cancel();
        if (flushTask != null) flushTask.cancel();
        if (auction != null) {
            auction.stop(true);
            auction = null;
        }
        if (estate != null) {
            estate.stop(true);
            estate = null;
        }
        stopPlaceholders();
        if (metrics != null) metrics.shutdown();
        if (vaultProvider != null) {
            getServer().getServicesManager().unregister(net.milkbowl.vault.economy.Economy.class, vaultProvider);
            vaultProvider = null;
        }
        if (scheduler != null) scheduler.cancelAll();
        if (economy != null) economy.flush();
        if (io != null) {
            io.execute(store::close);
            io.shutdown();
        }
        getLogger().info("DyMarket disabled.");
    }

    // ---- reload ---------------------------------------------------------------------------------------------

    public boolean tryReloadPlugin() {
        if (!validateFiles()) return false;
        String oldFile = getConfig().getString("storage.file", "data.db");
        reloadConfig();
        addMissingConfigKeys();
        for (Language language : Language.values()) ensureMessageDefaults(language.fileName());
        if (!oldFile.equals(getConfig().getString("storage.file", "data.db")))
            getLogger().warning("storage.file changes take effect after a restart.");
        currencies = CurrencyRegistry.fromConfig(getConfig().getConfigurationSection("currencies"));
        economy.currencies(currencies);
        readCreditSettings();
        messages.reload();
        baltop.clear();
        setupVault();
        startFlushTask();
        updateChecker.reload();
        discord.start(getServer().getPluginManager().getPlugin("DyDiscord"), getConfig().getBoolean("security.alert.discord", true));
        stopPlaceholders();
        startPlaceholders();
        boolean auctionWanted = getConfig().getBoolean("auction.enabled", true);
        if (auctionWanted && auction == null) startAuction();
        else if (!auctionWanted && auction != null) stopAuction();
        else if (auction != null) auction.reload();
        boolean estateWanted = getConfig().getBoolean("estate.enabled", true);
        if (estateWanted && estate == null) startEstate();
        else if (!estateWanted && estate != null) stopEstate();
        else if (estate != null) estate.reload();
        return true;
    }

    // ---- auction module -------------------------------------------------------------------------------------

    private void startAuction() {
        AuctionModule module = new AuctionModule(this);
        if (module.start()) auction = module;
    }

    private void stopAuction() {
        AuctionModule module = auction;
        auction = null;
        if (module != null) module.stop(false);
        getLogger().info("Auction house turned off.");
    }

    /** The auction module, or null while it is turned off. */
    public AuctionModule auction() {
        return auction;
    }

    // ---- estate module --------------------------------------------------------------------------------------

    /** Starts the estate module when DyClaim 1.3.1 or newer is present; otherwise /estate stays on the stub. */
    private void startEstate() {
        if (!EstateModule.dyClaimSupported()) {
            Plugin dyClaim = getServer().getPluginManager().getPlugin("DyClaim");
            getLogger().info(dyClaim == null || !dyClaim.isEnabled()
                    ? "Estate module is off: it needs DyClaim 1.3.1 or newer, which is not installed."
                    : "Estate module is off: DyClaim " + dyClaim.getDescription().getVersion() + " is too old; it needs DyClaim 1.3.1 or newer.");
            return;
        }
        EstateModule module = EstateModule.create(this);
        if (module.start()) estate = module;
    }

    private void stopEstate() {
        EstateModule module = estate;
        estate = null;
        if (module != null) module.stop(false);
        getLogger().info("Estate module turned off.");
    }

    /** The estate module, or null while it is turned off or DyClaim is missing. */
    public EstateModule estate() {
        return estate;
    }

    /** Applies setting changes only if the whole staged configuration is valid, then reloads. */
    public boolean applyConfigChanges(Map<String, Object> changes) {
        try {
            YamlConfiguration staged = new YamlConfiguration();
            staged.loadFromString(getConfig().saveToString());
            changes.forEach(staged::set);
            ConfigValidator.validate(staged);
            YamlFiles.save(staged, new File(getDataFolder(), "config.yml"));
            return tryReloadPlugin();
        } catch (Exception exception) {
            getLogger().warning("Setting change rejected; running settings retained: " + exception.getMessage());
            return false;
        }
    }

    private boolean validateFiles() {
        try {
            YamlConfiguration staged = new YamlConfiguration();
            staged.load(new File(getDataFolder(), "config.yml"));
            ConfigValidator.validate(staged);
            for (Language language : Language.values()) {
                File file = new File(getDataFolder(), language.fileName());
                if (file.exists()) new YamlConfiguration().load(file);
            }
            return true;
        } catch (Exception exception) {
            getLogger().warning("Configuration rejected; running settings retained: " + exception.getMessage());
            return false;
        }
    }

    private void readCreditSettings() {
        creditSettings = new CreditsService.Settings(getConfig().getBoolean("credits.exchange.enabled", true),
                getConfig().getDouble("credits.exchange.rate", 1000), getConfig().getDouble("credits.exchange.min", 1),
                getConfig().getDouble("credits.topup-bonus-percent", 0));
    }

    /** Writes changes every flush-interval-ticks on DyMarket-IO, then drops idle offline accounts. */
    private void startFlushTask() {
        if (flushTask != null) flushTask.cancel();
        long period = Math.max(1, getConfig().getInt("storage.flush-interval-ticks", 20));
        flushTask = scheduler.runAsyncTimer(() -> {
            if (!flushQueued.compareAndSet(false, true)) return;
            io.execute(() -> {
                try {
                    economy.flush();
                    economy.evictIdle(id -> Bukkit.getPlayer(id) != null, CACHE_SIZE);
                } finally {
                    flushQueued.set(false);
                }
            });
        }, period, period);
    }

    // ---- Vault ----------------------------------------------------------------------------------------------

    /**
     * Registers DyMarket's economy when the Vault currency uses {@code provider: internal}; with
     * {@code provider: vault} it uses the other plugin's economy instead and registers none.
     */
    private void setupVault() {
        Currency primary = currencies.primary();
        for (Currency currency : currencies.all()) economy.external(currency.id(), null);
        boolean vaultPresent = getServer().getPluginManager().getPlugin("Vault") != null;
        if (primary != null && primary.external()) {
            if (vaultProvider != null) {
                getServer().getServicesManager().unregister(net.milkbowl.vault.economy.Economy.class, vaultProvider);
                vaultProvider = null;
            }
            if (!vaultPresent) {
                getLogger().warning("currencies." + primary.id() + ".provider is vault, but Vault is not installed; that currency is unavailable.");
                return;
            }
            economy.external(primary.id(), new VaultConsumer(() -> currencies.get(primary.id())));
            getLogger().info("Using another plugin's Vault economy for " + primary.id() + ".");
            return;
        }
        if (!vaultPresent || vaultProvider != null) return;
        vaultProvider = new VaultProvider(this);
        getServer().getServicesManager().register(net.milkbowl.vault.economy.Economy.class, vaultProvider, this, ServicePriority.High);
        getLogger().info("Registered as the Vault economy (" + (primary == null ? "-" : primary.id()) + ").");
    }

    /** The highest-priority Vault economy that is not DyMarket's own, for the Vault importer. */
    public net.milkbowl.vault.economy.Economy otherVaultEconomy() {
        try {
            return VaultConsumer.find();
        } catch (LinkageError noVault) {
            return null;
        }
    }

    // ---- module hooks (auction, estate) ---------------------------------------------------------------------

    public boolean moduleLoaded(String module) { return modules.getOrDefault(module, false); }
    public void setModuleLoaded(String module, boolean loaded) { modules.put(module, loaded); }

    public AdminSubcommand adminCommand(String module) { return adminCommands.get(module); }
    public void registerAdminCommand(String module, AdminSubcommand handler) {
        if (handler == null) adminCommands.remove(module);
        else adminCommands.put(module, handler);
    }

    /** A placeholder answered by a module, such as "ah_listings"; null when none is registered. */
    public Function<OfflinePlayer, String> modulePlaceholder(String key) { return modulePlaceholders.get(key); }
    public void registerPlaceholder(String key, Function<OfflinePlayer, String> answer) {
        if (answer == null) modulePlaceholders.remove(key);
        else modulePlaceholders.put(key, answer);
    }

    /** Called after /dymarket merge moved listings and leases in storage (DyMarket-IO thread). */
    public void onMerge(BiConsumer<UUID, UUID> listener) { mergeListeners.add(listener); }
    public void fireMerge(UUID from, UUID to) {
        for (BiConsumer<UUID, UUID> listener : mergeListeners) {
            try {
                listener.accept(from, to);
            } catch (RuntimeException failure) {
                getLogger().warning("A merge listener failed: " + failure.getMessage());
            }
        }
    }

    // ---- accessors ------------------------------------------------------------------------------------------

    public static DyMarket getInstance() { return instance; }
    public Scheduler scheduler() { return scheduler; }
    public IoThread io() { return io; }
    public SqliteStore store() { return store; }
    public CurrencyRegistry currencies() { return currencies; }
    public EconomyService economy() { return economy; }
    public CreditsService credits() { return credits; }
    public RateLimiter rateLimiter() { return rateLimiter; }
    public MessageService messages() { return messages; }
    public BaltopService baltop() { return baltop; }
    public AlertService alerts() { return alerts; }
    public DyDiscordHook discord() { return discord; }

    // ---- placeholders and metrics ---------------------------------------------------------------------------

    /** PlaceholderAPI classes are only touched when that plugin is present. */
    private void startPlaceholders() {
        if (placeholders != null) return;
        Plugin papi = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) return;
        try {
            placeholders = dev.dymarket.hook.PlaceholderHook.register(this);
            if (placeholders != null) getLogger().info("PlaceholderAPI placeholders registered (%dymarket_...%).");
        } catch (LinkageError | RuntimeException exception) {
            getLogger().warning("PlaceholderAPI hook failed: " + exception);
        }
    }

    private void stopPlaceholders() {
        if (placeholders == null) return;
        try {
            dev.dymarket.hook.PlaceholderHook.unregister(placeholders);
        } catch (LinkageError | RuntimeException ignored) {
            // PlaceholderAPI is already gone.
        }
        placeholders = null;
    }

    private void startMetrics() {
        if (BSTATS_ID <= 0 || !getConfig().getBoolean("metrics.enabled", true)) return;
        try {
            metrics = new dev.dymarket.lib.bstats.bukkit.Metrics(this, BSTATS_ID);
            metrics.addCustomChart(new dev.dymarket.lib.bstats.charts.SimplePie("currencies_enabled", () -> String.valueOf(currencies.ids().size())));
            metrics.addCustomChart(new dev.dymarket.lib.bstats.charts.SimplePie("auction_enabled", () -> getConfig().getBoolean("auction.enabled", true) ? "Yes" : "No"));
            metrics.addCustomChart(new dev.dymarket.lib.bstats.charts.SimplePie("estate_enabled", () -> getConfig().getBoolean("estate.enabled", true) ? "Yes" : "No"));
            metrics.addCustomChart(new dev.dymarket.lib.bstats.charts.SimplePie("vault_provider", () -> {
                Currency primary = currencies.primary();
                return primary == null ? "none" : primary.provider();
            }));
            metrics.addCustomChart(new dev.dymarket.lib.bstats.charts.SimplePie("language_default", () -> getConfig().getString("language.default", "auto")));
        } catch (RuntimeException exception) {
            getLogger().warning("bStats could not start: " + exception.getMessage());
        }
    }

    // ---- default keys ---------------------------------------------------------------------------------------

    /** Adds settings that are new in this version to config.yml without touching existing values. */
    private void addMissingConfigKeys() {
        try (InputStream input = getResource("config.yml")) {
            if (input == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            File file = new File(getDataFolder(), "config.yml");
            YamlConfiguration existing = YamlFiles.load(file);
            if (copyMissing(defaults, existing, null)) {
                YamlFiles.save(existing, file);
                reloadConfig();
            }
        } catch (Exception exception) {
            getLogger().warning("Could not add missing settings to config.yml: " + exception.getMessage());
        }
    }

    private void ensureMessageDefaults(String fileName) {
        File file = new File(getDataFolder(), fileName);
        if (!file.exists()) {
            saveResource(fileName, false);
            return;
        }
        try (InputStream input = getResource(fileName)) {
            if (input == null) return;
            YamlConfiguration existing = YamlFiles.load(file);
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            boolean changed = copyMissing(defaults, existing, null);
            for (String version : LEGACY_VERSIONS) changed |= copyMissing(defaults, existing, legacy(fileName, version));
            if (changed) YamlFiles.save(existing, file);
        } catch (Exception exception) {
            getLogger().warning("Could not add missing message keys to " + fileName + ": " + exception.getMessage());
        }
    }

    /** The bundled text of a message file from an older release, used to recognise texts nobody edited. */
    private YamlConfiguration legacy(String fileName, String version) {
        try (InputStream input = getResource("legacy/" + fileName.replace(".yml", "-" + version + ".yml"))) {
            if (input == null) return new YamlConfiguration();
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            return new YamlConfiguration();
        }
    }

    /**
     * Adds keys that are new in this version. A text is only replaced when it is still exactly the stock text of
     * an older release; edited texts are never changed. Currency sections the user removed stay removed.
     */
    static boolean copyMissing(ConfigurationSection defaults, ConfigurationSection target, ConfigurationSection stock) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (defaults.getCurrentPath() != null && defaults.getCurrentPath().equals("currencies") && !target.contains(key)) continue;
            ConfigurationSection section = defaults.getConfigurationSection(key);
            if (section != null) {
                ConfigurationSection existing = target.getConfigurationSection(key);
                if (existing == null) {
                    if (target.contains(key)) continue;
                    existing = target.createSection(key);
                    changed = true;
                }
                ConfigurationSection oldStock = stock == null ? null : stock.getConfigurationSection(key);
                changed |= copyMissing(section, existing, oldStock);
            } else if (!target.contains(key)) {
                target.set(key, defaults.get(key));
                changed = true;
            } else if (stock != null && stock.contains(key) && !(stock.get(key) instanceof ConfigurationSection)
                    && Objects.equals(target.get(key), stock.get(key)) && !Objects.equals(target.get(key), defaults.get(key))) {
                target.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    static Set<String> moduleNames() {
        return Set.of("auction", "estate");
    }
}
