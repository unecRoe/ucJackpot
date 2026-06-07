package com.unecroe.ucjackpot.economy;

import org.bukkit.OfflinePlayer;

public interface EconomyProvider {
    String name();

    boolean available();

    boolean has(OfflinePlayer player, double amount);

    boolean withdraw(OfflinePlayer player, double amount);

    boolean deposit(OfflinePlayer player, double amount);

    String format(double amount);
}


