package dev.dymarket;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {
    static YamlConfiguration config() {
        var stream = ConfigValidatorTest.class.getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    private static void rejected(String path, Object value) {
        YamlConfiguration config = config();
        config.set(path, value);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ConfigValidator.validate(config), path + "=" + value);
        assertTrue(error.getMessage().contains(path.split("\\.")[0]), error.getMessage());
    }

    @Test void theDefaultConfigIsValid() {
        assertDoesNotThrow(() -> ConfigValidator.validate(config()));
    }

    @Test void exactlyOneVaultCurrency() {
        rejected("currencies.credits.vault", true);
        rejected("currencies.money.vault", false);
        YamlConfiguration config = config();
        config.set("currencies.money.enabled", false);
        config.set("currencies.credits.vault", true);
        assertDoesNotThrow(() -> ConfigValidator.validate(config), "a disabled currency does not count");
    }

    @Test void decimalsLocaleAndAmounts() {
        rejected("currencies.money.decimals", 9);
        rejected("currencies.money.decimals", -1);
        rejected("currencies.money.decimals", 1.5);
        rejected("currencies.money.format-locale", "banana");
        rejected("currencies.money.format-locale", "");
        rejected("currencies.money.max-balance", 0);
        rejected("currencies.money.starting-balance", -1);
        rejected("currencies.money.starting-balance", 2_000_000_000.0);
        rejected("currencies.money.provider", "essentials");
        rejected("currencies.credits.provider", "vault");
        YamlConfiguration config = config();
        config.set("currencies.money.format-locale", "tr-TR");
        config.set("currencies.money.decimals", 8);
        assertDoesNotThrow(() -> ConfigValidator.validate(config));
    }

    @Test void ratesPercentagesAndLimits() {
        rejected("credits.exchange.rate", 0);
        rejected("credits.exchange.rate", -2.5);
        rejected("credits.topup-bonus-percent", -1);
        rejected("auction.tax-percent", 101);
        rejected("estate.tax-percent", -0.5);
        rejected("baltop.page-size", 0);
        rejected("storage.flush-interval-ticks", 0);
        rejected("storage.type", "mysql");
        rejected("language.default", "fr");
        rejected("auction.gui.sort", "random");
        rejected("estate.rent.min-days", 100);
        rejected("estate.rent.rights", List.of("build", "fly"));
        rejected("estate.rent.rights", List.of());
        rejected("estate.rent.check-interval-minutes", 0);
        rejected("estate.max-chunks-per-listing", 0);
        rejected("estate.rent.refund-on-owner-cancel-percent", 101);
        rejected("estate.sale", "yes");
        rejected("auction.min-price", 200_000_000.0);
        rejected("pay.enabled", "yes");
        rejected("metrics.enabled", "no");
    }

    @Test void moduleCurrenciesMustExist() {
        rejected("auction.allowed-currencies", List.of("money", "gems"));
        rejected("auction.currency", "gems");
        rejected("estate.allowed-currencies", List.of("credits"));
    }

    @Test void auctionListsAndSections() {
        rejected("auction.blacklist", List.of("BEDROCK", "minecraft:barrier"));
        rejected("auction.blacklist", "BEDROCK");
        rejected("auction.block-items-with-lore-containing", List.of(" "));
        rejected("auction.gui", 6);
        rejected("auction.duration-hours", 0);
        rejected("auction.max-listings", -1);
        rejected("auction.gui.rows", 1);
        rejected("auction.confirm-purchase", "sometimes");
        rejected("auction.broadcast-sales", 1);
        YamlConfiguration config = config();
        config.set("auction.blacklist", List.of("bedrock", "ELYTRA"));
        config.set("auction.block-items-with-lore-containing", List.of("Soulbound"));
        config.set("auction.max-listings", 0);
        assertDoesNotThrow(() -> ConfigValidator.validate(config));
    }
}
