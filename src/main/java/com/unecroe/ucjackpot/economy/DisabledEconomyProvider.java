package com.unecroe.ucjackpot.economy;

import org.bukkit.OfflinePlayer;

import java.text.DecimalFormat;

public final class DisabledEconomyProvider implements EconomyProvider {
    private final String symbol;
    private final DecimalFormat format;

    public DisabledEconomyProvider(String symbol, int decimals) {
        this.symbol = symbol;
        StringBuilder pattern = new StringBuilder("#,##0");
        if (decimals > 0) {
            pattern.append(".");
            pattern.append("0".repeat(decimals));
        }
        this.format = new DecimalFormat(pattern.toString());
    }

    @Override
    public String name() {
        return "Disabled";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return false;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return false;
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        return false;
    }

    @Override
    public String format(double amount) {
        return symbol + format.format(amount);
    }
}


