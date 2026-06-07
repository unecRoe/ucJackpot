package com.unecroe.ucjackpot.config;

public record StorageSettings(
        String type,
        String sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean useSsl,
        int poolSize
) {
}


