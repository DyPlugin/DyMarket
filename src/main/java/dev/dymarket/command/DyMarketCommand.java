package dev.dymarket.command;

import dev.dymarket.DyMarket;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.economy.Transaction;
import dev.dymarket.economy.TransactionKind;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.credits.CreditsService;
import dev.dymarket.importer.EssentialsImporter;
import dev.dymarket.importer.ImportEntry;
import dev.dymarket.importer.VaultImporter;
import dev.dymarket.lang.Language;
import dev.dymarket.util.CommandWords;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /dymarket: help, reload, language and the economy administration. English and Turkish sub-command words
 * are accepted everywhere ({@link CommandWords}).
 */
public final class DyMarketCommand implements CommandExecutor, TabCompleter {
    private static final int LOG_PAGE = 10;
    private final DyMarket plugin;
    private final AtomicBoolean importing = new AtomicBoolean();

    public DyMarketCommand(DyMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : CommandWords.canonical(args[0]);
        switch (sub) {
            case "help" -> help(sender);
            case "reload" -> reload(sender);
            case "language" -> language(sender, args);
            case "give" -> { if (economyAdmin(sender)) give(sender, args); }
            case "take" -> { if (economyAdmin(sender)) take(sender, args); }
            case "set" -> { if (economyAdmin(sender)) set(sender, args); }
            case "log" -> { if (economyAdmin(sender)) log(sender, args); }
            case "rollback" -> { if (economyAdmin(sender)) rollback(sender, args); }
            case "merge" -> { if (economyAdmin(sender)) merge(sender, args); }
            case "import" -> { if (economyAdmin(sender)) importBalances(sender, args); }
            case "ah", "auction" -> module(sender, "auction", "dymarket.admin.ah", args);
            case "estate" -> module(sender, "estate", "dymarket.admin.estate", args);
            default -> plugin.messages().send(sender, "general.unknown-command");
        }
        return true;
    }

    private boolean economyAdmin(CommandSender sender) {
        if (sender.hasPermission("dymarket.admin.economy")) return true;
        plugin.messages().send(sender, "general.no-permission");
        return false;
    }

    // ---- help, reload, language -----------------------------------------------------------------------------

    private void help(CommandSender sender) {
        List<String> lines = new ArrayList<>(plugin.messages().getList(sender, "help.header"));
        group(sender, lines, "dymarket.balance", "help.balance");
        group(sender, lines, "dymarket.balance.others", "help.balance-others");
        if (plugin.getConfig().getBoolean("pay.enabled", true)) group(sender, lines, "dymarket.pay", "help.pay");
        group(sender, lines, "dymarket.baltop", "help.baltop");
        if (plugin.credits().enabled()) {
            group(sender, lines, "dymarket.credits", "help.credits");
            group(sender, lines, "dymarket.credits.send", "help.credits-send");
            if (plugin.getConfig().getBoolean("credits.exchange.enabled", true)) group(sender, lines, "dymarket.credits.exchange", "help.credits-exchange");
        }
        if (plugin.moduleLoaded("auction")) {
            group(sender, lines, "dymarket.ah", "help.auction");
            group(sender, lines, "dymarket.ah.sell", "help.auction-sell");
        }
        if (plugin.moduleLoaded("estate")) {
            group(sender, lines, "dymarket.estate", "help.estate");
            group(sender, lines, "dymarket.estate.sell", "help.estate-sell");
            group(sender, lines, "dymarket.estate.rent", "help.estate-rent");
        }
        lines.addAll(plugin.messages().getList(sender, "help.language"));
        group(sender, lines, "dymarket.admin", "help.admin");
        group(sender, lines, "dymarket.admin.economy", "help.admin-economy");
        if (plugin.moduleLoaded("auction")) group(sender, lines, "dymarket.admin.ah", "help.admin-auction");
        if (plugin.moduleLoaded("estate")) group(sender, lines, "dymarket.admin.estate", "help.admin-estate");
        lines.forEach(sender::sendMessage);
    }

    private void group(CommandSender sender, List<String> lines, String permission, String key) {
        if (sender.hasPermission(permission)) lines.addAll(plugin.messages().getList(sender, key));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("dymarket.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        plugin.messages().send(sender, plugin.tryReloadPlugin() ? "general.reload" : "general.reload-failed");
    }

    private void language(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "general.invalid-language");
            return;
        }
        String code = CommandWords.is(args[1], "auto") ? "auto" : CommandWords.plain(args[1]);
        if (!code.equals("auto") && !Language.codes().contains(code)) {
            plugin.messages().send(sender, "general.invalid-language");
            return;
        }
        if (sender instanceof Player player) {
            plugin.messages().setPlayerLanguage(player.getUniqueId(), code);
            plugin.messages().send(sender, "general.language-changed", Map.of("{language}", plugin.messages().get(sender, "value.language-" + code)));
            return;
        }
        if (!sender.hasPermission("dymarket.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        if (!plugin.applyConfigChanges(Map.of("language.default", code))) {
            plugin.messages().send(sender, "general.reload-failed");
            return;
        }
        plugin.messages().send(sender, "general.server-language-changed", Map.of("{language}", plugin.messages().get(sender, "value.language-" + code)));
    }

    // ---- give, take, set ------------------------------------------------------------------------------------

    /** Parsed "&lt;player&gt; &lt;amount&gt; [currency] ..." arguments, or null after telling the sender what is wrong. */
    private record Amount(String player, Currency currency, long minor, int next) { }

    private Amount amount(CommandSender sender, String[] args, String usage, boolean allowZero) {
        if (args.length < 3) {
            plugin.messages().send(sender, usage);
            return null;
        }
        Currency currency = plugin.currencies().primary();
        int next = 3;
        if (args.length > 3 && plugin.currencies().get(args[3]) != null) {
            currency = plugin.currencies().get(args[3]);
            next = 4;
        }
        OptionalLong minor = CurrencyFormat.parse(currency, args[2]);
        if (minor.isEmpty() || minor.getAsLong() < 0 || !allowZero && minor.getAsLong() == 0) {
            plugin.messages().send(sender, "general.invalid-amount", Map.of("{amount}", args[2]));
            return null;
        }
        return new Amount(args[1], currency, minor.getAsLong(), next);
    }

    private void give(CommandSender sender, String[] args) {
        Amount parsed = amount(sender, args, "admin.give-usage", false);
        if (parsed == null) return;
        String txid = args.length > parsed.next() ? args[parsed.next()] : null;
        if (txid != null && !txid.matches("[A-Za-z0-9_.:\\-]{1,128}")) {
            plugin.messages().send(sender, "admin.invalid-txid", Map.of("{txid}", txid));
            return;
        }
        Currency currency = parsed.currency();
        plugin.io().execute(() -> {
            Commands.Target target = Commands.resolve(plugin, parsed.player());
            if (target == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", parsed.player()));
                return;
            }
            boolean topUp = txid != null && plugin.credits().credits() != null && currency.id().equals(plugin.credits().credits().id());
            TransactionResult result;
            long bonus = 0;
            if (topUp) {
                CreditsService.TopUp done = plugin.credits().topUp(target.id(), parsed.minor(), txid);
                result = done.delivery();
                bonus = done.bonusAmount();
            } else {
                result = plugin.economy().give(target.id(), currency, parsed.minor(), TransactionKind.GIVE, note(sender), txid);
            }
            Map<String, String> values = Commands.money(currency, parsed.minor());
            values.put("{player}", target.display());
            values.put("{txid}", txid == null ? "-" : txid);
            if (result.status() == TransactionResult.Status.ALREADY_APPLIED) {
                Commands.reply(plugin, sender, "admin.already-applied", values);
                return;
            }
            if (!result.success()) {
                Commands.failure(plugin, sender, result, currency, values);
                return;
            }
            values.put("{balance}", CurrencyFormat.format(currency, result.balance() + bonus));
            values.put("{id}", result.transaction().id());
            values.put("{bonus}", CurrencyFormat.format(currency, bonus));
            Commands.reply(plugin, sender, bonus > 0 ? "admin.given-bonus" : "admin.given", values);
            if (topUp) announceTopUp(target, currency, parsed.minor(), bonus);
        });
    }

    private void announceTopUp(Commands.Target target, Currency currency, long amount, long bonus) {
        Player online = Bukkit.getPlayer(target.id());
        if (online != null) {
            Map<String, String> values = Commands.money(currency, amount);
            Commands.reply(plugin, online, "credits.topup-received", values);
            if (bonus > 0) Commands.reply(plugin, online, "credits.bonus-received", Commands.money(currency, bonus));
        }
        if (plugin.getConfig().getBoolean("credits.announce-topups", false)) {
            Map<String, String> values = Commands.money(currency, amount);
            values.put("{player}", target.display());
            plugin.scheduler().runGlobal(() -> plugin.messages().broadcast(null, "credits.topup-announce", values));
        }
    }

    private void take(CommandSender sender, String[] args) {
        Amount parsed = amount(sender, args, "admin.take-usage", false);
        if (parsed == null) return;
        plugin.io().execute(() -> {
            Commands.Target target = Commands.resolve(plugin, parsed.player());
            if (target == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", parsed.player()));
                return;
            }
            TransactionResult result = plugin.economy().withdraw(target.id(), parsed.currency(), parsed.minor(), TransactionKind.TAKE, note(sender));
            Map<String, String> values = Commands.money(parsed.currency(), parsed.minor());
            values.put("{player}", target.display());
            if (!result.success()) {
                Commands.failure(plugin, sender, result, parsed.currency(), values);
                return;
            }
            values.put("{balance}", CurrencyFormat.format(parsed.currency(), result.balance()));
            values.put("{id}", result.transaction().id());
            Commands.reply(plugin, sender, "admin.taken", values);
        });
    }

    private void set(CommandSender sender, String[] args) {
        Amount parsed = amount(sender, args, "admin.set-usage", true);
        if (parsed == null) return;
        plugin.io().execute(() -> {
            Commands.Target target = Commands.resolve(plugin, parsed.player());
            if (target == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", parsed.player()));
                return;
            }
            TransactionResult result = plugin.economy().set(target.id(), parsed.currency(), parsed.minor(), note(sender));
            Map<String, String> values = Commands.money(parsed.currency(), parsed.minor());
            values.put("{player}", target.display());
            if (!result.success()) {
                Commands.failure(plugin, sender, result, parsed.currency(), values);
                return;
            }
            values.put("{balance}", CurrencyFormat.format(parsed.currency(), result.balance()));
            Commands.reply(plugin, sender, "admin.set", values);
        });
    }

    private static String note(CommandSender sender) {
        return "by " + sender.getName();
    }

    // ---- log, rollback, merge -------------------------------------------------------------------------------

    private void log(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin.log-usage");
            return;
        }
        int page = args.length > 2 ? Commands.page(args[2], 1) : 1;
        plugin.io().execute(() -> {
            Commands.Target target = Commands.resolve(plugin, args[1]);
            if (target == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", args[1]));
                return;
            }
            try {
                plugin.economy().flush();
                int total = plugin.store().transactionCount(target.id());
                List<Transaction> list = plugin.store().transactions(target.id(), (page - 1) * LOG_PAGE, LOG_PAGE);
                if (list.isEmpty()) {
                    Commands.reply(plugin, sender, total == 0 ? "admin.log-empty" : "general.invalid-page",
                            Commands.values("{player}", target.display(), "{pages}", String.valueOf(Math.max(1, (total + LOG_PAGE - 1) / LOG_PAGE))));
                    return;
                }
                SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                List<String> lines = new ArrayList<>();
                lines.add(plugin.messages().get(sender, "admin.log-header", Commands.values("{player}", target.display(),
                        "{page}", String.valueOf(page), "{pages}", String.valueOf(Math.max(1, (total + LOG_PAGE - 1) / LOG_PAGE)))));
                for (Transaction transaction : list) {
                    Currency currency = plugin.currencies().get(transaction.currency());
                    String amount = currency == null ? transaction.amount() + " " + transaction.currency()
                            : CurrencyFormat.format(currency, transaction.amount());
                    boolean incoming = target.id().equals(transaction.to());
                    UUID other = incoming ? transaction.from() : transaction.to();
                    lines.add(plugin.messages().get(sender, "admin.log-line", Commands.values("{time}", time.format(new Date(transaction.timestamp())),
                            "{id}", transaction.id(), "{kind}", transaction.kind().name(), "{sign}", incoming ? "+" : "-",
                            "{amount}", amount, "{other}", other == null ? "-" : name(other),
                            "{note}", transaction.note() == null ? "" : transaction.note(),
                            "{reversed}", transaction.reversedBy() == null ? "" : plugin.messages().get(sender, "admin.log-reversed",
                                    Commands.values("{id}", transaction.reversedBy())))));
                }
                Commands.replyLines(plugin, sender, lines);
            } catch (Exception failure) {
                plugin.getLogger().warning("Could not read the transaction log: " + failure.getMessage());
                Commands.reply(plugin, sender, "general.storage-error");
            }
        });
    }

    private String name(UUID id) {
        var account = plugin.economy().cached(id);
        if (account != null && account.name() != null) return account.name();
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }

    private void rollback(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin.rollback-usage");
            return;
        }
        boolean force = args.length > 2 && CommandWords.is(args[2], "force");
        String id = args[1];
        plugin.io().execute(() -> {
            TransactionResult result = plugin.economy().rollback(id, force);
            Map<String, String> values = Commands.values("{id}", id);
            if (result.status() == TransactionResult.Status.INSUFFICIENT_FUNDS) {
                Commands.reply(plugin, sender, "admin.rollback-negative", values);
                return;
            }
            if (!result.success()) {
                Commands.failure(plugin, sender, result, null, values);
                return;
            }
            Transaction reversal = result.transaction();
            Currency currency = plugin.currencies().get(reversal.currency());
            values.putAll(Commands.money(currency, reversal.amount()));
            values.put("{reversal}", reversal.id());
            Commands.reply(plugin, sender, "admin.rollback-done", values);
        });
    }

    private void merge(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "admin.merge-usage");
            return;
        }
        plugin.io().execute(() -> {
            Commands.Target from = Commands.resolve(plugin, args[1]);
            Commands.Target to = Commands.resolve(plugin, args[2]);
            if (from == null || to == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", from == null ? args[1] : args[2]));
                return;
            }
            if (from.id().equals(to.id())) {
                Commands.reply(plugin, sender, "admin.merge-same");
                return;
            }
            TransactionResult result = plugin.economy().merge(from.id(), to.id());
            Map<String, String> values = Commands.values("{from}", from.display(), "{to}", to.display());
            if (!result.success()) {
                Commands.failure(plugin, sender, result, null, values);
                return;
            }
            try {
                plugin.store().mergeOwnership(from.id(), to.id(), to.name());
            } catch (Exception failure) {
                plugin.getLogger().warning("Balances were merged, but moving listings and leases failed: " + failure.getMessage());
                Commands.reply(plugin, sender, "general.storage-error");
                return;
            }
            plugin.fireMerge(from.id(), to.id());
            values.put("{count}", String.valueOf(result.transactions().size()));
            Commands.reply(plugin, sender, "admin.merge-done", values);
        });
    }

    // ---- import ---------------------------------------------------------------------------------------------

    private void importBalances(CommandSender sender, String[] args) {
        boolean vault = args.length >= 2 && CommandWords.plain(args[1]).equals("vault");
        boolean essentials = args.length >= 2 && CommandWords.plain(args[1]).startsWith("essentials");
        if (!vault && !essentials) {
            plugin.messages().send(sender, "admin.import-usage");
            return;
        }
        Currency currency = plugin.currencies().primary();
        if (currency == null || currency.external()) {
            plugin.messages().send(sender, "admin.import-external");
            return;
        }
        File serverFolder = plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile();
        File userdata = new File(serverFolder, plugin.getConfig().getString("import.essentials-userdata", "plugins/Essentials/userdata"));
        EssentialsImporter essentialsImporter = new EssentialsImporter(userdata, plugin.getLogger());
        net.milkbowl.vault.economy.Economy source = null;
        if (vault) {
            source = plugin.otherVaultEconomy();
            if (source == null) {
                plugin.messages().send(sender, "admin.import-no-vault");
                return;
            }
        } else if (!essentialsImporter.present()) {
            plugin.messages().send(sender, "admin.import-not-found", Map.of("{path}", userdata.getPath()));
            return;
        }
        if (!importing.compareAndSet(false, true)) {
            plugin.messages().send(sender, "admin.import-running");
            return;
        }
        plugin.messages().send(sender, "admin.import-started");
        net.milkbowl.vault.economy.Economy vaultSource = source;
        // The other plugin's economy is read off the server thread; DyMarket's own writes go through the IO thread.
        plugin.scheduler().runAsync(() -> {
            try {
                List<ImportEntry> entries;
                int skipped = 0;
                if (vault) {
                    entries = new VaultImporter(vaultSource, plugin.getLogger()).read(Bukkit.getOfflinePlayers());
                } else {
                    EssentialsImporter.Result read = essentialsImporter.read();
                    entries = read.entries;
                    skipped = read.skipped;
                }
                int imported = 0, kept = 0, done = 0;
                for (ImportEntry entry : entries) {
                    long minor;
                    try {
                        minor = currency.toMinor(entry.amount());
                    } catch (ArithmeticException tooLarge) {
                        skipped++;
                        continue;
                    }
                    if (plugin.economy().importBalance(entry.id(), entry.name(), currency, minor)) imported++;
                    else kept++;
                    if (++done % 1000 == 0) {
                        plugin.getLogger().info("Import: " + done + " of " + entries.size() + " balances processed.");
                        plugin.economy().flush();
                    }
                }
                plugin.economy().flush();
                plugin.economy().evictIdle(id -> Bukkit.getPlayer(id) != null, DyMarket.CACHE_SIZE);
                plugin.getLogger().info("Import finished: " + imported + " imported, " + kept + " kept, " + skipped + " skipped.");
                Commands.reply(plugin, sender, "admin.import-done", Commands.values("{imported}", String.valueOf(imported),
                        "{kept}", String.valueOf(kept), "{skipped}", String.valueOf(skipped)));
            } catch (Exception failure) {
                plugin.getLogger().warning("Import failed: " + failure);
                Commands.reply(plugin, sender, "admin.import-failed");
            } finally {
                importing.set(false);
            }
        });
    }

    // ---- modules --------------------------------------------------------------------------------------------

    private void module(CommandSender sender, String module, String permission, String[] args) {
        if (!sender.hasPermission(permission)) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        AdminSubcommand handler = plugin.adminCommand(module);
        if (handler == null) {
            plugin.messages().send(sender, "general.module-not-loaded", Map.of("{module}", plugin.messages().get(sender, "value.module-" + module)));
            return;
        }
        handler.execute(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    // ---- TAB ------------------------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean turkish = Commands.turkish(plugin, sender);
        if (args.length == 1) {
            List<String> options = new ArrayList<>(CommandWords.choices(turkish, "help", "language"));
            if (sender.hasPermission("dymarket.admin")) options.add(CommandWords.display("reload", turkish));
            if (sender.hasPermission("dymarket.admin.economy"))
                options.addAll(CommandWords.choices(turkish, "give", "take", "set", "log", "rollback", "merge", "import"));
            if (sender.hasPermission("dymarket.admin.ah")) options.add("ah");
            if (sender.hasPermission("dymarket.admin.estate")) options.add("estate");
            return Commands.complete(args[0], options);
        }
        String sub = CommandWords.canonical(args[0]);
        if (sub.equals("language") && args.length == 2) {
            List<String> codes = new ArrayList<>();
            codes.add(CommandWords.display("auto", turkish));
            codes.addAll(Language.codes());
            return Commands.complete(args[1], codes);
        }
        if (sub.equals("ah") || sub.equals("auction") || sub.equals("estate")) {
            String module = sub.equals("estate") ? "estate" : "auction";
            AdminSubcommand handler = plugin.adminCommand(module);
            if (handler == null || !sender.hasPermission(module.equals("estate") ? "dymarket.admin.estate" : "dymarket.admin.ah")) return List.of();
            return handler.complete(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (!sender.hasPermission("dymarket.admin.economy")) return List.of();
        switch (sub) {
            case "give", "take", "set" -> {
                if (args.length == 2) return Commands.onlineNames(args[1]);
                if (args.length == 4) return Commands.complete(args[3], plugin.currencies().ids());
            }
            case "log" -> {
                if (args.length == 2) return Commands.onlineNames(args[1]);
            }
            case "merge" -> {
                if (args.length == 2 || args.length == 3) return Commands.onlineNames(args[args.length - 1]);
            }
            case "rollback" -> {
                if (args.length == 3) return Commands.complete(args[2], List.of(CommandWords.display("force", turkish)));
            }
            case "import" -> {
                if (args.length == 2) return Commands.complete(args[1], List.of("essentials", "vault"));
            }
            default -> { }
        }
        return List.of();
    }
}
