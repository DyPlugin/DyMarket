package dev.dymarket.command;

import dev.dymarket.DyMarket;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Map;

/** Answers /ah and /estate while their module is not loaded (turned off, or DyClaim missing for estate). */
public final class ModuleStubCommand implements CommandExecutor, TabCompleter {
    private final DyMarket plugin;
    private final String module;

    public ModuleStubCommand(DyMarket plugin, String module) {
        this.plugin = plugin;
        this.module = module;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.messages().send(sender, "general.module-not-loaded", Map.of("{module}", plugin.messages().get(sender, "value.module-" + module)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
