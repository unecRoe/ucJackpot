package com.unecroe.ucjackpot.storage;

public record SeasonStatRecord(
        String seasonId,
        String playerUuid,
        String playerName,
        int entries,
        int wins,
        double valueIn,
        double valueWon
) {
}


