package com.unecroe.ucjackpot.debug;

import com.unecroe.ucjackpot.config.PluginSettings;
import org.bukkit.plugin.java.JavaPlugin;

public final class DebugLogger {
    private final JavaPlugin plugin;
    private PluginSettings settings;

    public DebugLogger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void settings(PluginSettings settings) {
        this.settings = settings;
    }

    public void log(String category, String message) {
        if (settings != null && settings.debugCategory(category)) {
            plugin.getLogger().info("[debug:" + category + "] " + message);
        }
    }

    public void warn(String category, String message, Throwable throwable) {
        if (settings != null && settings.debugCategory(category)) {
            plugin.getLogger().warning("[debug:" + category + "] " + message + " (" + throwable.getMessage() + ")");
        }
    }
}


