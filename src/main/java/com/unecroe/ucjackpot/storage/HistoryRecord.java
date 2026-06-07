package com.unecroe.ucjackpot.storage;

public record HistoryRecord(
        String drawId,
        String jackpotId,
        String winnerName,
        double value,
        long createdAt
) {
}


