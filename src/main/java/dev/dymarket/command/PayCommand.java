package dev.dymarket.command;

import dev.dymarket.DyMarket;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.economy.TransactionKind;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.util.CommandWords;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** /pay &lt;player&gt; &lt;amount&gt; [currency] [confirm]. */
public final class PayCommand implements CommandExecutor, TabCompleter {
    private final DyMarket plugin;

    public PayCommand(DyMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.getConfig().getBoolean("pay.enabled", true)) {
            plugin.messages().send(sender, "general.feature-disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return true;
        }
        if (!sender.hasPermission("dymarket.pay")) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "pay.usage");
            return true;
        }
        boolean confirmed = CommandWords.is(args[args.length - 1], "confirm");
        int extra = args.length - (confirmed ? 1 : 0);
        Currency currency = extra >= 3 ? plugin.currencies().get(args[2]) : plugin.currencies().primary();
        if (currency == null) {
            plugin.messages().send(sender, "general.unknown-currency", Commands.values("{currency}", args[2],
                    "{currencies}", String.join(", ", plugin.currencies().ids())));
            return true;
        }
        OptionalLong parsed = CurrencyFormat.parse(currency, args[1]);
        if (parsed.isEmpty() || parsed.getAsLong() <= 0) {
            plugin.messages().send(sender, "general.invalid-amount", Map.of("{amount}", args[1]));
            return true;
        }
        long amount = parsed.getAsLong();
        if (!currency.transferable()) {
            plugin.messages().send(sender, "economy.not-transferable", Map.of("{currency}", currency.plural()));
            return true;
        }
        long minimum = currency.toMinor(plugin.getConfig().getDouble("pay.min-amount", 0.01));
        if (amount < minimum) {
            plugin.messages().send(sender, "pay.below-min", Map.of("{min}", CurrencyFormat.format(currency, minimum)));
            return true;
        }
        double confirmAbove = plugin.getConfig().getDouble("pay.confirm-above", 0);
        if (confirmAbove > 0 && !confirmed && amount >= currency.toMinor(confirmAbove)) {
            boolean turkish = Commands.turkish(plugin, sender);
            String again = "/" + label + " " + args[0] + " " + args[1] + " " + currency.id() + " " + CommandWords.display("confirm", turkish);
            Map<String, String> values = Commands.money(currency, amount);
            values.put("{player}", args[0]);
            values.put("{command}", again);
            plugin.messages().send(sender, "pay.confirm", values);
            return true;
        }
        if (!sender.hasPermission("dymarket.bypass.ratelimit")
                && !plugin.rateLimiter().tryAcquire(player.getUniqueId(), "pay", plugin.getConfig().getInt("security.rate-limit.pay-per-minute", 10))) {
            plugin.messages().send(sender, "general.rate-limited");
            return true;
        }
        String targetName = args[0];
        plugin.io().execute(() -> {
            Commands.Target target = Commands.resolve(plugin, targetName);
            if (target == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", targetName));
                return;
            }
            if (target.id().equals(player.getUniqueId())) {
                Commands.reply(plugin, sender, "pay.self");
                return;
            }
            TransactionResult result = plugin.economy().transfer(player.getUniqueId(), target.id(), currency, amount, TransactionKind.PAY, null);
            Map<String, String> values = Commands.money(currency, amount);
            values.put("{player}", target.display());
            if (!result.success()) {
                Commands.failure(plugin, sender, result, currency, values);
                return;
            }
            values.put("{balance}", CurrencyFormat.format(currency, result.balance()));
            Commands.reply(plugin, sender, "pay.sent", values);
            Player receiver = Bukkit.getPlayer(target.id());
            if (receiver != null) {
                Map<String, String> received = Commands.money(currency, amount);
                received.put("{player}", player.getName());
                Commands.reply(plugin, receiver, "pay.received", received);
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Commands.onlineNames(args[0]);
        if (args.length == 3) return Commands.complete(args[2], plugin.currencies().all().stream().filter(Currency::transferable).map(Currency::id).toList());
        return List.of();
    }
}
