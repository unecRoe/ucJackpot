package com.unecroe.ucjackpot.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

final class VaultHook {
    private VaultHook() {
    }

    static EconomyProvider load(JavaPlugin plugin) {
        RegisteredServiceProvider<Economy> registration = plugin.getServer()
                .getServicesManager()
                .getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            return null;
        }
        return new VaultEconomyProvider(registration.getProvider());
    }
}


