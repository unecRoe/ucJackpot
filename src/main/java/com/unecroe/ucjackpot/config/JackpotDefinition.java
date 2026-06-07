package com.unecroe.ucjackpot.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record JackpotDefinition(
        String id,
        boolean enabled,
        String displayName,
        JackpotMode mode,
        boolean moneyEnabled,
        double minMoneyEntry,
        double maxMoneyEntry,
        double defaultMoneyEntry,
        List<Double> quickMoneyAmounts,
        double taxPercent,
        boolean itemEnabled,
        double minItemValue,
        int minItemsPerEntry,
        int maxItemsPerEntry,
        boolean acceptEnchantedItems,
        boolean acceptCustomModelData,
        Map<String, Double> materialValues,
        int durationSeconds,
        int minPlayers,
        double minTotalValue,
        int maxEntriesPerPlayer,
        int cooldownSeconds,
        boolean autoDraw,
        int startDelaySeconds,
        boolean luckDampenerEnabled,
        int recentWinWindowHours,
        double recentWinnerMultiplier,
        boolean comboEnabled,
        double comboStepPercent,
        double comboMaxPercent,
        String seasonId,
        List<String> seasonRewardCommands,
        boolean winnerTakesItems,
        boolean consolationEnabled,
        int consolationMinEntries,
        double consolationMoneyPercent,
        List<String> consolationCommands,
        boolean rareBonusEnabled,
        double rareBonusChancePercent,
        List<String> rareBonusCommands,
        boolean ticketEnabled,
        String ticketMaterial,
        String ticketNameContains,
        double ticketEntryValue,
        boolean specialItemProtection,
        Set<Integer> countdownBroadcasts,
        Set<Double> milestoneValues
) {
    public boolean acceptsMoney() {
        return enabled && moneyEnabled && (mode == JackpotMode.MONEY || mode == JackpotMode.HYBRID);
    }

    public boolean acceptsItems() {
        return enabled && itemEnabled && (mode == JackpotMode.ITEM || mode == JackpotMode.HYBRID);
    }
}


