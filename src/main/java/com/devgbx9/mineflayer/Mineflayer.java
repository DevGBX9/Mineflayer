package com.devgbx9.mineflayer;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class Mineflayer extends JavaPlugin {

    private FakePlayerManager fakePlayer;

    @Override
    public void onEnable() {
        fakePlayer = new FakePlayerManager(this);
        MineflayerCommand handler = new MineflayerCommand(fakePlayer);

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
        // Without this the fake player survives a plugin reload as a ghost entry
        // in the player list that nothing owns any more.
        if (fakePlayer != null && fakePlayer.isOnline()) {
            fakePlayer.stop();
        }
    }
}
