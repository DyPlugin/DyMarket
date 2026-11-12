package dev.dymarket.hook;

import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Staff alerts through DyDiscord. DyDiscord's public API ({@code dev.dydiscord.api.DyDiscordAPI}) currently only
 * reads account links and has no staff-alert method, and DyMarket does not reach into its private classes. Until
 * DyDiscord adds such a method, alerts stay in game and in the console, and this hook says so once at start.
 */
public final class DyDiscordHook {
    private final Logger logger;
    private volatile boolean available;

    public DyDiscordHook(Logger logger) {
        this.logger = logger;
    }

    /** Checks DyDiscord when alerts to Discord are switched on. */
    public void start(Plugin dyDiscord, boolean wanted) {
        available = false;
        if (!wanted || dyDiscord == null || !dyDiscord.isEnabled()) return;
        if (hasStaffAlertApi(dyDiscord)) {
            available = true;
            return;
        }
        logger.info("DyDiscord " + dyDiscord.getDescription().getVersion()
                + " has no staff-alert API yet; DyMarket alerts are shown in game and in the console only.");
    }

    /** True once DyDiscord's public API has a staff-alert entry point; none exists in current versions. */
    private static boolean hasStaffAlertApi(Plugin dyDiscord) {
        return false;
    }

    public boolean available() {
        return available;
    }

    public void staffAlert(String text) {
        if (!available) return;
        // Reserved for DyDiscord's future public staff-alert method.
    }
}
