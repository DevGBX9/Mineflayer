package com.devgbx9.mineflayer;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * Handles {@code /mineflayer m <start|stop>}, which brings the monitoring fake
 * player online and takes it back off.
 */
public class MineflayerCommand implements CommandExecutor, TabCompleter {

    private static final String SUB = "m";
    private static final List<String> ACTIONS = List.of("start", "stop");
    private static final String PREFIX = ChatColor.AQUA + "[Mineflayer] " + ChatColor.WHITE;

    private final FakePlayerManager manager;

    public MineflayerCommand(FakePlayerManager manager) {
        this.manager = manager;
    }

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

        if (action.equals("start")) {
            start(sender);
        } else {
            stop(sender);
        }
        return true;
    }

    private void start(CommandSender sender) {
        if (manager.isOnline()) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + manager.name() + " is already online.");
            return;
        }

        String failure = manager.start();
        if (failure == null) {
            sender.sendMessage(PREFIX + ChatColor.GREEN + manager.name() + " joined. "
                    + ChatColor.WHITE + "Players online: " + Bukkit.getOnlinePlayers().size() + ".");
        } else {
            sender.sendMessage(PREFIX + ChatColor.RED + "Could not start: " + failure);
            sender.sendMessage(PREFIX + ChatColor.GRAY
                    + "This build reaches server internals by reflection; see the console for details.");
        }
    }

    private void stop(CommandSender sender) {
        if (!manager.isOnline()) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + manager.name() + " is not online.");
            return;
        }

        String failure = manager.stop();
        if (failure == null) {
            sender.sendMessage(PREFIX + ChatColor.GREEN + manager.name() + " left. "
                    + ChatColor.WHITE + "Players online: " + Bukkit.getOnlinePlayers().size() + ".");
        } else {
            sender.sendMessage(PREFIX + ChatColor.RED + "Could not stop cleanly: " + failure);
        }
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
