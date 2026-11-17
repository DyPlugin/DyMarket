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

    /** Rows shown to players on a help page; the console sees all rows at once. */
    static final int HELP_PAGE_SIZE = 8;
    static final List<String> PLAYER_HELP = List.of("balance", "balance-others", "pay", "baltop", "credits", "credits-send",
            "credits-exchange", "ah", "ah-sell", "ah-search", "ah-collect", "estate", "estate-sell", "estate-rent", "estate-mine",
            "language", "short");
    static final List<String> ADMIN_HELP = List.of("give", "take", "set", "reset", "log", "rollback", "merge", "import",
            "ah-remove", "ah-price", "ah-purge", "estate-unlist", "lang", "reload", "dyma", "eco");
    /** /economy (/eco): only the balance commands, for staff who manage money. */
    static final List<String> ECONOMY_WORDS = List.of("give", "take", "set", "reset", "log", "rollback", "merge", "import");
    /** Admin words that still work straight after /dymarket, as in 1.0 (web stores deliver with /dymarket give). */
    private static final List<String> LEGACY_ADMIN_WORDS = List.of("give", "take", "set", "reset", "log", "rollback", "merge", "import",
            "ah", "auction", "estate", "reload");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // "/dymarket:market" is shown as "/market" in the next-page hint.
        label = label.substring(label.indexOf(':') + 1);
        if (command.getName().equalsIgnoreCase("dyma")) {
            admin(sender, "/" + label, args);
            return true;
        }
        if (command.getName().equalsIgnoreCase("economy")) {
            economy(sender, "/" + label, args);
            return true;
        }
        String sub = args.length == 0 ? "help" : CommandWords.canonical(args[0]);
        switch (sub) {
            case "help" -> help(sender, false, args.length > 1 ? args[1] : null, "/" + label);
            case "language" -> language(sender, args);
            case "admin" -> admin(sender, "/" + label + " " + args[0], Arrays.copyOfRange(args, 1, args.length));
            default -> {
                if (!LEGACY_ADMIN_WORDS.contains(sub) || !adminAction(sender, sub, args))
                    plugin.messages().send(sender, "general.unknown-command");
            }
        }
        return true;
    }

    /** /dymarket admin ... and /dyma ...: the admin help, or one admin action. */
    private void admin(CommandSender sender, String root, String[] args) {
        if (!anyAdmin(sender)) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        String sub = args.length == 0 ? "help" : CommandWords.canonical(args[0]);
        if (sub.equals("help")) {
            help(sender, true, args.length > 1 ? args[1] : null, root);
            return;
        }
        if (sub.equals("language")) {
            serverLanguage(sender, args);
            return;
        }
        if (!adminAction(sender, sub, args)) plugin.messages().send(sender, "admin.unknown", Commands.values("{command}", root + " " + helpWord(sender)));
    }

    /** /economy ...: the economy help, or one balance command. */
    private void economy(CommandSender sender, String root, String[] args) {
        if (!sender.hasPermission("dymarket.admin.economy")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        String sub = args.length == 0 ? "help" : CommandWords.canonical(args[0]);
        if (sub.equals("help")) {
            List<String> rows = new ArrayList<>();
            for (String word : ECONOMY_WORDS) rows.add("admin-help." + word);
            help(sender, rows, "economy-help.header", "admin-help.footer", args.length > 1 ? args[1] : null, root, "/dyma ");
            return;
        }
        if (!ECONOMY_WORDS.contains(sub) || !adminAction(sender, sub, args))
            plugin.messages().send(sender, "admin.unknown", Commands.values("{command}", root + " " + helpWord(sender)));
    }

    private boolean anyAdmin(CommandSender sender) {
        return sender.hasPermission("dymarket.admin") || sender.hasPermission("dymarket.admin.economy")
                || sender.hasPermission("dymarket.admin.ah") || sender.hasPermission("dymarket.admin.estate");
    }

    /** Runs one admin action named by {@code sub} (args[0] is the action word); false when there is no such action. */
    private boolean adminAction(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "reload" -> reload(sender);
            case "give" -> { if (economyAdmin(sender)) give(sender, args); }
            case "take" -> { if (economyAdmin(sender)) take(sender, args); }
            case "set" -> { if (economyAdmin(sender)) set(sender, args); }
            case "reset" -> { if (economyAdmin(sender)) reset(sender, args); }
            case "log" -> { if (economyAdmin(sender)) log(sender, args); }
            case "rollback" -> { if (economyAdmin(sender)) rollback(sender, args); }
            case "merge" -> { if (economyAdmin(sender)) merge(sender, args); }
            case "import" -> { if (economyAdmin(sender)) importBalances(sender, args); }
            case "ah", "auction" -> module(sender, "auction", "dymarket.admin.ah", args);
            case "estate" -> module(sender, "estate", "dymarket.admin.estate", args);
            default -> { return false; }
        }
        return true;
    }

    private boolean economyAdmin(CommandSender sender) {
        if (sender.hasPermission("dymarket.admin.economy")) return true;
        plugin.messages().send(sender, "general.no-permission");
        return false;
    }

    private String helpWord(CommandSender sender) {
        return CommandWords.display("help", Commands.turkish(plugin, sender));
    }

    // ---- help, reload, language -----------------------------------------------------------------------------

    List<String> helpRows(CommandSender sender, boolean admin) {
        List<String> rows = new ArrayList<>();
        boolean player = sender instanceof Player;
        if (admin) {
            for (String row : ADMIN_HELP) {
                boolean allowed = switch (row) {
                    case "lang", "reload" -> sender.hasPermission("dymarket.admin");
                    case "dyma" -> anyAdmin(sender);
                    case "eco" -> sender.hasPermission("dymarket.admin.economy");
                    case "ah-remove", "ah-price", "ah-purge" -> plugin.moduleLoaded("auction") && sender.hasPermission("dymarket.admin.ah");
                    case "estate-unlist" -> plugin.moduleLoaded("estate") && sender.hasPermission("dymarket.admin.estate");
                    default -> sender.hasPermission("dymarket.admin.economy");
                };
                if (allowed) rows.add("admin-help." + row);
            }
            return rows;
        }
        boolean credits = plugin.credits().enabled();
        boolean auction = plugin.moduleLoaded("auction");
        boolean estate = plugin.moduleLoaded("estate");
        for (String row : PLAYER_HELP) {
            boolean allowed = switch (row) {
                case "balance" -> player && sender.hasPermission("dymarket.balance");
                case "balance-others" -> sender.hasPermission("dymarket.balance.others");
                case "pay" -> player && plugin.getConfig().getBoolean("pay.enabled", true) && sender.hasPermission("dymarket.pay");
                case "baltop" -> sender.hasPermission("dymarket.baltop");
                case "credits" -> player && credits && sender.hasPermission("dymarket.credits");
                case "credits-send" -> player && credits && sender.hasPermission("dymarket.credits.send");
                case "credits-exchange" -> player && credits && plugin.getConfig().getBoolean("credits.exchange.enabled", true)
                        && sender.hasPermission("dymarket.credits.exchange");
                case "ah", "ah-search" -> auction && sender.hasPermission("dymarket.ah");
                case "ah-sell" -> player && auction && sender.hasPermission("dymarket.ah.sell");
                case "ah-collect" -> player && auction && sender.hasPermission("dymarket.ah");
                case "estate" -> estate && sender.hasPermission("dymarket.estate");
                case "estate-sell" -> player && estate && sender.hasPermission("dymarket.estate.sell");
                case "estate-rent" -> player && estate && sender.hasPermission("dymarket.estate.rent");
                case "estate-mine" -> player && estate && sender.hasPermission("dymarket.estate");
                default -> true;
            };
            if (allowed) rows.add("help-v2." + row);
        }
        if (anyAdmin(sender)) rows.add("help-v2.admin");
        return rows;
    }

    /** Players get short pages with the command for the next one; the console gets every row at once. */
    private void help(CommandSender sender, boolean admin, String requestedPage, String root) {
        help(sender, helpRows(sender, admin), admin ? "admin-help.header" : "help-v2.header", admin ? "admin-help.footer" : "help-v2.footer",
                requestedPage, root, null);
    }

    /** {@code command} in the rows, when given, is replaced with {@code root}: /eco shows "/eco give" for "/dyma give". */
    private void help(CommandSender sender, List<String> rows, String header, String footer, String requestedPage, String root, String command) {
        int size = sender instanceof Player ? HELP_PAGE_SIZE : Math.max(1, rows.size());
        int pages = Math.max(1, (rows.size() + size - 1) / size);
        int page = Math.min(Commands.page(requestedPage, 1), pages);
        plugin.messages().send(sender, header);
        for (String key : rows.subList((page - 1) * size, Math.min(rows.size(), page * size))) {
            if (command == null) plugin.messages().send(sender, key);
            else sender.sendMessage(plugin.messages().get(sender, key).replace(command, root + " "));
        }
        if (pages > 1) {
            String next = root + " " + helpWord(sender) + " " + (page + 1);
            plugin.messages().send(sender, page < pages ? "help-v2.page-next" : "help-v2.page",
                    Commands.values("{page}", String.valueOf(page), "{pages}", String.valueOf(pages), "{command}", next));
        }
        plugin.messages().send(sender, footer);
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("dymarket.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        plugin.messages().send(sender, plugin.tryReloadPlugin() ? "general.reload" : "general.reload-failed");
    }

    /** /dyma language &lt;auto|en|tr|de|es|ru&gt;: the language for everyone (players can still pick their own). */
    private void serverLanguage(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dymarket.admin")) {
            plugin.messages().send(sender, "general.no-permission");
            return;
        }
        String code = args.length < 2 ? null : CommandWords.is(args[1], "auto") ? "auto" : CommandWords.plain(args[1]);
        if (code == null || !code.equals("auto") && !Language.codes().contains(code)) {
            Commands.problem(plugin, sender, "general.invalid-language", Map.of(), "admin.language-usage");
            return;
        }
        if (!plugin.applyConfigChanges(Map.of("language.default", code))) {
            plugin.messages().send(sender, "general.reload-failed");
            return;
        }
        plugin.messages().send(sender, "general.server-language-changed", Map.of("{language}", plugin.messages().get(sender, "value.language-" + code)));
    }

    private void language(CommandSender sender, String[] args) {
        String code = args.length < 2 ? null : CommandWords.is(args[1], "auto") ? "auto" : CommandWords.plain(args[1]);
        if (code == null || !code.equals("auto") && !Language.codes().contains(code)) {
            Commands.problem(plugin, sender, "general.invalid-language", Map.of(), "general.language-usage");
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

    /** Accepts "&lt;player&gt; &lt;amount&gt; [currency]" and "&lt;player&gt; &lt;currency&gt; &lt;amount&gt;". */
    private Amount amount(CommandSender sender, String[] args, String usage, boolean allowZero) {
        if (args.length < 3) {
            plugin.messages().send(sender, usage);
            return null;
        }
        Currency currency = plugin.currencies().primary();
        String amountText = args[2];
        int next = 3;
        Currency first = plugin.currencies().get(args[2]);
        if (first != null && args.length > 3) {
            currency = first;
            amountText = args[3];
            next = 4;
        } else if (args.length > 3 && plugin.currencies().get(args[3]) != null) {
            currency = plugin.currencies().get(args[3]);
            next = 4;
        } else if (first != null) {
            Map<String, String> values = Commands.money(first, 0);
            values.put("{currency}", first.plural());
            Commands.problem(plugin, sender, "admin.amount-missing", values, usage);
            return null;
        }
        OptionalLong minor = CurrencyFormat.parse(currency, amountText);
        if (minor.isEmpty() || minor.getAsLong() < 0 || !allowZero && minor.getAsLong() == 0) {
            Commands.problem(plugin, sender, "general.invalid-amount", Map.of("{amount}", amountText), usage);
            return null;
        }
        return new Amount(args[1], currency, minor.getAsLong(), next);
    }

    /** /dyma reset &lt;player&gt; [currency]: back to the starting balance (every currency when none is named). */
    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "admin.reset-usage");
            return;
        }
        Currency only = args.length > 2 ? plugin.currencies().get(args[2]) : null;
        if (args.length > 2 && only == null) {
            Commands.problem(plugin, sender, "general.unknown-currency", Commands.values("{currency}", args[2],
                    "{currencies}", String.join(", ", plugin.currencies().ids())), "admin.reset-usage");
            return;
        }
        plugin.io().execute(() -> {
            Commands.Target target = Commands.resolve(plugin, args[1]);
            if (target == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", args[1]));
                return;
            }
            for (Currency currency : only != null ? List.of(only) : plugin.currencies().all()) {
                if (currency.external()) continue;
                TransactionResult result = plugin.economy().set(target.id(), currency, currency.startingBalance(), note(sender));
                Map<String, String> values = Commands.money(currency, currency.startingBalance());
                values.put("{player}", target.display());
                if (!result.success()) {
                    Commands.failure(plugin, sender, result, currency, values);
                    return;
                }
                values.put("{balance}", CurrencyFormat.format(currency, result.balance()));
                values.put("{currency}", currency.plural());
                Commands.reply(plugin, sender, "admin.reset", values);
            }
        });
    }

    private void give(CommandSender sender, String[] args) {
        Amount parsed = amount(sender, args, "admin.give-usage", false);
        if (parsed == null) return;
        String txid = args.length > parsed.next() ? args[parsed.next()] : null;
        if (txid != null && !txid.matches("[A-Za-z0-9_.:\\-]{1,128}")) {
            Commands.problem(plugin, sender, "admin.invalid-txid", Map.of("{txid}", txid), "admin.give-usage");
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
        if (command.getName().equalsIgnoreCase("dyma")) return adminComplete(sender, args, turkish);
        if (command.getName().equalsIgnoreCase("economy")) {
            if (!sender.hasPermission("dymarket.admin.economy")) return List.of();
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of(CommandWords.display("help", turkish)));
                options.addAll(CommandWords.choices(turkish, ECONOMY_WORDS.toArray(String[]::new)));
                return Commands.complete(args[0], options);
            }
            if (args.length == 2 && CommandWords.canonical(args[0]).equals("help")) {
                int pages = (ECONOMY_WORDS.size() + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE;
                List<String> options = new ArrayList<>();
                for (int i = 1; i <= pages && sender instanceof Player; i++) options.add(String.valueOf(i));
                return Commands.complete(args[1], options);
            }
            return ECONOMY_WORDS.contains(CommandWords.canonical(args[0])) ? adminComplete(sender, args, turkish) : List.of();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(CommandWords.choices(turkish, "help", "language"));
            if (anyAdmin(sender)) options.add("admin");
            return Commands.complete(args[0], options);
        }
        String sub = CommandWords.canonical(args[0]);
        if (sub.equals("help") && args.length == 2) return pages(sender, false, args[1]);
        if (sub.equals("language") && args.length == 2) return languages(args[1], turkish);
        if (sub.equals("admin")) return adminComplete(sender, Arrays.copyOfRange(args, 1, args.length), turkish);
        if (LEGACY_ADMIN_WORDS.contains(sub)) return adminComplete(sender, args, turkish);
        return List.of();
    }

    private List<String> adminComplete(CommandSender sender, String[] args, boolean turkish) {
        if (!anyAdmin(sender) || args.length == 0) return List.of();
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add(CommandWords.display("help", turkish));
            if (sender.hasPermission("dymarket.admin.economy"))
                options.addAll(CommandWords.choices(turkish, "give", "take", "set", "reset", "log", "rollback", "merge", "import"));
            if (sender.hasPermission("dymarket.admin.ah") && plugin.moduleLoaded("auction")) options.add("ah");
            if (sender.hasPermission("dymarket.admin.estate") && plugin.moduleLoaded("estate")) options.add("estate");
            if (sender.hasPermission("dymarket.admin")) options.addAll(CommandWords.choices(turkish, "language", "reload"));
            return Commands.complete(args[0], options);
        }
        String sub = CommandWords.canonical(args[0]);
        if (sub.equals("help") && args.length == 2) return pages(sender, true, args[1]);
        if (sub.equals("language") && args.length == 2 && sender.hasPermission("dymarket.admin")) return languages(args[1], turkish);
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
                if (args.length == 3) return args[2].isEmpty() || Character.isDigit(args[2].charAt(0))
                        ? Commands.amountHints(args[2]) : Commands.complete(args[2], plugin.currencies().ids());
                if (args.length == 4) return plugin.currencies().get(args[2]) != null
                        ? Commands.amountHints(args[3]) : Commands.complete(args[3], plugin.currencies().ids());
            }
            case "reset" -> {
                if (args.length == 2) return Commands.onlineNames(args[1]);
                if (args.length == 3) return Commands.complete(args[2], plugin.currencies().ids());
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

    private List<String> pages(CommandSender sender, boolean admin, String typed) {
        if (!(sender instanceof Player)) return List.of();
        int pages = Math.max(1, (helpRows(sender, admin).size() + HELP_PAGE_SIZE - 1) / HELP_PAGE_SIZE);
        List<String> options = new ArrayList<>();
        for (int i = 1; i <= pages; i++) options.add(String.valueOf(i));
        return Commands.complete(typed, options);
    }

    private static List<String> languages(String typed, boolean turkish) {
        List<String> codes = new ArrayList<>();
        codes.add(CommandWords.display("auto", turkish));
        codes.addAll(Language.codes());
        return Commands.complete(typed, codes);
    }
}
