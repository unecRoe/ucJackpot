package com.unecroe.ucjackpot.jackpot;

import java.util.UUID;

public record DrawResult(
        String drawId,
        String jackpotId,
        UUID winnerUuid,
        String winnerName,
        String seed,
        String hash,
        double moneyPrize,
        double itemValue,
        int itemCount,
        double totalValue,
        int entryCount,
        long createdAt
) {
}


