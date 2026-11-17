package dev.dymarket.gui;

import dev.dymarket.scheduler.Scheduler;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks a player to type an answer in chat, for example a search after clicking the auction house's search button.
 * The typed line is not shown to anyone; the answer runs on the player's own thread (Folia too).
 */
public final class ChatPrompt implements Listener {
    /** Longest answer that is kept; longer text is cut. */
    public static final int MAX_LENGTH = 48;

    private record Pending(Consumer<String> answer, long expires) { }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Scheduler scheduler;

    public ChatPrompt(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Waits up to {@code seconds} for the player's next chat line; a newer question replaces an older one. */
    public void ask(Player player, int seconds, Consumer<String> answer) {
        pending.put(player.getUniqueId(), new Pending(answer, System.currentTimeMillis() + seconds * 1000L));
    }

    public boolean waiting(Player player) {
        Pending entry = pending.get(player.getUniqueId());
        return entry != null && entry.expires() > System.currentTimeMillis();
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Pending entry = pending.remove(player.getUniqueId());
        if (entry == null || entry.expires() < System.currentTimeMillis()) return;
        event.setCancelled(true);
        String text = ChatColor.stripColor(event.getMessage()).replaceAll("\s+", " ").trim();
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);
        String answer = text;
        scheduler.run(player, () -> entry.answer().accept(answer));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
