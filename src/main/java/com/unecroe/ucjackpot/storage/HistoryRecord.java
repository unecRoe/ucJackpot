package com.unecroe.ucjackpot.storage;

public record HistoryRecord(
        String jackpotId,
        String winnerName,
        double value,
        long createdAt
) {
}


