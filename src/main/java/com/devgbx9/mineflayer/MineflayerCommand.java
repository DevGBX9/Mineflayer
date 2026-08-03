package com.devgbx9.mineflayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import com.devgbx9.mineflayer.remote.RemoteBotManager;

/**
 * Handles the plugin's two commands.
 *
 * <ul>
 *   <li>{@code /mineflayer m <start|stop>} brings the local fake player online
 *       and takes it back off.</li>
 *   <li>{@code /mineflayer m connectto <ip> <port> <start|stop>} sends a bot to
 *       another server entirely, over a real client connection.</li>
 * </ul>
 *
 * <p>The two share a subcommand because they are the same idea seen twice: a
 * player that is not a person, here or elsewhere. Which one is meant is decided
 * by the argument count, so neither form can shadow the other.
 */
public class MineflayerCommand implements CommandExecutor, TabCompleter {

    private static final String SUB = "m";
    private static final String CONNECT = "connectto";
    private static final List<String> ACTIONS = List.of("start", "stop");
    private static final String PREFIX = ChatColor.AQUA + "[Mineflayer] " + ChatColor.WHITE;

    /** Argument count for {@code m connectto <ip> <port> <action>}. */
    private static final int CONNECT_ARGS = 5;

    private final FakePlayerManager manager;
    private final RemoteBotManager remote;

    public MineflayerCommand(FakePlayerManager manager, RemoteBotManager remote) {
        this.manager = manager;
        this.remote = remote;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Returning false makes Bukkit print the usage string from plugin.yml.
        if (args.length == 0 || !args[0].equalsIgnoreCase(SUB)) {
            return false;
        }

        if (args.length == CONNECT_ARGS && args[1].equalsIgnoreCase(CONNECT)) {
            return connectTo(sender, args[2], args[3], args[4]);
        }

        if (args.length != 2) {
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

    /**
     * Starts or stops the remote bot.
     *
     * @return {@code false} to print the usage string, for arguments that are
     *         malformed rather than merely unsuccessful
     */
    private boolean connectTo(CommandSender sender, String host, String rawPort, String rawAction) {
        String action = rawAction.toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            return false;
        }

        int port;
        try {
            port = Integer.parseInt(rawPort);
        } catch (NumberFormatException e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "'" + rawPort + "' is not a port number.");
            return true;
        }
        if (port < 1 || port > 65535) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Port " + port + " is outside 1-65535.");
            return true;
        }
        if (host.isBlank()) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No address given.");
            return true;
        }

        if (action.equals("start")) {
            remoteStart(sender, host, port);
        } else {
            remoteStop(sender);
        }
        return true;
    }

    private void remoteStart(CommandSender sender, String host, int port) {
        String failure = remote.start(host, port);
        if (failure != null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Could not connect: " + failure);
            return;
        }
        // Only the attempt is confirmed here. The connection runs on its own
        // thread and takes a moment; the console carries the outcome, because it
        // arrives after this command has already returned.
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Connecting to " + host + ":" + port + "...");
        sender.sendMessage(PREFIX + ChatColor.GRAY + "Progress is reported in the console.");
    }

    private void remoteStop(CommandSender sender) {
        String target = remote.target();
        String failure = remote.stop();
        if (failure == null) {
            sender.sendMessage(PREFIX + ChatColor.GREEN + "Disconnected from "
                    + (target == null ? "the target server" : target) + ".");
        } else {
            sender.sendMessage(PREFIX + ChatColor.RED + failure);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label,
            String[] args) {
        if (args.length == 1) {
            return prefixed(List.of(SUB), args[0]);
        }
        if (!args[0].equalsIgnoreCase(SUB)) {
            return Collections.emptyList();
        }

        switch (args.length) {
            case 2 -> {
                List<String> options = new ArrayList<>(ACTIONS);
                options.add(CONNECT);
                return prefixed(options, args[1]);
            }
            case 3 -> {
                if (args[1].equalsIgnoreCase(CONNECT)) {
                    // A hint at the shape rather than a real suggestion; there is
                    // no list of addresses to draw from.
                    return prefixed(List.of("127.0.0.1"), args[2]);
                }
            }
            case 4 -> {
                if (args[1].equalsIgnoreCase(CONNECT)) {
                    return prefixed(List.of("25565"), args[3]);
                }
            }
            case 5 -> {
                if (args[1].equalsIgnoreCase(CONNECT)) {
                    return prefixed(ACTIONS, args[4]);
                }
            }
            default -> {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(lower)).toList();
    }
}
