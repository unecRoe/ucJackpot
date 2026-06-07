package com.unecroe.ucjackpot.economy;

import com.unecroe.ucjackpot.config.PluginSettings;
import com.unecroe.ucjackpot.debug.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyService {
    private final JavaPlugin plugin;
    private final DebugLogger debug;
    private EconomyProvider provider = new DisabledEconomyProvider("$", 2);

    public EconomyService(JavaPlugin plugin, DebugLogger debug) {
        this.plugin = plugin;
        this.debug = debug;
    }

    public void reload(PluginSettings settings) {
        provider = new DisabledEconomyProvider(settings.currencySymbol(), settings.currencyDecimals());
        if (!settings.vaultEnabled() || Bukkit.getPluginManager().getPlugin("Vault") == null) {
            debug.log("economy", "Vault is not enabled or not installed; money jackpot disabled.");
            return;
        }
        try {
            EconomyProvider loaded = VaultHook.load(plugin);
            if (loaded != null) {
                provider = loaded;
                debug.log("economy", "Economy provider loaded: " + provider.name());
            }
        } catch (Throwable throwable) {
            debug.warn("economy", "Unable to hook Vault economy", throwable);
        }
    }

    public EconomyProvider provider() {
        return provider;
    }
}


