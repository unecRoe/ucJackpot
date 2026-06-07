package com.unecroe.ucjackpot.config;

import java.util.List;
import java.util.Set;

public record PluginSettings(
        String defaultLocale,
        String fallbackLocale,
        int tickIntervalSeconds,
        boolean autoStartRounds,
        boolean saveActiveEntries,
        List<String> commandAliases,
        boolean metricsEnabled,
        boolean drawTitlesEnabled,
        boolean drawTitlesPlayerToggle,
        int drawAnimationSeconds,
        String chanceUpdateMode,
        boolean chanceUpdateIncludeActor,
        boolean vaultEnabled,
        String currencySymbol,
        int currencyDecimals,
        boolean debug,
        Set<String> debugCategories,
        Set<String> blockedWorlds,
        Set<String> blockedMaterials,
        boolean requireItemValueRule,
        boolean auditDatabase,
        boolean auditFile,
        int auditKeepDays,
        StorageSettings storage
) {
    public boolean debugCategory(String category) {
        return debug && debugCategories.contains(category.toLowerCase());
    }
}


