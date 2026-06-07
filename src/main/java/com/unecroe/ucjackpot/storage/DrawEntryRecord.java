package com.unecroe.ucjackpot.storage;

public record DrawEntryRecord(
        String entryId,
        String drawId,
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
