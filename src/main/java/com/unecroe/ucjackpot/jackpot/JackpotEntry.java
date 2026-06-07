package com.unecroe.ucjackpot.jackpot;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public record JackpotEntry(
        UUID id,
        String jackpotId,
        UUID playerUuid,
        String playerName,
        EntryType type,
        double value,
        double moneyAmount,
        ItemStack item,
        long createdAt
) {
}


