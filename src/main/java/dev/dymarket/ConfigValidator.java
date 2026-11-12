package dev.dymarket;

import dev.dymarket.economy.Currency;
import dev.dymarket.lang.Language;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Validates a staged configuration before it replaces the running settings. */
final class ConfigValidator {
    static final Set<String> RIGHTS = Set.of("build", "containers", "doors", "trapdoors", "redstone", "entities", "teleport");

    private ConfigValidator() {
    }

    static void validate(FileConfiguration config) {
        String language = config.getString("language.default", "auto");
        if (!"auto".equals(language) && !Language.codes().contains(language))
            invalid("language.default", "expected auto or one of " + Language.codes());
        if (!"sqlite".equalsIgnoreCase(config.getString("storage.type", "sqlite"))) invalid("storage.type", "only sqlite is supported");
        String file = config.getString("storage.file", "data.db");
        if (file == null || file.isBlank() || file.contains("..")) invalid("storage.file", "expected a file name such as data.db");
        bounded(config, "storage.flush-interval-ticks", 1, 72000);

        Set<String> defined = currencies(config);
        number(config, "credits.exchange.rate", 0, false);
        number(config, "credits.exchange.min", 0, true);
        number(config, "credits.topup-bonus-percent", 0, true);
        if (config.getDouble("credits.topup-bonus-percent", 0) > 1000) invalid("credits.topup-bonus-percent", "expected 0-1000");
        number(config, "pay.min-amount", 0, true);
        number(config, "pay.confirm-above", 0, true);
        bounded(config, "security.rate-limit.pay-per-minute", 0, 100000);
        bounded(config, "security.rate-limit.ah-buy-per-minute", 0, 100000);
        number(config, "security.alert.transfer-above", 0, true);
        bounded(config, "baltop.page-size", 1, 100);
        bounded(config, "baltop.refresh-seconds", 5, 86400);

        moduleCurrencies(config, "auction", defined);
        bounded(config, "auction.duration-hours", 1, 8760);
        percent(config, "auction.tax-percent");
        prices(config, "auction.min-price", "auction.max-price");
        bounded(config, "auction.max-listings", 0, 100000);
        bounded(config, "auction.gui.rows", 2, 6);
        if (!Set.of("newest", "price-asc", "price-desc").contains(config.getString("auction.gui.sort", "newest")))
            invalid("auction.gui.sort", "expected newest, price-asc or price-desc");
        stringList(config, "auction.blacklist");
        for (String material : config.getStringList("auction.blacklist"))
            if (!material.trim().matches("[A-Za-z0-9_]+")) invalid("auction.blacklist", material + " is not a material name such as DIAMOND_SWORD");
        stringList(config, "auction.block-items-with-lore-containing");
        for (String text : config.getStringList("auction.block-items-with-lore-containing"))
            if (text.isBlank()) invalid("auction.block-items-with-lore-containing", "empty texts would block every item with lore");
        if (config.contains("auction.gui") && !config.isConfigurationSection("auction.gui")) invalid("auction.gui", "expected a section with rows and sort");

        moduleCurrencies(config, "estate", defined);
        bounded(config, "estate.max-chunks-per-listing", 1, 10000);
        percent(config, "estate.tax-percent");
        prices(config, "estate.sale.min-price", "estate.sale.max-price");
        bounded(config, "estate.rent.min-days", 1, 3650);
        bounded(config, "estate.rent.max-days", 1, 3650);
        if (config.getInt("estate.rent.min-days", 1) > config.getInt("estate.rent.max-days", 90))
            invalid("estate.rent.min-days", "must not be above estate.rent.max-days");
        stringList(config, "estate.rent.rights");
        for (String right : config.getStringList("estate.rent.rights"))
            if (!RIGHTS.contains(right)) invalid("estate.rent.rights", right + " is not a DyClaim right; expected some of " + RIGHTS);
        if (config.getBoolean("estate.rent.enabled", true) && config.contains("estate.rent.rights") && config.getStringList("estate.rent.rights").isEmpty())
            invalid("estate.rent.rights", "tenants would get no rights; list at least one or set estate.rent.enabled: false");
        for (String section : List.of("estate.sale", "estate.rent"))
            if (config.contains(section) && !config.isConfigurationSection(section)) invalid(section, "expected a section");
        bounded(config, "estate.rent.grace-hours", 0, 8760);
        bounded(config, "estate.rent.refund-on-owner-cancel-percent", 0, 100);
        bounded(config, "estate.rent.check-interval-minutes", 1, 1440);
        bounded(config, "estate.listing-expires-days", 0, 3650);

        for (String key : config.getKeys(true)) {
            if ((key.endsWith(".enabled") || key.endsWith(".check") || key.endsWith("-after") || key.endsWith(".transferable")
                    || key.endsWith(".vault") || key.endsWith(".discord") || key.endsWith("announce-topups")
                    || key.endsWith("confirm-purchase") || key.endsWith("broadcast-sales"))
                    && !config.isConfigurationSection(key) && !(config.get(key) instanceof Boolean))
                invalid(key, "expected true or false");
        }
    }

    /** Checks every currency and returns the ids defined (enabled or not). */
    private static Set<String> currencies(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("currencies");
        if (section == null || section.getKeys(false).isEmpty()) invalid("currencies", "define at least one currency");
        Set<String> ids = new java.util.HashSet<>();
        int vault = 0, enabled = 0;
        for (String id : section.getKeys(false)) {
            String path = "currencies." + id;
            ConfigurationSection currency = section.getConfigurationSection(id);
            if (currency == null) invalid(path, "expected a currency section");
            if (!id.matches("[a-z0-9_-]{1,32}")) invalid(path, "currency ids use a-z, 0-9, _ and - only");
            ids.add(id);
            bounded(config, path + ".decimals", 0, 8);
            if (Currency.parseLocale(currency.getString("format-locale", "en-US")) == null)
                invalid(path + ".format-locale", "expected a locale such as en-US or tr-TR");
            number(config, path + ".starting-balance", 0, true);
            number(config, path + ".max-balance", 0, false);
            int decimals = currency.getInt("decimals", 2);
            BigDecimal max = BigDecimal.valueOf(currency.getDouble("max-balance", 1_000_000_000d)).movePointRight(decimals);
            if (max.compareTo(BigDecimal.valueOf(Long.MAX_VALUE / 4)) > 0) invalid(path + ".max-balance", "too large for " + decimals + " decimals");
            if (currency.getDouble("starting-balance", 0) > currency.getDouble("max-balance", 1_000_000_000d))
                invalid(path + ".starting-balance", "must not be above max-balance");
            String provider = currency.getString("provider", "internal");
            if (!"internal".equals(provider) && !"vault".equals(provider)) invalid(path + ".provider", "expected internal or vault");
            if (!currency.getBoolean("enabled", true)) continue;
            enabled++;
            if (currency.getBoolean("vault", false)) vault++;
            else if ("vault".equals(provider)) invalid(path + ".provider", "only the Vault currency (vault: true) may use provider: vault");
        }
        if (enabled == 0) invalid("currencies", "enable at least one currency");
        if (vault != 1) invalid("currencies", "exactly one enabled currency must have vault: true (found " + vault + ")");
        return ids;
    }

    private static void moduleCurrencies(FileConfiguration config, String module, Set<String> defined) {
        if (!config.contains(module + ".currency")) return;
        String currency = config.getString(module + ".currency");
        if (!defined.contains(currency)) invalid(module + ".currency", currency + " is not defined under currencies");
        stringList(config, module + ".allowed-currencies");
        List<String> allowed = config.getStringList(module + ".allowed-currencies");
        for (String id : allowed) if (!defined.contains(id)) invalid(module + ".allowed-currencies", id + " is not defined under currencies");
        if (config.contains(module + ".allowed-currencies") && !allowed.contains(currency))
            invalid(module + ".allowed-currencies", "must contain " + module + ".currency (" + currency + ")");
    }

    private static void prices(FileConfiguration config, String min, String max) {
        number(config, min, 0, true);
        number(config, max, 0, false);
        if (config.getDouble(min, 0) > config.getDouble(max, Double.MAX_VALUE)) invalid(min, "must not be above " + max);
    }

    private static void percent(FileConfiguration config, String path) {
        number(config, path, 0, true);
        if (config.getDouble(path, 0) > 100) invalid(path, "expected 0-100");
    }

    /** A finite number above {@code min} (or equal to it when {@code inclusive}). */
    private static void number(FileConfiguration config, String path, double min, boolean inclusive) {
        if (!config.contains(path)) return;
        Object value = config.get(path);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || (inclusive ? number.doubleValue() < min : number.doubleValue() <= min))
            invalid(path, inclusive ? "expected a number of at least " + min : "expected a number above " + min);
    }

    private static void bounded(FileConfiguration config, String path, long min, long max) {
        if (!config.contains(path)) return;
        Object value = config.get(path);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.longValue() || number.longValue() < min || number.longValue() > max)
            invalid(path, "expected a whole number between " + min + " and " + max);
    }

    private static void stringList(FileConfiguration config, String path) {
        if (!config.contains(path)) return;
        if (!(config.get(path) instanceof List<?> values) || values.stream().anyMatch(item -> !(item instanceof String)))
            invalid(path, "expected a list of names");
    }

    private static void invalid(String path, String reason) {
        throw new IllegalArgumentException(path + ": " + reason);
    }
}
