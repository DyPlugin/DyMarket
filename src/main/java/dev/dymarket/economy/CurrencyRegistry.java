package dev.dymarket.economy;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The enabled currencies, in config order. Ids are matched case-insensitively (Locale.ROOT). */
public final class CurrencyRegistry {
    public static final String CREDITS = "credits";

    private final Map<String, Currency> currencies;
    private final Currency vault;

    public CurrencyRegistry(Collection<Currency> enabled) {
        Map<String, Currency> map = new LinkedHashMap<>();
        Currency vaultCurrency = null;
        for (Currency currency : enabled) {
            map.put(currency.id(), currency);
            if (currency.vault() && vaultCurrency == null) vaultCurrency = currency;
        }
        if (vaultCurrency == null && !map.isEmpty()) vaultCurrency = map.values().iterator().next();
        this.currencies = Map.copyOf(map);
        this.vault = vaultCurrency;
        order = List.copyOf(map.values());
    }

    private final List<Currency> order;

    public static CurrencyRegistry fromConfig(ConfigurationSection section) {
        List<Currency> list = new java.util.ArrayList<>();
        if (section != null) for (String id : section.getKeys(false)) {
            ConfigurationSection currency = section.getConfigurationSection(id);
            if (currency != null && currency.getBoolean("enabled", true)) list.add(Currency.fromConfig(id, currency));
        }
        return new CurrencyRegistry(list);
    }

    /** A currency by id, or null when unknown or disabled. */
    public Currency get(String id) {
        return id == null ? null : currencies.get(id.toLowerCase(Locale.ROOT));
    }

    /** The Vault currency: the default for every command that takes an optional currency. */
    public Currency primary() {
        return vault;
    }

    public Currency credits() {
        return currencies.get(CREDITS);
    }

    public List<Currency> all() {
        return order;
    }

    public List<String> ids() {
        return order.stream().map(Currency::id).toList();
    }
}
