package dev.dymarket.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Formats and parses amounts with a currency's locale: "$1,250.50" under en-US, "1.250,50 C" under tr-TR.
 * Parsing accepts the locale's separators, plain "1250.5" and the suffixes k, m and b ("2.5m").
 */
public final class CurrencyFormat {
    private CurrencyFormat() {
    }

    public static String format(Currency currency, long minor) {
        String number = number(currency, minor < 0 ? -minor : minor);
        String sign = minor < 0 ? "-" : "";
        if (currency.symbol().isEmpty()) return sign + number;
        return currency.symbolAfter() ? sign + number + " " + currency.symbol() : sign + currency.symbol() + number;
    }

    /** The number alone with grouping, for example "1,250.50". */
    public static String number(Currency currency, long minor) {
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(currency.locale()));
        format.setMinimumFractionDigits(currency.decimals());
        format.setMaximumFractionDigits(currency.decimals());
        format.setRoundingMode(RoundingMode.DOWN);
        return format.format(currency.toMajor(minor));
    }

    /** The plain amount without grouping or symbol, for placeholders and logs: "1250.50". */
    public static String plain(Currency currency, long minor) {
        return currency.toMajor(minor).toPlainString();
    }

    /** Minor units for typed text, or empty when it is not a valid non-negative amount for this currency. */
    public static OptionalLong parse(Currency currency, String text) {
        if (text == null) return OptionalLong.empty();
        String value = text.strip().replace(" ", "").replace(" ", "").replace(" ", "").replace("_", "");
        if (!currency.symbol().isEmpty()) value = value.replace(currency.symbol(), "");
        if (value.isEmpty()) return OptionalLong.empty();
        BigDecimal multiplier = BigDecimal.ONE;
        char last = Character.toLowerCase(value.charAt(value.length() - 1));
        if (last == 'k' || last == 'm' || last == 'b') {
            multiplier = BigDecimal.TEN.pow(last == 'k' ? 3 : last == 'm' ? 6 : 9);
            value = value.substring(0, value.length() - 1);
        }
        String normalized = normalize(value, DecimalFormatSymbols.getInstance(currency.locale()));
        if (normalized == null || !normalized.matches("\\d+(\\.\\d+)?|\\.\\d+")) return OptionalLong.empty();
        try {
            BigDecimal major = new BigDecimal(normalized).multiply(multiplier);
            // More decimals than the currency has is a typo, not something to round away silently.
            BigDecimal scaled = major.setScale(currency.decimals(), RoundingMode.UNNECESSARY);
            return OptionalLong.of(scaled.movePointRight(currency.decimals()).longValueExact());
        } catch (ArithmeticException | NumberFormatException invalid) {
            return OptionalLong.empty();
        }
    }

    /**
     * Turns locale-formatted text into "1234.56". With both separators present the grouping one is dropped.
     * With only one kind, it is the decimal separator unless every group after it has exactly three digits
     * (so "1.250" is 1250 under tr-TR while "2.5" stays 2.5 there).
     */
    static String normalize(String value, DecimalFormatSymbols symbols) {
        char decimal = symbols.getDecimalSeparator();
        char grouping = symbols.getGroupingSeparator();
        if (Character.isSpaceChar(grouping)) grouping = decimal == ',' ? '.' : ',';
        boolean hasDecimal = value.indexOf(decimal) >= 0;
        boolean hasGrouping = value.indexOf(grouping) >= 0;
        if (hasDecimal && hasGrouping) {
            if (value.lastIndexOf(grouping) > value.indexOf(decimal)) return null;
            return value.replace(String.valueOf(grouping), "").replace(decimal, '.');
        }
        char only = hasDecimal ? decimal : hasGrouping ? grouping : 0;
        if (only == 0) return value;
        String[] parts = value.split(java.util.regex.Pattern.quote(String.valueOf(only)), -1);
        boolean groups = parts.length > 1 && !parts[0].isEmpty() && parts[0].length() <= 3;
        for (int i = 1; i < parts.length && groups; i++) groups = parts[i].length() == 3;
        if (groups && (only == grouping || parts.length > 2)) return value.replace(String.valueOf(only), "");
        if (parts.length > 2) return null;
        return value.replace(only, '.');
    }

    /** Locale-independent lower case for ids typed by players. */
    public static String fold(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
