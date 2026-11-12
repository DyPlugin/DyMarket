package dev.dymarket.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * English sub-command words and their Turkish forms. Only sub-command positions are translated; player names,
 * amounts and ids stay untouched. Matching ignores case (Locale.ROOT) and Turkish letters, so "satınal",
 * "SATINAL" and "satinal" are all "buy".
 */
public final class CommandWords {
    private static final Map<String, String> TR = new LinkedHashMap<>();
    /** Other accepted spellings: English shortcuts and Turkish forms with special letters. */
    private static final Map<String, String> EXTRA = new LinkedHashMap<>();

    static {
        String[][] pairs = {
                {"help", "yardım"}, {"reload", "yenile"}, {"language", "dil"}, {"give", "ver"}, {"take", "al"},
                {"set", "ayarla"}, {"log", "kayit"}, {"rollback", "gerial"}, {"merge", "birlestir"}, {"import", "aktar"},
                {"sell", "sat"}, {"rent", "kirala"}, {"unlist", "kaldir"}, {"list", "liste"}, {"info", "bilgi"},
                {"buy", "satinal"}, {"collect", "topla"}, {"cancel", "iptal"}, {"search", "ara"}, {"mine", "benim"},
                {"send", "gonder"}, {"exchange", "cevir"}, {"autorenew", "otoyenile"}, {"leave", "ayril"},
                {"evict", "tahliye"}, {"remove", "sil"}, {"purge", "temizle"}, {"lease", "tut"},
                {"confirm", "onayla"}, {"force", "zorla"}, {"auto", "oto"}, {"here", "burasi"}};
        for (String[] pair : pairs) TR.put(pair[0], pair[1]);
        EXTRA.put("lang", "language");
        EXTRA.put("otomatik", "auto");
        EXTRA.put("yardim", "help");
        EXTRA.put("logs", "log");
        EXTRA.put("history", "log");
        EXTRA.put("gecmis", "log");
    }

    private CommandWords() {
    }

    /** Lower case (Locale.ROOT) with Turkish letters folded to ASCII. */
    public static String plain(String text) {
        if (text == null) return "";
        return text.replace('İ', 'i').replace('I', 'i').toLowerCase(Locale.ROOT)
                .replace('ı', 'i').replace('ş', 's').replace('ç', 'c').replace('ğ', 'g').replace('ö', 'o').replace('ü', 'u');
    }

    /** The English word for a typed English or Turkish word; anything else comes back folded to lower case. */
    public static String canonical(String typed) {
        String plain = plain(typed);
        if (TR.containsKey(plain)) return plain;
        for (Map.Entry<String, String> entry : TR.entrySet()) if (plain(entry.getValue()).equals(plain)) return entry.getKey();
        String extra = EXTRA.get(plain);
        return extra != null ? extra : plain;
    }

    /** True when the typed word is the English word or its Turkish form. */
    public static boolean is(String typed, String english) {
        return typed != null && canonical(typed).equals(english);
    }

    public static String display(String english, boolean turkish) {
        return turkish ? TR.getOrDefault(english, english) : english;
    }

    public static List<String> choices(boolean turkish, String... words) {
        return Arrays.stream(words).map(word -> display(word, turkish)).toList();
    }

    public static List<String> choices(boolean turkish, Collection<String> words) {
        return words.stream().map(word -> display(word, turkish)).toList();
    }

    /** All English words with a Turkish form, for tests and documentation. */
    public static Map<String, String> turkish() {
        return java.util.Collections.unmodifiableMap(TR);
    }
}
