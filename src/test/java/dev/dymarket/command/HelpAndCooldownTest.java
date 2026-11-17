package dev.dymarket.command;

import dev.dymarket.economy.Cooldowns;
import dev.dymarket.lang.Language;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HelpAndCooldownTest {
    private YamlConfiguration resource(String name) {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, name);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test void everyHelpRowExistsInEveryLanguage() {
        for (Language language : Language.values()) {
            YamlConfiguration messages = resource(language.fileName());
            for (String row : DyMarketCommand.PLAYER_HELP) assertTrue(messages.isString("help-v2." + row), language.fileName() + " help-v2." + row);
            for (String row : DyMarketCommand.ADMIN_HELP) assertTrue(messages.isString("admin-help." + row), language.fileName() + " admin-help." + row);
            for (String key : new String[]{"header", "footer", "page", "page-next", "admin"}) assertTrue(messages.isString("help-v2." + key), key);
            assertTrue(messages.getString("help-v2.page-next").contains("{command}"), language.fileName());
            assertFalse(messages.getString("prefix").contains("&8"), "the prefix uses the family's light grey brackets");
        }
    }

    @Test void adminRowsUseTheDymaShortcutAndPlayerRowsHideAdminWords() {
        YamlConfiguration english = resource("messages_en.yml");
        for (String row : DyMarketCommand.ADMIN_HELP) assertTrue(english.getString("admin-help." + row).matches("&6/(dyma|eco) .*"), row);
        for (String row : DyMarketCommand.PLAYER_HELP) {
            String text = english.getString("help-v2." + row);
            assertFalse(text.contains(" give ") || text.contains(" rollback "), row);
        }
    }

    @Test void windowTitlesAndLoreStayReadable() {
        for (Language language : Language.values()) {
            YamlConfiguration messages = resource(language.fileName());
            for (String section : new String[]{"auction-gui", "estate-gui"})
                for (String key : messages.getConfigurationSection(section).getKeys(false)) {
                    String path = section + "." + key;
                    if (key.startsWith("title")) {
                        assertFalse(messages.getString(path).matches("(?s).*&[0-9a-fA-Fk-oK-OrRxX].*"), language.fileName() + " " + path + " has no colour, so dark resource packs can recolour it");
                    } else if (messages.isList(path)) {
                        for (String line : messages.getStringList(path)) assertFalse(line.contains("&8"), language.fileName() + " " + path);
                    }
                }
        }
    }

    @Test void cooldownsWaitAndSurviveUntilTheyEnd() {
        AtomicLong now = new AtomicLong(1_000);
        Cooldowns cooldowns = new Cooldowns(now::get);
        UUID player = UUID.randomUUID();
        assertEquals(0, cooldowns.remaining(player, "pay", 3_000));
        cooldowns.used(player, "pay");
        assertEquals(3_000, cooldowns.remaining(player, "pay", 3_000));
        assertEquals(0, cooldowns.remaining(player, "ah-sell", 3_000), "actions wait separately");
        now.addAndGet(1_000);
        cooldowns.prune(3_000);
        assertEquals(2_000, cooldowns.remaining(player, "pay", 3_000), "a running wait is kept");
        now.addAndGet(2_000);
        assertEquals(0, cooldowns.remaining(player, "pay", 3_000));
        assertEquals(0, cooldowns.remaining(player, "pay", 0), "0 seconds means no wait");
    }

    @Test void everyCooldownIsInTheBundledConfig() {
        YamlConfiguration config = resource("config.yml");
        for (String action : Commands.COOLDOWNS) assertTrue(config.isSet("cooldowns." + action), action);
    }
}
