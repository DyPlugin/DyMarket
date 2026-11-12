package dev.dymarket;

import dev.dymarket.lang.Language;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Message files, plugin.yml and the default config agree with the code and the spec. */
class ResourcesTest {
    private static YamlConfiguration resource(String name) {
        var stream = ResourcesTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, name);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test void everyLanguageHasExactlyTheEnglishKeysAndPlaceholders() {
        YamlConfiguration english = resource("messages_en.yml");
        Pattern placeholder = Pattern.compile("\\{[a-z_]+}");
        for (Language language : Language.values()) {
            YamlConfiguration other = resource(language.fileName());
            assertEquals(new TreeSet<>(english.getKeys(true)), new TreeSet<>(other.getKeys(true)), language.fileName());
            for (String key : english.getKeys(true)) {
                if (!english.isString(key)) continue;
                Set<String> expected = new TreeSet<>(), found = new TreeSet<>();
                placeholder.matcher(english.getString(key)).results().forEach(m -> expected.add(m.group()));
                placeholder.matcher(other.getString(key)).results().forEach(m -> found.add(m.group()));
                assertEquals(expected, found, language.fileName() + " " + key);
            }
        }
    }

    @Test void everyMessageTheCodeSendsExists() throws Exception {
        YamlConfiguration english = resource("messages_en.yml");
        YamlConfiguration config = resource("config.yml");
        Pattern used = Pattern.compile("\"((?:general|economy|balance|pay|baltop|credits|admin|help|alerts|updates|value|auction|auction-gui|estate|estate-gui)\\.[a-z.-]+)\"");
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = used.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (key.endsWith("-") || config.contains(key)) continue;
                    if (!english.contains(key)) missing.add(key + " in " + file.getFileName());
                }
            }
        }
        for (String code : List.of("auto", "en", "tr", "de", "es", "ru")) assertTrue(english.contains("value.language-" + code));
        for (String module : List.of("auction", "estate")) assertTrue(english.contains("value.module-" + module));
        assertTrue(missing.isEmpty(), "missing messages: " + missing);
    }

    @Test void auctionKeysBuiltAtRunTimeExist() {
        for (Language language : Language.values()) {
            YamlConfiguration messages = resource(language.fileName());
            for (String reason : List.of(dev.dymarket.auction.CollectEntry.EXPIRED, dev.dymarket.auction.CollectEntry.CANCELLED,
                    dev.dymarket.auction.CollectEntry.REMOVED, dev.dymarket.auction.CollectEntry.FULL_INVENTORY,
                    dev.dymarket.auction.CollectEntry.PURCHASE)) {
                String key = "auction-gui.reason-" + reason.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                assertTrue(messages.isString(key), language.fileName() + " " + key);
            }
            for (String sort : dev.dymarket.auction.gui.AuctionGui.SORTS)
                assertTrue(messages.isString("auction-gui.sort-" + sort), language.fileName() + " " + sort);
            for (String list : List.of("item-lore", "item-lore-own", "mine-item-lore", "confirm-item-lore", "collect-item-lore",
                    "sort-lore", "mine-lore", "collect-lore", "search-lore", "confirm-lore"))
                assertFalse(messages.getStringList("auction-gui." + list).isEmpty(), language.fileName() + " " + list);
        }
        YamlConfiguration turkish = resource("messages_tr.yml");
        assertTrue(turkish.getString("auction.usage").contains("/pazar"));
        assertTrue(turkish.getString("auction.empty-hand").contains("istediğin eşyayı"));
    }

    @Test void estateWindowListsExistInEveryLanguage() {
        for (Language language : Language.values()) {
            YamlConfiguration messages = resource(language.fileName());
            for (String list : List.of("sale-lore", "rent-lore", "landlord-lore", "tenant-lore", "confirm-buy-lore", "confirm-lease-lore", "mine-lore"))
                assertFalse(messages.getStringList("estate-gui." + list).isEmpty(), language.fileName() + " " + list);
        }
        YamlConfiguration turkish = resource("messages_tr.yml");
        assertTrue(turkish.getString("estate.usage").contains("/emlak"));
        assertTrue(turkish.getString("estate.usage").contains("tut"), "lease is 'tut' in Turkish");
    }

    @Test void turkishTextsUseTurkishLetters() {
        YamlConfiguration turkish = resource("messages_tr.yml");
        assertEquals("Türkçe", turkish.getString("language-name"));
        assertTrue(turkish.getString("general.no-permission").contains("yetkin"));
        assertTrue(turkish.getString("value.language-en").startsWith("İ"));
    }

    @Test void pluginYmlDeclaresTheSpecCommandsAliasesAndPermissions() throws Exception {
        YamlConfiguration plugin = resource("plugin.yml");
        assertTrue(plugin.getBoolean("folia-supported"));
        assertEquals("dev.dymarket.DyMarket", plugin.getString("main"));
        assertEquals(List.of("Vault", "DyClaim", "DyDiscord", "PlaceholderAPI"), plugin.getStringList("softdepend"));
        assertEquals("Multi-currency economy, web-credit bridge, auction house and DyClaim estate rent and sale in one light plugin,"
                + " with English, Turkish, German, Spanish and Russian messages.", plugin.getString("description"));
        Map<String, List<String>> aliases = Map.of("balance", List.of("bal", "money", "para", "bakiye"), "pay", List.of("ode", "gonder"),
                "baltop", List.of("balancetop", "paratop", "zenginler"), "credits", List.of("credit", "kredi"),
                "ah", List.of("auction", "auctionhouse", "pazar", "muzayede"), "estate", List.of("realestate", "emlak"),
                "dymarket", List.of("dym", "dyma"));
        aliases.forEach((command, expected) -> assertEquals(expected, plugin.getStringList("commands." + command + ".aliases"), command));
        String text = new String(java.util.Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("plugin.yml"))
                .readAllBytes(), StandardCharsets.UTF_8);
        for (String permission : List.of("dymarket.balance", "dymarket.balance.others", "dymarket.pay", "dymarket.baltop", "dymarket.credits",
                "dymarket.credits.send", "dymarket.credits.exchange", "dymarket.ah", "dymarket.ah.sell", "dymarket.estate",
                "dymarket.estate.sell", "dymarket.estate.rent", "dymarket.admin", "dymarket.admin.economy", "dymarket.admin.ah",
                "dymarket.admin.estate", "dymarket.admin.alerts", "dymarket.bypass.ratelimit", "dymarket.bypass.tax"))
            assertTrue(text.contains("\n  " + permission + ":"), permission);
    }

    @Test void configMatchesTheSpecDefaults() {
        YamlConfiguration config = resource("config.yml");
        assertEquals("auto", config.getString("language.default"));
        assertEquals(20, config.getInt("storage.flush-interval-ticks"));
        assertTrue(config.getBoolean("currencies.money.vault"));
        assertEquals("internal", config.getString("currencies.money.provider"));
        assertEquals(0, config.getInt("currencies.credits.decimals"));
        assertEquals(1000.0, config.getDouble("credits.exchange.rate"));
        assertEquals(100000.0, config.getDouble("security.alert.transfer-above"));
        assertEquals(List.of("money", "credits"), config.getStringList("auction.allowed-currencies"));
        assertEquals(35262, DyMarket.BSTATS_ID);
    }

    @Test void missingKeysAreAddedWithoutTouchingEdits() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("pay.enabled", true);
        defaults.set("pay.min-amount", 0.01);
        defaults.set("currencies.money.decimals", 2);
        defaults.set("currencies.credits.decimals", 0);
        YamlConfiguration user = new YamlConfiguration();
        user.set("pay.enabled", false);
        user.set("currencies.money.symbol", "TL");
        assertTrue(DyMarket.copyMissing(defaults, user, null));
        assertFalse(user.getBoolean("pay.enabled"), "edited value kept");
        assertEquals(0.01, user.getDouble("pay.min-amount"));
        assertEquals(2, user.getInt("currencies.money.decimals"));
        assertFalse(user.contains("currencies.credits"), "a removed currency stays removed");
        assertFalse(DyMarket.copyMissing(defaults, user, null), "nothing left to add");
    }
}
