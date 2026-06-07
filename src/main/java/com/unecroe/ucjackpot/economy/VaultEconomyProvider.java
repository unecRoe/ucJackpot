package com.unecroe.ucjackpot.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

public final class VaultEconomyProvider implements EconomyProvider {
    private final Economy economy;

    public VaultEconomyProvider(Economy economy) {
        this.economy = economy;
    }

    @Override
    public String name() {
        return "Vault:" + economy.getName();
    }

    @Override
    public boolean available() {
        return economy != null;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return economy.has(player, amount);
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        return economy.depositPlayer(player, amount).transactionSuccess();
    }

    @Override
    public String format(double amount) {
        return economy.format(amount);
    }
}


