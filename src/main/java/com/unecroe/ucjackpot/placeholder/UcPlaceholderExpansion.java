package com.unecroe.ucjackpot.placeholder;

import com.unecroe.ucjackpot.economy.EconomyService;
import com.unecroe.ucjackpot.jackpot.JackpotRound;
import com.unecroe.ucjackpot.jackpot.JackpotService;
import com.unecroe.ucjackpot.util.TimeUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class UcPlaceholderExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final JackpotService jackpot;
    private final EconomyService economy;

    public UcPlaceholderExpansion(JavaPlugin plugin, JackpotService jackpot, EconomyService economy) {
        this.plugin = plugin;
        this.jackpot = jackpot;
        this.economy = economy;
    }

    @Override
    public String getIdentifier() {
        return "ucjackpot";
    }

    @Override
    public String getAuthor() {
        return "unecroe";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        JackpotRound round = jackpot.primaryRound();
        String key = params.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "pot", "pot_value" -> economy.provider().format(round.totalValue());
            case "money_pot" -> economy.provider().format(round.moneyPot());
            case "item_pot" -> economy.provider().format(round.itemValue());
            case "entries" -> String.valueOf(round.entries().size());
            case "players" -> String.valueOf(round.participantCount());
            case "time_left" -> TimeUtil.compact(round.secondsLeft());
            case "status" -> round.running() ? "LIVE" : "STOPPED";
            case "player_entries" -> player == null ? "0" : String.valueOf(jackpot.primaryPlayerEntries(player.getUniqueId()));
            case "player_chance" -> player == null ? "0.00" : String.format(Locale.US, "%.2f", jackpot.primaryChance(player.getUniqueId()));
            case "last_winner" -> "-";
            default -> null;
        };
    }
}


