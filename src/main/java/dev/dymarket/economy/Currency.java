package dev.dymarket.economy;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * One currency from config.yml. Amounts are whole numbers of minor units (cents for two decimals),
 * so sums never lose precision.
 */
public record Currency(String id, String name, String plural, String symbol, boolean symbolAfter, int decimals,
                       Locale locale, long startingBalance, long maxBalance, boolean transferable, boolean vault,
                       String provider) {

    private static final java.util.Set<String> ISO_LANGUAGES = java.util.Set.of(Locale.getISOLanguages());

    public static Currency fromConfig(String id, ConfigurationSection section) {
        int decimals = section.getInt("decimals", 2);
        Locale parsed = parseLocale(section.getString("format-locale", "en-US"));
        Locale locale = parsed == null ? Locale.US : parsed;
        String provider = section.getString("provider", "internal").toLowerCase(Locale.ROOT);
        Currency draft = new Currency(id.toLowerCase(Locale.ROOT), section.getString("name", id), section.getString("plural", section.getString("name", id)),
                section.getString("symbol", ""), section.getBoolean("symbol-after", false), decimals, locale, 0, Long.MAX_VALUE,
                section.getBoolean("transferable", true), section.getBoolean("vault", false), provider);
        return new Currency(draft.id, draft.name, draft.plural, draft.symbol, draft.symbolAfter, decimals, locale,
                draft.toMinor(section.getDouble("starting-balance", 0)), draft.toMinor(section.getDouble("max-balance", 1_000_000_000d)),
                draft.transferable, draft.vault, provider);
    }

    /** "en-US", "en_US" or "tr" style tags; null when the tag names no language. */
    public static Locale parseLocale(String tag) {
        if (tag == null || tag.isBlank()) return null;
        Locale locale = Locale.forLanguageTag(tag.trim().replace('_', '-'));
        return ISO_LANGUAGES.contains(locale.getLanguage()) ? locale : null;
    }

    public boolean external() {
        return "vault".equals(provider);
    }

    /** Major units (1.5) to minor units (150 for two decimals), rounded half up. */
    public long toMinor(double major) {
        return toMinor(BigDecimal.valueOf(major));
    }

    public long toMinor(BigDecimal major) {
        return major.setScale(decimals, RoundingMode.HALF_UP).movePointRight(decimals).longValueExact();
    }

    public BigDecimal toMajor(long minor) {
        return BigDecimal.valueOf(minor, decimals);
    }

    public double toDouble(long minor) {
        return toMajor(minor).doubleValue();
    }

    public String displayName(long minor) {
        return minor == toMinor(BigDecimal.ONE) ? name : plural;
    }
}
