package dev.dymarket.importer;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Copies balances from another Vault economy (for example the one DyMarket replaces). Only reads. */
public final class VaultImporter {
    private final Economy source;
    private final Logger logger;

    public VaultImporter(Economy source, Logger logger) {
        this.source = source;
        this.logger = logger;
    }

    public List<ImportEntry> read(OfflinePlayer[] players) {
        List<ImportEntry> entries = new ArrayList<>();
        int done = 0;
        for (OfflinePlayer player : players) {
            try {
                if (source.hasAccount(player)) {
                    double balance = source.getBalance(player);
                    if (Double.isFinite(balance) && balance >= 0)
                        entries.add(new ImportEntry(player.getUniqueId(), player.getName(), BigDecimal.valueOf(balance)));
                }
            } catch (RuntimeException failure) {
                logger.warning("Vault import: could not read " + player.getUniqueId() + ": " + failure.getMessage());
            }
            if (++done % 1000 == 0) logger.info("Vault import: read " + done + " of " + players.length + " players.");
        }
        return entries;
    }
}
