package dev.dymarket.economy;

import dev.dymarket.DyMarket;
import dev.dymarket.hook.DyDiscordHook;
import dev.dymarket.lang.Language;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reports large transfers between players (and large Vault payments from other plugins) to staff with the
 * notify permission, the console and, when DyDiscord offers a staff-alert API, Discord.
 */
public final class AlertService {
    /** Kinds where money moves because of players; admin actions are not alerted. */
    static final Set<TransactionKind> WATCHED = Set.of(TransactionKind.PAY, TransactionKind.VAULT, TransactionKind.AH_SALE,
            TransactionKind.ESTATE_SALE, TransactionKind.ESTATE_RENT);

    private final DyMarket plugin;
    private final DyDiscordHook discord;

    public AlertService(DyMarket plugin, DyDiscordHook discord) {
        this.plugin = plugin;
        this.discord = discord;
    }

    public void check(Transaction transaction) {
        if (!WATCHED.contains(transaction.kind()) || !plugin.getConfig().getBoolean("security.alert.enabled", true)) return;
        Currency currency = plugin.currencies().get(transaction.currency());
        if (currency == null) return;
        long threshold = currency.toMinor(plugin.getConfig().getDouble("security.alert.transfer-above", 100000));
        if (threshold <= 0 || transaction.amount() < threshold) return;
        Map<String, String> values = Map.of("{from}", name(transaction.from()), "{to}", name(transaction.to()),
                "{amount}", CurrencyFormat.format(currency, transaction.amount()), "{kind}", transaction.kind().name(),
                "{id}", transaction.id());
        String permission = plugin.getConfig().getString("security.alert.notify-permission", "dymarket.admin.alerts");
        plugin.scheduler().runGlobal(() -> {
            plugin.messages().send(Bukkit.getConsoleSender(), "alerts.transfer", values);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(permission)) plugin.scheduler().run(player, () -> plugin.messages().send(player, "alerts.transfer", values));
            }
        });
        if (plugin.getConfig().getBoolean("security.alert.discord", true))
            discord.staffAlert(plugin.messages().plain(Language.EN, "alerts.transfer", values));
    }

    private String name(UUID id) {
        if (id == null) return "-";
        Account account = plugin.economy().cached(id);
        if (account != null && account.name() != null) return account.name();
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }
}
