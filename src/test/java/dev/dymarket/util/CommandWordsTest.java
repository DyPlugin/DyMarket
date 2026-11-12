package dev.dymarket.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandWordsTest {
    @Test void everyTurkishWordFromTheSpecMapsToItsEnglishWord() {
        Map<String, String> spec = Map.ofEntries(Map.entry("yenile", "reload"), Map.entry("dil", "language"), Map.entry("ver", "give"),
                Map.entry("al", "take"), Map.entry("ayarla", "set"), Map.entry("kayit", "log"), Map.entry("gerial", "rollback"),
                Map.entry("birlestir", "merge"), Map.entry("aktar", "import"), Map.entry("sat", "sell"), Map.entry("kirala", "rent"),
                Map.entry("kaldir", "unlist"), Map.entry("liste", "list"), Map.entry("bilgi", "info"), Map.entry("satinal", "buy"),
                Map.entry("topla", "collect"), Map.entry("iptal", "cancel"), Map.entry("ara", "search"), Map.entry("benim", "mine"),
                Map.entry("gonder", "send"), Map.entry("cevir", "exchange"), Map.entry("otoyenile", "autorenew"),
                Map.entry("ayril", "leave"), Map.entry("tahliye", "evict"));
        spec.forEach((turkish, english) -> {
            assertEquals(english, CommandWords.canonical(turkish), turkish);
            assertTrue(CommandWords.is(turkish, english));
            assertEquals(english, CommandWords.canonical(english), "English stays English");
        });
    }

    @Test void turkishLettersAndCaseAreIgnored() {
        assertEquals("buy", CommandWords.canonical("satınal"));
        assertEquals("buy", CommandWords.canonical("SATINAL"));
        assertEquals("unlist", CommandWords.canonical("kaldır"));
        assertEquals("leave", CommandWords.canonical("AYRIL"));
        assertEquals("exchange", CommandWords.canonical("çevir"));
        assertEquals("send", CommandWords.canonical("gönder"));
        assertEquals("info", CommandWords.canonical("BİLGİ"));
        assertEquals("help", CommandWords.canonical("yardım"));
        assertEquals("language", CommandWords.canonical("lang"));
    }

    @Test void foldingDoesNotDependOnTheServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("info", CommandWords.canonical("INFO"));
            assertEquals("list", CommandWords.canonical("LIST"));
            assertEquals("give", CommandWords.canonical("GIVE"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test void displayAndUnknownWords() {
        assertEquals("satinal", CommandWords.display("buy", true));
        assertEquals("buy", CommandWords.display("buy", false));
        assertEquals("steve", CommandWords.canonical("Steve"), "names are only folded");
        assertEquals(java.util.List.of("sat", "liste"), CommandWords.choices(true, "sell", "list"));
    }
}
