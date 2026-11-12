package dev.dymarket.command;

import dev.dymarket.DyMarket;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.util.CommandWords;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** /credits, /credits send &lt;player&gt; &lt;amount&gt;, /credits exchange &lt;amount&gt;. */
public final class CreditsCommand implements CommandExecutor, TabCompleter {
    private final DyMarket plugin;

    public CreditsCommand(DyMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Currency credits = plugin.credits().credits();
        if (credits == null) {
            plugin.messages().send(sender, "credits.disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return true;
        }
        if (!sender.hasPermission("dymarket.credits")) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : CommandWords.canonical(args[0]);
        switch (sub) {
            case "" -> show(player, credits);
            case "send" -> send(player, credits, args);
            case "exchange" -> exchange(player, credits, args);
            case "help" -> plugin.messages().getList(sender, "help.credits").forEach(sender::sendMessage);
            default -> plugin.messages().send(sender, "credits.usage");
        }
        return true;
    }

    private void show(Player player, Currency credits) {
        plugin.io().execute(() -> {
            long balance = plugin.economy().balance(player.getUniqueId(), credits);
            Commands.reply(plugin, player, "credits.balance", Commands.values("{balance}", CurrencyFormat.format(credits, balance)));
        });
    }

    private void send(Player player, Currency credits, String[] args) {
        if (!player.hasPermission("dymarket.credits.send")) {
            plugin.messages().send(player, "general.no-permission");
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(player, "credits.send-usage");
            return;
        }
        if (!credits.transferable()) {
            plugin.messages().send(player, "economy.not-transferable", Map.of("{currency}", credits.plural()));
            return;
        }
        OptionalLong amount = CurrencyFormat.parse(credits, args[2]);
        if (amount.isEmpty() || amount.getAsLong() <= 0) {
            plugin.messages().send(player, "general.invalid-amount", Map.of("{amount}", args[2]));
            return;
        }
        if (!player.hasPermission("dymarket.bypass.ratelimit")
                && !plugin.rateLimiter().tryAcquire(player.getUniqueId(), "pay", plugin.getConfig().getInt("security.rate-limit.pay-per-minute", 10))) {
            plugin.messages().send(player, "general.rate-limited");
            return;
        }
        String name = args[1];
        plugin.io().execute(() -> {
            Commands.Target target = Commands.resolve(plugin, name);
            if (target == null) {
                Commands.reply(plugin, player, "general.player-not-found", Commands.values("{player}", name));
                return;
            }
            if (target.id().equals(player.getUniqueId())) {
                Commands.reply(plugin, player, "pay.self");
                return;
            }
            TransactionResult result = plugin.credits().send(player.getUniqueId(), target.id(), amount.getAsLong());
            Map<String, String> values = Commands.money(credits, amount.getAsLong());
            values.put("{player}", target.display());
            if (!result.success()) {
                Commands.failure(plugin, player, result, credits, values);
                return;
            }
            Commands.reply(plugin, player, "credits.sent", values);
            Player receiver = Bukkit.getPlayer(target.id());
            if (receiver != null) {
                Map<String, String> received = Commands.money(credits, amount.getAsLong());
                received.put("{player}", player.getName());
                Commands.reply(plugin, receiver, "credits.received", received);
            }
        });
    }

    private void exchange(Player player, Currency credits, String[] args) {
        if (!player.hasPermission("dymarket.credits.exchange")) {
            plugin.messages().send(player, "general.no-permission");
            return;
        }
        Currency money = plugin.currencies().primary();
        if (!plugin.getConfig().getBoolean("credits.exchange.enabled", true) || money == null || money.id().equals(credits.id())) {
            plugin.messages().send(player, "credits.exchange-disabled");
            return;
        }
        String one = CurrencyFormat.format(credits, credits.toMinor(java.math.BigDecimal.ONE));
        String rate = CurrencyFormat.format(money, plugin.credits().exchangeValue(credits.toMinor(java.math.BigDecimal.ONE)));
        if (args.length < 2) {
            plugin.messages().send(player, "credits.exchange-usage", Commands.values("{credit}", one, "{money}", rate));
            return;
        }
        OptionalLong amount = CurrencyFormat.parse(credits, args[1]);
        if (amount.isEmpty() || amount.getAsLong() <= 0) {
            plugin.messages().send(player, "general.invalid-amount", Map.of("{amount}", args[1]));
            return;
        }
        if (amount.getAsLong() < plugin.credits().minimumExchange()) {
            plugin.messages().send(player, "credits.exchange-min", Map.of("{min}", CurrencyFormat.format(credits, plugin.credits().minimumExchange())));
            return;
        }
        plugin.io().execute(() -> {
            TransactionResult result = plugin.credits().exchange(player.getUniqueId(), amount.getAsLong());
            if (!result.success()) {
                // Insufficient credits is reported in credits; a full money balance in money.
                Currency failed = result.status() == TransactionResult.Status.MAX_BALANCE ? money : credits;
                Commands.failure(plugin, player, result, failed, Commands.money(credits, amount.getAsLong()));
                return;
            }
            Commands.reply(plugin, player, "credits.exchanged", Commands.values("{credits}", CurrencyFormat.format(credits, amount.getAsLong()),
                    "{money}", CurrencyFormat.format(money, plugin.credits().exchangeValue(amount.getAsLong())),
                    "{balance}", CurrencyFormat.format(money, result.balance())));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean turkish = Commands.turkish(plugin, sender);
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("dymarket.credits.send")) options.add(CommandWords.display("send", turkish));
            if (sender.hasPermission("dymarket.credits.exchange")) options.add(CommandWords.display("exchange", turkish));
            return Commands.complete(args[0], options);
        }
        if (args.length == 2 && CommandWords.is(args[0], "send")) return Commands.onlineNames(args[1]);
        return List.of();
    }
}
