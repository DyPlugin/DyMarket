package dev.dymarket.estate;

import dev.dymarket.DyMarket;
import dev.dymarket.command.AdminSubcommand;
import dev.dymarket.command.Commands;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.util.CommandWords;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /dymarket estate unlist &lt;id&gt; (Turkish: kaldır). A leased listing ends its lease and the tenant gets the
 * unused time back from the owner (as much as the owner has). The permission is checked by /dymarket.
 */
final class EstateAdminCommand implements AdminSubcommand {
    private final DyMarket plugin;
    private final EstateModule module;

    EstateAdminCommand(DyMarket plugin, EstateModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2 || !CommandWords.is(args[0], "unlist")) {
            plugin.messages().send(sender, "estate.admin-usage");
            return;
        }
        String id = args[1];
        EstateListing listing = module.service().get(id);
        if (listing == null) {
            plugin.messages().send(sender, "estate.not-found", Commands.values("{id}", id));
            return;
        }
        // An admin who owns the listing unlists it like an owner; anyone else removes it.
        module.service().unlist(listing.id(), sender instanceof Player player ? player.getUniqueId() : null, true, outcome -> {
            if (!outcome.ok()) {
                module.failure(sender, outcome, Commands.values("{id}", id));
                return;
            }
            Currency currency = plugin.currencies().get(listing.currency());
            Map<String, String> values = module.service().values(listing, currency);
            if (outcome.lease() != null) {
                values.put("{tenant}", outcome.lease().tenantName());
                values.put("{refund}", currency == null ? String.valueOf(outcome.amount()) : CurrencyFormat.format(currency, outcome.amount()));
                Commands.reply(plugin, sender, "estate.unlisted-refund", values);
            } else {
                Commands.reply(plugin, sender, "estate.removed", values);
            }
        });
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        boolean turkish = Commands.turkish(plugin, sender);
        if (args.length == 1) return Commands.complete(args[0], CommandWords.choices(turkish, "unlist"));
        if (args.length == 2 && CommandWords.is(args[0], "unlist")) {
            List<String> ids = new ArrayList<>();
            for (EstateListing listing : module.service().active()) ids.add(listing.id());
            for (Lease lease : module.service().allLeases()) ids.add(lease.listingId());
            List<String> matches = Commands.complete(args[1], ids);
            return matches.size() > 30 ? matches.subList(0, 30) : matches;
        }
        return List.of();
    }
}
