package com.devgbx9.mineflayer;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * Handles {@code /mineflayer m <start|stop>}.
 *
 * <p>Nothing is started or stopped yet: the command only acknowledges that the
 * plugin is loaded and reachable.
 */
public class MineflayerCommand implements CommandExecutor, TabCompleter {

    private static final String SUB = "m";
    private static final List<String> ACTIONS = List.of("start", "stop");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Returning false makes Bukkit print the usage string from plugin.yml.
        if (args.length != 2 || !args[0].equalsIgnoreCase(SUB)) {
            return false;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            return false;
        }

        sender.sendMessage(ChatColor.AQUA + "[Mineflayer] "
                + ChatColor.WHITE + "'" + action + "' received - plugin is running.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return prefixed(List.of(SUB), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase(SUB)) {
            return prefixed(ACTIONS, args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(lower)).toList();
    }
}
