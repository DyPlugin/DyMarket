package dev.dymarket.command;

import dev.dymarket.DyMarket;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /balance [player] [currency]. Without a currency every enabled currency is shown. */
public final class BalanceCommand implements CommandExecutor, TabCompleter {
    private final DyMarket plugin;

    public BalanceCommand(DyMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dymarket.balance")) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        String playerName = null;
        Currency currency = null;
        if (args.length >= 1) {
            Currency first = plugin.currencies().get(args[0]);
            if (args.length == 1 && first != null && Commands.online(args[0]) == null) currency = first;
            else playerName = args[0];
        }
        if (args.length >= 2) {
            currency = plugin.currencies().get(args[1]);
            if (currency == null) {
                plugin.messages().send(sender, "general.unknown-currency", Commands.values("{currency}", args[1],
                        "{currencies}", String.join(", ", plugin.currencies().ids())));
                return true;
            }
        }
        if (playerName == null && !(sender instanceof Player)) {
            plugin.messages().send(sender, "balance.usage");
            return true;
        }
        if (playerName != null && !sender.hasPermission("dymarket.balance.others")
                && !(sender instanceof Player self && self.getName().equalsIgnoreCase(playerName))) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        Currency only = currency;
        String name = playerName;
        plugin.io().execute(() -> {
            Commands.Target target = name == null ? new Commands.Target(((Player) sender).getUniqueId(), sender.getName())
                    : Commands.resolve(plugin, name);
            if (target == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", name));
                return;
            }
            boolean self = sender instanceof Player player && player.getUniqueId().equals(target.id());
            List<Currency> shown = only != null ? List.of(only) : plugin.currencies().all();
            try {
                if (shown.size() == 1) {
                    Currency single = shown.get(0);
                    long balance = plugin.economy().balance(target.id(), single);
                    var values = Commands.money(single, balance);
                    values.put("{balance}", CurrencyFormat.format(single, balance));
                    values.put("{currency}", single.plural());
                    values.put("{player}", target.display());
                    Commands.reply(plugin, sender, self ? "balance.self" : "balance.other", values);
                    return;
                }
                List<String> lines = new ArrayList<>();
                lines.add(plugin.messages().get(sender, self ? "balance.header-self" : "balance.header-other",
                        Commands.values("{player}", target.display())));
                for (Currency each : shown) {
                    long balance = plugin.economy().balance(target.id(), each);
                    lines.add(plugin.messages().get(sender, "balance.line", Commands.values("{currency}", each.plural(),
                            "{balance}", CurrencyFormat.format(each, balance))));
                }
                Commands.replyLines(plugin, sender, lines);
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Balance lookup failed: " + failure.getMessage());
                Commands.reply(plugin, sender, "general.storage-error");
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(plugin.currencies().ids());
            if (sender.hasPermission("dymarket.balance.others")) return Commands.complete(args[0], concat(options, Commands.onlineNames("")));
            return Commands.complete(args[0], options);
        }
        if (args.length == 2 && sender.hasPermission("dymarket.balance.others")) return Commands.complete(args[1], plugin.currencies().ids());
        return List.of();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
