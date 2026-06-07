package com.unecroe.ucjackpot.storage;

public record MailboxRecord(
        long id,
        String playerUuid,
        String playerName,
        String encodedItem,
        String reason,
        long createdAt
) {
}


