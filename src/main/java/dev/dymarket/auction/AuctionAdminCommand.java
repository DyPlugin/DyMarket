package dev.dymarket.auction;

import dev.dymarket.DyMarket;
import dev.dymarket.command.AdminSubcommand;
import dev.dymarket.command.Commands;
import dev.dymarket.util.CommandWords;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * /dyma ah remove &lt;id&gt;, /dyma ah price &lt;id&gt; &lt;price&gt; and /dyma ah purge &lt;player&gt; (Turkish: sil, fiyat,
 * temizle). Removed items go to the seller's collection box; the seller is told about every change. The permission is checked by /dymarket.
 */
final class AuctionAdminCommand implements AdminSubcommand {
    private final DyMarket plugin;
    private final AuctionModule module;

    AuctionAdminCommand(DyMarket plugin, AuctionModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : CommandWords.canonical(args[0]);
        switch (sub) {
            case "remove" -> {
                if (args.length < 2) plugin.messages().send(sender, "auction.admin-remove-usage");
                else module.staffRemove(sender, args[1], null);
            }
            case "price" -> {
                if (args.length < 3) plugin.messages().send(sender, "auction.admin-price-usage");
                else module.staffPrice(sender, args[1], args[2], null);
            }
            case "purge" -> {
                if (args.length < 2) plugin.messages().send(sender, "auction.admin-purge-usage");
                else purge(sender, args[1]);
            }
            default -> plugin.messages().send(sender, "auction.admin-usage");
        }
    }

    private void purge(CommandSender sender, String name) {
        // Resolving an offline player reads storage, so it runs on DyMarket-IO.
        plugin.io().execute(() -> {
            Commands.Target target = Commands.resolve(plugin, name);
            if (target == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", name));
                return;
            }
            module.service().purge(target.id(), outcome -> Commands.reply(plugin, sender,
                    outcome.ok() ? "auction.purged" : "auction.purge-none",
                    Commands.values("{count}", String.valueOf(outcome.count()), "{player}", target.display())));
        });
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        boolean turkish = Commands.turkish(plugin, sender);
        if (args.length == 1) return Commands.complete(args[0], CommandWords.choices(turkish, "remove", "price", "purge"));
        if (args.length == 2) {
            String sub = CommandWords.canonical(args[0]);
            if (sub.equals("purge")) return Commands.onlineNames(args[1]);
            if (sub.equals("remove") || sub.equals("price")) {
                List<String> ids = new ArrayList<>();
                for (Listing listing : module.service().active()) ids.add(listing.id());
                List<String> matches = Commands.complete(args[1], ids);
                return matches.size() > 30 ? matches.subList(0, 30) : matches;
            }
        }
        if (args.length == 3 && CommandWords.canonical(args[0]).equals("price")) return Commands.amountHints(args[2]);
        return List.of();
    }
}
