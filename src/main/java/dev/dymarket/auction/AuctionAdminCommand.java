package dev.dymarket.auction;

import dev.dymarket.DyMarket;
import dev.dymarket.command.AdminSubcommand;
import dev.dymarket.command.Commands;
import dev.dymarket.util.CommandWords;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * /dymarket ah remove &lt;id&gt; and /dymarket ah purge &lt;player&gt; (Turkish: sil, temizle). The items go to the
 * seller's collection box and the seller is told. The permission is checked by /dymarket.
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
        if (args.length < 2 || !(sub.equals("remove") || sub.equals("purge"))) {
            plugin.messages().send(sender, "auction.admin-usage");
            return;
        }
        if (sub.equals("remove")) {
            String id = args[1];
            module.service().remove(id, outcome -> {
                if (outcome.ok()) Commands.reply(plugin, sender, "auction.removed", Commands.values("{id}", outcome.listing().id(),
                        "{item}", AuctionService.label(outcome.listing()), "{player}", outcome.listing().sellerName()));
                else module.failure(sender, outcome, Commands.values("{id}", id));
            });
            return;
        }
        // Resolving an offline player reads storage, so it runs on DyMarket-IO.
        plugin.io().execute(() -> {
            Commands.Target target = Commands.resolve(plugin, args[1]);
            if (target == null) {
                Commands.reply(plugin, sender, "general.player-not-found", Commands.values("{player}", args[1]));
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
        if (args.length == 1) return Commands.complete(args[0], CommandWords.choices(turkish, "remove", "purge"));
        if (args.length == 2) {
            String sub = CommandWords.canonical(args[0]);
            if (sub.equals("purge")) return Commands.onlineNames(args[1]);
            if (sub.equals("remove")) {
                List<String> ids = new ArrayList<>();
                for (Listing listing : module.service().active()) ids.add(listing.id());
                List<String> matches = Commands.complete(args[1], ids);
                return matches.size() > 30 ? matches.subList(0, 30) : matches;
            }
        }
        return List.of();
    }
}
