package dev.dymarket.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Uses another plugin's Vault economy for a currency with {@code provider: vault}. DyMarket then registers no
 * economy of its own; it logs what passes through its commands.
 */
public final class VaultConsumer implements ExternalEconomy {
    private final Supplier<Currency> currency;

    public VaultConsumer(Supplier<Currency> currency) {
        this.currency = currency;
    }

    /** The highest-priority Vault economy that is not DyMarket's own, or null. */
    public static Economy find() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;
        Economy best = null;
        for (RegisteredServiceProvider<Economy> registration : Bukkit.getServicesManager().getRegistrations(Economy.class)) {
            if (registration.getProvider() instanceof VaultProvider) continue;
            if (best == null) best = registration.getProvider();
        }
        return best;
    }

    @Override
    public boolean available() {
        return find() != null;
    }

    private static OfflinePlayer player(UUID id) {
        return Bukkit.getOfflinePlayer(id);
    }

    @Override
    public long balance(UUID id) {
        Economy economy = find();
        if (economy == null) return 0;
        try {
            return currency.get().toMinor(BigDecimal.valueOf(economy.getBalance(player(id))));
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    @Override
    public boolean withdraw(UUID id, long minor) {
        Economy economy = find();
        return economy != null && economy.withdrawPlayer(player(id), currency.get().toDouble(minor)).transactionSuccess();
    }

    @Override
    public boolean deposit(UUID id, long minor) {
        Economy economy = find();
        return economy != null && economy.depositPlayer(player(id), currency.get().toDouble(minor)).transactionSuccess();
    }
}
