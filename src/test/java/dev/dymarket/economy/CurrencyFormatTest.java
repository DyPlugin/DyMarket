package dev.dymarket.economy;

import dev.dymarket.util.CommandWords;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class CurrencyFormatTest {
    static final Currency US = new Currency("money", "Money", "Money", "$", false, 2, Locale.forLanguageTag("en-US"), 0, Long.MAX_VALUE / 4, true, true, "internal");
    static final Currency TR = new Currency("lira", "Lira", "Lira", "₺", true, 2, Locale.forLanguageTag("tr-TR"), 0, Long.MAX_VALUE / 4, true, false, "internal");
    static final Currency WHOLE = new Currency("credits", "Credit", "Credits", "C", true, 0, Locale.US, 0, Long.MAX_VALUE / 4, true, false, "internal");

    @Test void formatsWithTheCurrencyLocale() {
        assertEquals("$1,250.50", CurrencyFormat.format(US, 125_050));
        assertEquals("1.250,50 ₺", CurrencyFormat.format(TR, 125_050));
        assertEquals("1,250 C", CurrencyFormat.format(WHOLE, 1_250));
        assertEquals("-$0.05", CurrencyFormat.format(US, -5));
        assertEquals("1250.50", CurrencyFormat.plain(US, 125_050));
    }

    @Test void parsesLocaleSeparators() {
        assertEquals(OptionalLong.of(125_050), CurrencyFormat.parse(US, "1,250.50"));
        assertEquals(OptionalLong.of(125_050), CurrencyFormat.parse(TR, "1.250,50"));
        assertEquals(OptionalLong.of(125_000), CurrencyFormat.parse(TR, "1.250"), "grouping in tr-TR");
        assertEquals(OptionalLong.of(125), CurrencyFormat.parse(US, "1.25"));
        assertEquals(OptionalLong.of(250), CurrencyFormat.parse(TR, "2,5"));
        assertEquals(OptionalLong.of(250), CurrencyFormat.parse(TR, "2.5"), "a dot that cannot be grouping is a decimal");
        assertEquals(OptionalLong.of(125_050), CurrencyFormat.parse(US, "$1250.5"));
    }

    @Test void parsesSuffixes() {
        assertEquals(OptionalLong.of(100_000), CurrencyFormat.parse(US, "1k"));
        assertEquals(OptionalLong.of(250_000_000), CurrencyFormat.parse(US, "2.5m"));
        assertEquals(OptionalLong.of(250_000_000), CurrencyFormat.parse(TR, "2,5M"));
        assertEquals(OptionalLong.of(3_000_000_000L), CurrencyFormat.parse(WHOLE, "3b"));
    }

    @Test void rejectsInvalidText() {
        for (String bad : new String[]{"", "abc", "-5", "1.2.3,4,5", "1e5", "0.001", "1,2,3", "NaN", "k"})
            assertTrue(CurrencyFormat.parse(US, bad).isEmpty(), bad);
        assertTrue(CurrencyFormat.parse(WHOLE, "1.5").isEmpty(), "credits have no decimals");
        assertTrue(CurrencyFormat.parse(US, null).isEmpty());
    }

    @Test void caseFoldingIgnoresTheTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            CurrencyRegistry registry = new CurrencyRegistry(java.util.List.of(US, WHOLE));
            assertSame(WHOLE, registry.get("CREDITS"), "CREDITS folds to credits, not credıts");
            assertSame(US, registry.get("MONEY"));
            assertEquals("credits", CurrencyFormat.fold("CREDITS"));
            assertEquals("info", CommandWords.canonical("INFO"));
            assertEquals("info", CommandWords.canonical("BİLGİ"));
            assertEquals("buy", CommandWords.canonical("SATINAL"));
            assertEquals("$1,250.50", CurrencyFormat.format(US, 125_050), "the currency locale wins over the JVM default");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
