package dev.dymarket.listener;

import dev.dymarket.DyMarket;
import dev.dymarket.storage.SqliteStore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

/**
 * Loads a player's account and language before they join (off the server thread), keeps the stored name and
 * last-seen time current, and delivers notices that waited while they were offline.
 */
public final class PlayerListener implements Listener {
    private final DyMarket plugin;

    public PlayerListener(DyMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        preload(event.getUniqueId(), event.getName());
    }

    /** Loads the account and language; runs on a non-server thread. */
    public void preload(UUID id, String name) {
        try {
            plugin.economy().ensure(id, name);
            SqliteStore.PlayerRow row = plugin.io().call(() -> plugin.store().player(id));
            plugin.messages().cachePlayerLanguage(id, row == null ? null : row.language());
        } catch (Exception failure) {
            plugin.getLogger().warning("Could not preload the account of " + name + ": " + failure.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        String name = player.getName();
        String hidden = plugin.getConfig().getString("baltop.exclude-permission", "dymarket.baltop.hidden");
        plugin.baltop().hidden(id, hidden != null && !hidden.isEmpty() && player.hasPermission(hidden));
        plugin.io().execute(() -> {
            try {
                if (!plugin.economy().loaded(id)) preload(id, name);
                else plugin.economy().ensure(id, name);
                plugin.store().upsertPlayer(id, name, System.currentTimeMillis());
                List<SqliteStore.Notice> notices = plugin.store().takeNotices(id);
                if (notices.isEmpty()) return;
                plugin.scheduler().runLater(player, () -> {
                    for (SqliteStore.Notice notice : notices) plugin.messages().send(player, notice.key(), notice.values());
                }, 40L);
            } catch (Exception failure) {
                plugin.getLogger().warning("Could not update player data for " + name + ": " + failure.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        plugin.rateLimiter().forget(id);
        plugin.messages().forget(id);
        plugin.io().execute(() -> {
            try {
                plugin.store().upsertPlayer(id, name, System.currentTimeMillis());
            } catch (Exception failure) {
                plugin.getLogger().warning("Could not save last-seen time for " + name + ": " + failure.getMessage());
            }
        });
    }
}
