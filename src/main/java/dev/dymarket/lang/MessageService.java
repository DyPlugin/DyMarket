package dev.dymarket.lang;

import dev.dymarket.util.Colors;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Messages in five languages. Lookups are served from memory and are safe on any thread; the per-player
 * language choice is cached here and stored through the given saver (the DyMarket-IO thread).
 */
public class MessageService {
    private final Plugin plugin;
    private volatile Map<Language, FileConfiguration> messages = Map.of();
    private volatile Map<Language, FileConfiguration> bundledMessages = Map.of();
    private final Map<UUID, String> playerLanguages = new ConcurrentHashMap<>();
    private final Consumer<Map.Entry<UUID, String>> saver;
    private volatile String defaultSetting = "auto";

    public MessageService(Plugin plugin, Consumer<Map.Entry<UUID, String>> saver) {
        this.plugin = plugin;
        this.saver = saver;
        reload();
    }

    public void reload() {
        defaultSetting = plugin.getConfig().getString("language.default", "auto");
        Map<Language, FileConfiguration> loaded = new EnumMap<>(Language.class);
        Map<Language, FileConfiguration> bundled = new EnumMap<>(Language.class);
        for (Language language : Language.values()) {
            loaded.put(language, YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), language.fileName())));
            bundled.put(language, bundled(language.fileName()));
        }
        messages = loaded;
        bundledMessages = bundled;
    }

    /** Remembers a stored choice loaded at login; null or blank forgets it. */
    public void cachePlayerLanguage(UUID player, String code) {
        if (code == null || code.isBlank()) playerLanguages.remove(player);
        else playerLanguages.put(player, code);
    }

    public void forget(UUID player) {
        playerLanguages.remove(player);
    }

    /** Stores a player's choice ("auto" clears it). */
    public void setPlayerLanguage(UUID player, String code) {
        cachePlayerLanguage(player, "auto".equals(code) ? null : code);
        saver.accept(Map.entry(player, code));
    }

    public Language language(CommandSender sender) {
        String setting = defaultSetting;
        Language fallback = Language.fromCode(setting, Language.EN);
        if (!"auto".equalsIgnoreCase(setting)) return fallback;
        if (sender instanceof Player player) {
            String saved = playerLanguages.get(player.getUniqueId());
            if (saved != null && !"auto".equalsIgnoreCase(saved)) return Language.fromCode(saved, fallback);
            // The client locale (for example de_de) picks a bundled language; others fall back to English.
            String locale = player.getLocale();
            if (locale != null && locale.length() >= 2) return Language.fromCode(locale.substring(0, 2).toLowerCase(Locale.ROOT), Language.EN);
            return Language.EN;
        }
        return fallback;
    }

    public String get(CommandSender sender, String path) {
        return get(sender, path, Map.of());
    }

    public String get(CommandSender sender, String path, Map<String, String> placeholders) {
        return get(language(sender), path, placeholders);
    }

    public String get(Language language, String path, Map<String, String> placeholders) {
        return Colors.color(apply(language, raw(language, path), escaped(placeholders)));
    }

    /** Text without colours, for the console log and Discord. */
    public String plain(Language language, String path, Map<String, String> placeholders) {
        return Colors.strip(get(language, path, placeholders));
    }

    private String raw(Language language, String path) {
        String text = messages.getOrDefault(language, messages.get(Language.EN)).getString(path);
        if (text == null) text = bundledMessages.getOrDefault(language, bundledMessages.get(Language.EN)).getString(path);
        if (text == null) text = messages.get(Language.EN).getString(path);
        if (text == null) text = bundledMessages.get(Language.EN).getString(path, path);
        return text;
    }

    /** Placeholder values are data (names, numbers, typed input); their MiniMessage tags are never applied. */
    private static Map<String, String> escaped(Map<String, String> placeholders) {
        if (placeholders.isEmpty()) return placeholders;
        Map<String, String> out = new HashMap<>();
        placeholders.forEach((key, value) -> out.put(key, Colors.escapeTags(value == null ? "" : value)));
        return out;
    }

    public List<String> getList(CommandSender sender, String path) {
        Language language = language(sender);
        List<String> list = messages.getOrDefault(language, messages.get(Language.EN)).getStringList(path);
        if (list.isEmpty()) list = bundledMessages.getOrDefault(language, bundledMessages.get(Language.EN)).getStringList(path);
        if (list.isEmpty()) list = messages.get(Language.EN).getStringList(path);
        if (list.isEmpty()) list = bundledMessages.get(Language.EN).getStringList(path);
        return list.stream().map(line -> Colors.color(apply(language, line, Map.of()))).toList();
    }

    public void send(CommandSender sender, String path) {
        send(sender, path, Map.of());
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        String text = get(sender, path, placeholders);
        if (!text.isEmpty()) sender.sendMessage(text);
    }

    /** Sends to every online player with the permission and to the console, each in their own language. */
    public void broadcast(String permission, String path, Map<String, String> placeholders) {
        send(Bukkit.getConsoleSender(), path, placeholders);
        for (Player player : Bukkit.getOnlinePlayers())
            if (permission == null || player.hasPermission(permission)) send(player, path, placeholders);
    }

    /** Replaces {prefix} and the given placeholders; a given {prefix} wins over the file's own. */
    public String apply(Language language, String text, Map<String, String> placeholders) {
        String result = text;
        if (!placeholders.containsKey("{prefix}") && result.contains("{prefix}")) result = result.replace("{prefix}", raw(language, "prefix"));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }

    private FileConfiguration bundled(String fileName) {
        InputStream input = plugin.getResource(fileName);
        if (input == null) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
    }
}
