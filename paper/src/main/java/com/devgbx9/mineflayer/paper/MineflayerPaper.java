package com.devgbx9.mineflayer.paper;

import com.devgbx9.mineflayer.Mineflayer;
import org.bukkit.plugin.java.JavaPlugin;

public class MineflayerPaper extends JavaPlugin {
    @Override
    public void onEnable() {
        Mineflayer.init();
    }

    @Override
    public void onDisable() {
    }
}
