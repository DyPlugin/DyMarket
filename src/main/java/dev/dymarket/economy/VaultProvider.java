package dev.dymarket.economy;

import dev.dymarket.DyMarket;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DyMarket's Vault economy for the Vault currency. Calls are synchronous and answered from memory; an offline
 * player not in memory is loaded through the IO thread first. Changes are logged as {@code VAULT}.
 * Banks are not supported.
 */
public final class VaultProvider implements Economy {
    private static final String NOTE = "vault";
    private final DyMarket plugin;

    public VaultProvider(DyMarket plugin) {
        this.plugin = plugin;
    }

    private Currency currency() {
        return plugin.currencies().primary();
    }

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "DyMarket"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return currency().decimals(); }

    @Override
    public String format(double amount) {
        Currency currency = currency();
        try {
            return CurrencyFormat.format(currency, currency.toMinor(amount));
        } catch (ArithmeticException | NumberFormatException invalid) {
            return String.valueOf(amount);
        }
    }

    @Override public String currencyNamePlural() { return currency().plural(); }
    @Override public String currencyNameSingular() { return currency().name(); }

    // ---- players --------------------------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private UUID resolve(String name) {
        if (name == null) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        UUID cached = plugin.economy().cachedByName(name);
        if (cached != null) return cached;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.getUniqueId();
    }

    private UUID id(OfflinePlayer player) {
        return player == null ? null : player.getUniqueId();
    }

    @Override public boolean hasAccount(String name) { return resolve(name) != null; }
    @Override public boolean hasAccount(OfflinePlayer player) { return player != null; }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer player, String world) { return hasAccount(player); }

    @Override public double getBalance(String name) { return balance(resolve(name)); }
    @Override public double getBalance(OfflinePlayer player) { return balance(id(player)); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    private double balance(UUID id) {
        if (id == null) return 0;
        try {
            return currency().toDouble(plugin.economy().balance(id, currency()));
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Vault balance request failed: " + failure.getMessage());
            return 0;
        }
    }

    @Override public boolean has(String name, double amount) { return has(resolve(name), amount); }
    @Override public boolean has(OfflinePlayer player, double amount) { return has(id(player), amount); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer player, String world, double amount) { return has(player, amount); }

    private boolean has(UUID id, double amount) {
        if (id == null) return false;
        if (amount <= 0) return true;
        try {
            return plugin.economy().balance(id, currency()) >= currency().toMinor(amount);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return change(resolve(name), amount, false); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return change(id(player), amount, false); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) { return withdrawPlayer(player, amount); }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return change(resolve(name), amount, true); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return change(id(player), amount, true); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) { return depositPlayer(player, amount); }

    private EconomyResponse change(UUID id, double amount, boolean deposit) {
        if (id == null) return new EconomyResponse(0, 0, ResponseType.FAILURE, "Unknown player");
        if (!Double.isFinite(amount) || amount < 0)
            return new EconomyResponse(0, balance(id), ResponseType.FAILURE, "Cannot " + (deposit ? "deposit" : "withdraw") + " negative funds");
        Currency currency = currency();
        long minor;
        try {
            minor = currency.toMinor(BigDecimal.valueOf(amount));
        } catch (ArithmeticException tooLarge) {
            return new EconomyResponse(0, balance(id), ResponseType.FAILURE, "Amount too large");
        }
        if (minor == 0) return new EconomyResponse(0, balance(id), ResponseType.SUCCESS, null);
        TransactionResult result = deposit ? plugin.economy().deposit(id, currency, minor, TransactionKind.VAULT, NOTE)
                : plugin.economy().withdraw(id, currency, minor, TransactionKind.VAULT, NOTE);
        if (result.success()) return new EconomyResponse(amount, currency.toDouble(result.balance()), ResponseType.SUCCESS, null);
        String error = switch (result.status()) {
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            case MAX_BALANCE -> "Balance limit reached";
            case CANCELLED -> "Cancelled by another plugin";
            default -> "Transaction failed (" + result.status() + ")";
        };
        return new EconomyResponse(0, balance(id), ResponseType.FAILURE, error);
    }

    @Override public boolean createPlayerAccount(String name) { return createPlayerAccount(resolve(name), name); }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return player != null && createPlayerAccount(player.getUniqueId(), player.getName()); }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String world) { return createPlayerAccount(player); }

    private boolean createPlayerAccount(UUID id, String name) {
        if (id == null) return false;
        try {
            plugin.economy().ensure(id, name);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    // ---- banks (not supported) ------------------------------------------------------------------------------

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "DyMarket does not support banks");
    }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }
}
