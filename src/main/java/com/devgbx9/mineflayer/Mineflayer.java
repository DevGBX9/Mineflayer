package com.devgbx9.mineflayer;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class Mineflayer extends JavaPlugin {

    @Override
    public void onEnable() {
        MineflayerCommand handler = new MineflayerCommand();

        PluginCommand command = getCommand("mineflayer");
        if (command == null) {
            // Only reachable if plugin.yml and this class fall out of sync.
            getLogger().severe("Command 'mineflayer' is missing from plugin.yml; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    @Override
    public void onDisable() {
    }
}
