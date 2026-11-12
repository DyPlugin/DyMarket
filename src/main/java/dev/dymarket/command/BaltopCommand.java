package dev.dymarket.command;

import dev.dymarket.DyMarket;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.storage.SqliteStore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /baltop [currency] [page]. */
public final class BaltopCommand implements CommandExecutor, TabCompleter {
    private final DyMarket plugin;

    public BaltopCommand(DyMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dymarket.baltop")) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        Currency currency = plugin.currencies().primary();
        int index = 0;
        if (args.length > 0 && plugin.currencies().get(args[0]) != null) {
            currency = plugin.currencies().get(args[0]);
            index = 1;
        } else if (args.length > 0 && Commands.page(args[0], -1) < 0) {
            plugin.messages().send(sender, "general.unknown-currency", Commands.values("{currency}", args[0],
                    "{currencies}", String.join(", ", plugin.currencies().ids())));
            return true;
        }
        int page = args.length > index ? Commands.page(args[index], 1) : 1;
        int size = Math.max(1, plugin.getConfig().getInt("baltop.page-size", 10));
        Currency shown = currency;
        plugin.io().execute(() -> {
            List<SqliteStore.TopEntry> entries = plugin.baltop().current(shown.id());
            int pages = Math.max(1, (entries.size() + size - 1) / size);
            if (entries.isEmpty()) {
                Commands.reply(plugin, sender, "baltop.empty", Commands.values("{currency}", shown.plural()));
                return;
            }
            if (page > pages) {
                Commands.reply(plugin, sender, "general.invalid-page", Commands.values("{pages}", String.valueOf(pages)));
                return;
            }
            List<String> lines = new ArrayList<>();
            lines.add(plugin.messages().get(sender, "baltop.header", Commands.values("{currency}", shown.plural(),
                    "{page}", String.valueOf(page), "{pages}", String.valueOf(pages))));
            for (int i = (page - 1) * size; i < Math.min(entries.size(), page * size); i++) {
                SqliteStore.TopEntry entry = entries.get(i);
                String name = entry.name() == null ? entry.id().toString().substring(0, 8) : entry.name();
                lines.add(plugin.messages().get(sender, "baltop.line", Commands.values("{rank}", String.valueOf(i + 1),
                        "{player}", name, "{balance}", CurrencyFormat.format(shown, entry.balance()))));
            }
            if (sender instanceof Player player) {
                long own = plugin.economy().cachedBalance(player.getUniqueId(), shown);
                if (own >= 0) lines.add(plugin.messages().get(sender, "baltop.own", Commands.values("{balance}", CurrencyFormat.format(shown, own))));
            }
            if (page < pages) lines.add(plugin.messages().get(sender, "baltop.next", Commands.values("{command}",
                    "/" + label + " " + shown.id() + " " + (page + 1))));
            Commands.replyLines(plugin, sender, lines);
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Commands.complete(args[0], plugin.currencies().ids());
        return List.of();
    }
}
