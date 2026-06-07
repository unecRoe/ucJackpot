package com.unecroe.ucjackpot.economy;

import com.unecroe.ucjackpot.config.ConfigService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyListener implements Listener {
    private static final String VAULT_ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    private final JavaPlugin plugin;
    private final ConfigService config;
    private final EconomyService economy;

    public EconomyListener(JavaPlugin plugin, ConfigService config, EconomyService economy) {
        this.plugin = plugin;
        this.config = config;
        this.economy = economy;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("Vault")) {
            Bukkit.getScheduler().runTask(plugin, () -> economy.reload(config.settings()));
        }
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService().getName().equals(VAULT_ECONOMY_CLASS)) {
            Bukkit.getScheduler().runTask(plugin, () -> economy.reload(config.settings()));
        }
    }
}


