package dev.dymarket.hook;

import dev.dymarket.DyMarket;
import dev.dymarket.economy.Currency;
import dev.dymarket.economy.CurrencyFormat;
import dev.dymarket.storage.SqliteStore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Built-in %dymarket_...% placeholders, answered from memory only (balances of loaded accounts, the cached
 * baltop). Only loaded when PlaceholderAPI is installed, through register().
 */
public final class PlaceholderHook extends PlaceholderExpansion {
    private final DyMarket plugin;

    public PlaceholderHook(DyMarket plugin) {
        this.plugin = plugin;
    }

    /** Returns the registered expansion, or null when registration failed. */
    public static Object register(DyMarket plugin) {
        PlaceholderHook hook = new PlaceholderHook(plugin);
        return hook.register() ? hook : null;
    }

    public static void unregister(Object hook) {
        if (hook instanceof PlaceholderHook expansion) expansion.unregister();
    }

    @Override public String getIdentifier() { return "dymarket"; }
    @Override public String getAuthor() { return "Claww"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        Function<OfflinePlayer, String> module = plugin.modulePlaceholder(key);
        if (module != null) return module.apply(player);
        if (key.equals("ah_listings") || key.equals("ah_mine") || key.equals("estate_listings")) return "0";
        if (key.startsWith("baltop_")) return baltop(key.substring("baltop_".length()));
        if (key.equals("credits")) return balance(player, plugin.currencies().credits(), true);
        if (!key.startsWith("balance")) return null;
        String rest = key.substring("balance".length());
        boolean formatted = rest.endsWith("_formatted");
        if (formatted) rest = rest.substring(0, rest.length() - "_formatted".length());
        Currency currency = rest.isEmpty() ? plugin.currencies().primary() : rest.startsWith("_") ? plugin.currencies().get(rest.substring(1)) : null;
        if (currency == null) return null;
        return balance(player, currency, formatted);
    }

    private String balance(OfflinePlayer player, Currency currency, boolean formatted) {
        if (player == null || currency == null) return "";
        long balance = plugin.economy().cachedBalance(player.getUniqueId(), currency);
        if (balance < 0) return "";
        return formatted ? CurrencyFormat.format(currency, balance) : CurrencyFormat.plain(currency, balance);
    }

    /** "3_name", "3_amount", "credits_3_name". */
    private String baltop(String rest) {
        String[] parts = rest.split("_");
        if (parts.length < 2) return null;
        Currency currency = parts.length >= 3 ? plugin.currencies().get(String.join("_", java.util.Arrays.copyOf(parts, parts.length - 2)))
                : plugin.currencies().primary();
        if (currency == null) return null;
        int rank;
        try {
            rank = Integer.parseInt(parts[parts.length - 2]);
        } catch (NumberFormatException notNumber) {
            return null;
        }
        List<SqliteStore.TopEntry> entries = plugin.baltop().cached(currency.id());
        if (rank < 1 || rank > entries.size()) return "";
        SqliteStore.TopEntry entry = entries.get(rank - 1);
        return switch (parts[parts.length - 1]) {
            case "name" -> entry.name() == null ? "" : entry.name();
            case "amount" -> CurrencyFormat.format(currency, entry.balance());
            default -> null;
        };
    }
}
