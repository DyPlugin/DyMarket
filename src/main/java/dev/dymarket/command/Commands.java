package dev.dymarket.command;

import dev.dymarket.DyMarket;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.economy.TransactionResult;
import dev.dymarket.lang.Language;
import dev.dymarket.storage.SqliteStore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Command registration and helpers shared by the command classes. */
public final class Commands {
    /** A player found by name or UUID; {@code name} may be null for players never seen by name. */
    public record Target(UUID id, String name) {
        public String display() {
            return name != null ? name : id.toString().substring(0, 8);
        }
    }

    private Commands() {
    }

    public static <T extends CommandExecutor & TabCompleter> void register(DyMarket plugin, T handler, String... names) {
        for (String name : names) {
            PluginCommand command = plugin.getCommand(name);
            if (command == null) continue;
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    public static boolean turkish(DyMarket plugin, CommandSender sender) {
        return plugin.messages().language(sender) == Language.TR;
    }

    public static List<String> complete(String typed, Collection<String> options) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(option);
        return result;
    }

    public static List<String> onlineNames(String typed) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
        return complete(typed, names);
    }

    public static Map<String, String> values(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    /** A page number from text, or {@code fallback} when it is missing or not a positive number. */
    public static int page(String text, int fallback) {
        try {
            int page = Integer.parseInt(text);
            return page > 0 ? page : fallback;
        } catch (NumberFormatException | NullPointerException notNumber) {
            return fallback;
        }
    }

    public static Player online(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getName().equalsIgnoreCase(name)) return player;
        return null;
    }

    /**
     * Finds a player by name or UUID: online players, cached accounts, DyMarket's player table, then the server's
     * own player list. Reads storage, so call it on the DyMarket-IO thread.
     */
    @SuppressWarnings("deprecation")
    public static Target resolve(DyMarket plugin, String text) {
        Player online = online(text);
        if (online != null) return new Target(online.getUniqueId(), online.getName());
        try {
            UUID id = UUID.fromString(text);
            String name = plugin.economy().cached(id) != null ? plugin.economy().cached(id).name() : Bukkit.getOfflinePlayer(id).getName();
            return new Target(id, name);
        } catch (IllegalArgumentException notUuid) {
            // A name.
        }
        UUID cached = plugin.economy().cachedByName(text);
        if (cached != null) return new Target(cached, plugin.economy().cached(cached) == null ? text : plugin.economy().cached(cached).name());
        try {
            SqliteStore.PlayerRow row = plugin.io().call(() -> plugin.store().playerByName(text));
            if (row != null) return new Target(row.id(), row.name());
        } catch (Exception failure) {
            plugin.getLogger().warning("Player lookup failed: " + failure.getMessage());
        }
        for (OfflinePlayer known : Bukkit.getOfflinePlayers())
            if (known.getName() != null && known.getName().equalsIgnoreCase(text)) return new Target(known.getUniqueId(), known.getName());
        return null;
    }

    /** Sends a message on the right thread for the receiver (the player's own thread on Folia). */
    public static void reply(DyMarket plugin, CommandSender sender, String key, Map<String, String> values) {
        if (sender instanceof Player player) {
            if (!player.isOnline()) return;
            plugin.scheduler().run(player, () -> plugin.messages().send(player, key, values));
        } else {
            plugin.messages().send(sender, key, values);
        }
    }

    public static void reply(DyMarket plugin, CommandSender sender, String key) {
        reply(plugin, sender, key, Map.of());
    }

    public static void replyLines(DyMarket plugin, CommandSender sender, List<String> lines) {
        if (sender instanceof Player player) plugin.scheduler().run(player, () -> lines.forEach(player::sendMessage));
        else lines.forEach(sender::sendMessage);
    }

    /** Placeholders describing an amount: {amount}, {currency} (singular or plural name), {currency_id}, {symbol}. */
    public static Map<String, String> money(Currency currency, long minor) {
        return values("{amount}", CurrencyFormat.format(currency, minor), "{currency}", currency.displayName(minor),
                "{currency_id}", currency.id(), "{symbol}", currency.symbol());
    }

    /** Tells the sender why an economy operation failed. */
    public static void failure(DyMarket plugin, CommandSender sender, TransactionResult result, Currency currency, Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(extra);
        if (currency != null) {
            values.putIfAbsent("{currency}", currency.plural());
            values.put("{balance}", CurrencyFormat.format(currency, result.balance()));
            values.put("{max}", CurrencyFormat.format(currency, currency.maxBalance()));
        }
        values.putIfAbsent("{currencies}", String.join(", ", plugin.currencies().ids()));
        String key = switch (result.status()) {
            case INSUFFICIENT_FUNDS -> "economy.insufficient-funds";
            case MAX_BALANCE -> "economy.max-balance";
            case NOT_TRANSFERABLE -> "economy.not-transferable";
            case CANCELLED -> "economy.cancelled";
            case UNKNOWN_CURRENCY -> "general.unknown-currency";
            case INVALID_AMOUNT -> "general.invalid-amount";
            case SAME_ACCOUNT -> "pay.self";
            case RATE_LIMITED -> "general.rate-limited";
            case ALREADY_APPLIED -> "admin.already-applied";
            case NOT_FOUND -> "admin.rollback-not-found";
            case ALREADY_REVERSED -> "admin.rollback-already";
            default -> "general.storage-error";
        };
        values.putIfAbsent("{amount}", "");
        reply(plugin, sender, key, values);
    }

    /** The currency named by the text, or null. */
    public static Currency currency(DyMarket plugin, String text) {
        return plugin.currencies().get(text);
    }
}
