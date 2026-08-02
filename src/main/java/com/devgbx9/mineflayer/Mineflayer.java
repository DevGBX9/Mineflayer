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

        // Refuses kicks aimed at the fake player, including ones from other
        // plugins. Registered even before it is started: it keys off the manager's
        // own protection flag, so it does nothing until there is a player to guard.
        getServer().getPluginManager().registerEvents(new FakePlayerGuard(fakePlayer), this);
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
