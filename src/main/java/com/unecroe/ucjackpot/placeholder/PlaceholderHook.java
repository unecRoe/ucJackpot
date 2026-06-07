package com.unecroe.ucjackpot.placeholder;

import com.unecroe.ucjackpot.economy.EconomyService;
import com.unecroe.ucjackpot.jackpot.JackpotService;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlaceholderHook {
    private PlaceholderHook() {
    }

    public static void register(JavaPlugin plugin, JackpotService jackpot, EconomyService economy) {
        new UcPlaceholderExpansion(plugin, jackpot, economy).register();
    }
}


