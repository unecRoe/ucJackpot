package com.unecroe.ucjackpot.storage;

public record ActiveEntryRecord(
        String entryId,
        String jackpotId,
        String playerUuid,
        String playerName,
        String type,
        double entryValue,
        double moneyAmount,
        String encodedItem,
        long createdAt
) {
}


