package com.devgbx9.mineflayer.fabric;

import com.devgbx9.mineflayer.Mineflayer;
import net.fabricmc.api.ModInitializer;

public class MineflayerFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Mineflayer.init();
    }
}
