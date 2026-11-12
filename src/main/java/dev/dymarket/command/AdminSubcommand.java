package dev.dymarket.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * A /dymarket sub-command contributed by a module (auction: "ah", estate: "estate"). Registered with
 * {@code DyMarket#adminCommand}; while none is registered the command says the module is not loaded.
 */
public interface AdminSubcommand {
    /** {@code args} start after the module word, for example ["remove", "abc123"] for /dymarket ah remove abc123. */
    void execute(CommandSender sender, String[] args);

    List<String> complete(CommandSender sender, String[] args);
}
