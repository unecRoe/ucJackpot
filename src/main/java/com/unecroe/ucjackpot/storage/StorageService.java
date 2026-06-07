package com.unecroe.ucjackpot.storage;

import com.unecroe.ucjackpot.config.PluginSettings;
import com.unecroe.ucjackpot.config.StorageSettings;
import com.unecroe.ucjackpot.debug.DebugLogger;
import com.unecroe.ucjackpot.item.ItemSerializer;
import com.unecroe.ucjackpot.jackpot.DrawResult;
import com.unecroe.ucjackpot.jackpot.JackpotEntry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class StorageService {
    private final JavaPlugin plugin;
    private final DebugLogger debug;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ucJackpot-Storage");
        thread.setDaemon(true);
        return thread;
    });
    private HikariDataSource dataSource;
    private boolean mysql;

    public StorageService(JavaPlugin plugin, DebugLogger debug) {
        this.plugin = plugin;
        this.debug = debug;
    }

    public void start(PluginSettings settings) {
        close();
        StorageSettings storage = settings.storage();
        HikariConfig config = new HikariConfig();
        String type = storage.type().toLowerCase(Locale.ROOT);
        mysql = type.equals("mysql") || type.equals("mariadb");
        if (mysql) {
            String driver = type.equals("mariadb") ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
            String prefix = type.equals("mariadb") ? "jdbc:mariadb://" : "jdbc:mysql://";
            config.setDriverClassName(driver);
            config.setJdbcUrl(prefix + storage.host() + ":" + storage.port() + "/" + storage.database()
                    + "?useSSL=" + storage.useSsl() + "&autoReconnect=true");
            config.setUsername(storage.username());
            config.setPassword(storage.password());
            config.setMaximumPoolSize(Math.max(1, storage.poolSize()));
        } else {
            File file = new File(plugin.getDataFolder(), storage.sqliteFile());
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            config.setMaximumPoolSize(1);
        }
        config.setPoolName("ucJackpotPool");
        dataSource = new HikariDataSource(config);
        initTables();
        debug.log("database", "Storage started type=" + type + " mysql=" + mysql);
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
            debug.log("database", "Storage connection pool closed.");
        }
    }

    public void shutdown() {
        close();
        executor.shutdownNow();
    }

    private void initTables() {
        String idType = mysql ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        executeSync(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ucjackpot_audit_log ("
                        + "id " + idType + ", event_type VARCHAR(64), actor_uuid VARCHAR(36), actor_name VARCHAR(64),"
                        + "jackpot_id VARCHAR(64), message TEXT, payload TEXT, created_at BIGINT)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ucjackpot_history ("
                        + "id " + idType + ", jackpot_id VARCHAR(64), winner_uuid VARCHAR(36), winner_name VARCHAR(64),"
                        + "entry_value DOUBLE, created_at BIGINT)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ucjackpot_mailbox ("
                        + "id " + idType + ", player_uuid VARCHAR(36), player_name VARCHAR(64), encoded_item LONGTEXT,"
                        + "reason TEXT, created_at BIGINT)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ucjackpot_active_entries ("
                        + "entry_id VARCHAR(36) PRIMARY KEY, jackpot_id VARCHAR(64), player_uuid VARCHAR(36),"
                        + "player_name VARCHAR(64), entry_type VARCHAR(16), entry_value DOUBLE, money_amount DOUBLE,"
                        + "encoded_item LONGTEXT, created_at BIGINT)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ucjackpot_player_settings ("
                        + "player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64), favorite_amount DOUBLE,"
                        + "updated_at BIGINT)");
                try {
                    statement.executeUpdate("ALTER TABLE ucjackpot_player_settings ADD COLUMN title_enabled BOOLEAN DEFAULT 1");
                } catch (SQLException ignored) {
                    // Existing installations may already have the column.
                }
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ucjackpot_draws ("
                        + "draw_id VARCHAR(36) PRIMARY KEY, jackpot_id VARCHAR(64), winner_uuid VARCHAR(36),"
                        + "winner_name VARCHAR(64), seed VARCHAR(128), hash VARCHAR(128), money_prize DOUBLE,"
                        + "item_value DOUBLE, item_count INT, total_value DOUBLE, entry_count INT, created_at BIGINT)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ucjackpot_entry_history ("
                        + "entry_id VARCHAR(36) PRIMARY KEY, draw_id VARCHAR(36), jackpot_id VARCHAR(64),"
                        + "player_uuid VARCHAR(36), player_name VARCHAR(64), entry_type VARCHAR(16),"
                        + "entry_value DOUBLE, money_amount DOUBLE, encoded_item LONGTEXT, created_at BIGINT)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS ucjackpot_season_stats ("
                        + "season_id VARCHAR(64), player_uuid VARCHAR(36), player_name VARCHAR(64),"
                        + "entries INT, wins INT, value_in DOUBLE, value_won DOUBLE, updated_at BIGINT,"
                        + "PRIMARY KEY(season_id, player_uuid))");
            }
        });
        debug.log("database", "Storage tables ensured.");
    }

    private void executeSync(SqlConsumer consumer) {
        try (Connection connection = dataSource.getConnection()) {
            consumer.accept(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Database operation failed", exception);
        }
    }

    private CompletableFuture<Void> runAsync(SqlConsumer consumer) {
        return CompletableFuture.runAsync(() -> executeSync(consumer), executor)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        debug.warn("database", "Async database operation failed", unwrap(throwable));
                    }
                });
    }

    private <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        debug.warn("database", "Async database query failed", unwrap(throwable));
                    }
                });
    }

    private Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
    }

    public CompletableFuture<Void> audit(String type, UUID actorUuid, String actorName,
                                         String jackpotId, String message, String payload) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ucjackpot_audit_log(event_type, actor_uuid, actor_name, jackpot_id, message, payload, created_at)"
                            + " VALUES(?,?,?,?,?,?,?)")) {
                statement.setString(1, type);
                statement.setString(2, actorUuid == null ? null : actorUuid.toString());
                statement.setString(3, actorName);
                statement.setString(4, jackpotId);
                statement.setString(5, message);
                statement.setString(6, payload);
                statement.setLong(7, System.currentTimeMillis());
                statement.executeUpdate();
                debug.log("audit", "Audit stored type=" + type + " actor=" + actorName + " jackpot=" + jackpotId);
            }
        });
    }

    public CompletableFuture<Void> recordHistory(String jackpotId, UUID winnerUuid, String winnerName, double value) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ucjackpot_history(jackpot_id, winner_uuid, winner_name, entry_value, created_at)"
                            + " VALUES(?,?,?,?,?)")) {
                statement.setString(1, jackpotId);
                statement.setString(2, winnerUuid.toString());
                statement.setString(3, winnerName);
                statement.setDouble(4, value);
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
                debug.log("database", "History stored jackpot=" + jackpotId + " winner=" + winnerName + " value=" + value);
            }
        });
    }

    public CompletableFuture<Void> saveFavoriteAmount(UUID playerUuid, String playerName, double amount) {
        return runAsync(connection -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ucjackpot_player_settings SET player_name = ?, favorite_amount = ?, updated_at = ?"
                            + " WHERE player_uuid = ?")) {
                update.setString(1, playerName);
                update.setDouble(2, amount);
                update.setLong(3, now);
                update.setString(4, playerUuid.toString());
                if (update.executeUpdate() > 0) {
                    debug.log("database", "Favorite amount updated player=" + playerName + " amount=" + amount);
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ucjackpot_player_settings(player_uuid, player_name, favorite_amount, updated_at)"
                            + " VALUES(?,?,?,?)")) {
                insert.setString(1, playerUuid.toString());
                insert.setString(2, playerName);
                insert.setDouble(3, amount);
                insert.setLong(4, now);
                insert.executeUpdate();
                debug.log("database", "Favorite amount inserted player=" + playerName + " amount=" + amount);
            }
        });
    }

    public CompletableFuture<Double> favoriteAmount(UUID playerUuid) {
        return supplyAsync(() -> {
            final Double[] amount = {null};
            executeSync(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT favorite_amount FROM ucjackpot_player_settings WHERE player_uuid = ?")) {
                    statement.setString(1, playerUuid.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            amount[0] = result.getDouble("favorite_amount");
                        }
                    }
                }
            });
            debug.log("database", "Favorite amount loaded player=" + playerUuid + " present=" + (amount[0] != null));
            return amount[0];
        });
    }

    public CompletableFuture<Void> saveTitleNotifications(UUID playerUuid, String playerName, boolean enabled) {
        return runAsync(connection -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE ucjackpot_player_settings SET player_name = ?, title_enabled = ?, updated_at = ?"
                            + " WHERE player_uuid = ?")) {
                update.setString(1, playerName);
                update.setBoolean(2, enabled);
                update.setLong(3, now);
                update.setString(4, playerUuid.toString());
                if (update.executeUpdate() > 0) {
                    debug.log("database", "Title notifications updated player=" + playerName + " enabled=" + enabled);
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ucjackpot_player_settings(player_uuid, player_name, favorite_amount, title_enabled, updated_at)"
                            + " VALUES(?,?,?,?,?)")) {
                insert.setString(1, playerUuid.toString());
                insert.setString(2, playerName);
                insert.setDouble(3, 0.0);
                insert.setBoolean(4, enabled);
                insert.setLong(5, now);
                insert.executeUpdate();
                debug.log("database", "Title notifications inserted player=" + playerName + " enabled=" + enabled);
            }
        });
    }

    public CompletableFuture<Boolean> titleNotifications(UUID playerUuid) {
        return supplyAsync(() -> {
            final boolean[] enabled = {true};
            executeSync(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT title_enabled FROM ucjackpot_player_settings WHERE player_uuid = ?")) {
                    statement.setString(1, playerUuid.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            enabled[0] = result.getBoolean("title_enabled");
                        }
                    }
                }
            });
            debug.log("database", "Title notifications loaded player=" + playerUuid + " enabled=" + enabled[0]);
            return enabled[0];
        });
    }

    public CompletableFuture<Map<UUID, Boolean>> titleNotifications(List<UUID> playerUuids) {
        return supplyAsync(() -> {
            Map<UUID, Boolean> settings = new LinkedHashMap<>();
            if (playerUuids.isEmpty()) {
                return settings;
            }
            executeSync(connection -> {
                String placeholders = String.join(",", java.util.Collections.nCopies(playerUuids.size(), "?"));
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT player_uuid, title_enabled FROM ucjackpot_player_settings"
                                + " WHERE player_uuid IN (" + placeholders + ")")) {
                    for (int index = 0; index < playerUuids.size(); index++) {
                        statement.setString(index + 1, playerUuids.get(index).toString());
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            settings.put(UUID.fromString(result.getString("player_uuid")),
                                    result.getBoolean("title_enabled"));
                        }
                    }
                }
            });
            debug.log("database", "Title notification settings loaded count=" + settings.size());
            return settings;
        });
    }

    public CompletableFuture<List<HistoryRecord>> recentHistory(int limit) {
        return supplyAsync(() -> {
            List<HistoryRecord> records = new ArrayList<>();
            executeSync(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT draw_id, jackpot_id, winner_name, total_value, created_at FROM ucjackpot_draws"
                                + " ORDER BY created_at DESC LIMIT ?")) {
                    statement.setInt(1, Math.max(1, limit));
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            records.add(new HistoryRecord(
                                    result.getString("draw_id"),
                                    result.getString("jackpot_id"),
                                    result.getString("winner_name"),
                                    result.getDouble("total_value"),
                                    result.getLong("created_at")
                            ));
                        }
                    }
                }
                if (!records.isEmpty()) {
                    return;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT jackpot_id, winner_name, entry_value, created_at FROM ucjackpot_history"
                                + " ORDER BY created_at DESC LIMIT ?")) {
                    statement.setInt(1, Math.max(1, limit));
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            records.add(new HistoryRecord(
                                    "",
                                    result.getString("jackpot_id"),
                                    result.getString("winner_name"),
                                    result.getDouble("entry_value"),
                                    result.getLong("created_at")
                            ));
                        }
                    }
                }
            });
            debug.log("database", "Recent history loaded count=" + records.size() + " limit=" + limit);
            return records;
        });
    }

    public CompletableFuture<List<DrawEntryRecord>> drawEntries(String drawId) {
        return supplyAsync(() -> {
            List<DrawEntryRecord> records = new ArrayList<>();
            if (drawId == null || drawId.isBlank()) {
                return records;
            }
            executeSync(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT entry_id, draw_id, jackpot_id, player_uuid, player_name, entry_type,"
                                + "entry_value, money_amount, encoded_item, created_at"
                                + " FROM ucjackpot_entry_history WHERE draw_id = ? ORDER BY created_at ASC")) {
                    statement.setString(1, drawId);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            records.add(new DrawEntryRecord(
                                    result.getString("entry_id"),
                                    result.getString("draw_id"),
                                    result.getString("jackpot_id"),
                                    result.getString("player_uuid"),
                                    result.getString("player_name"),
                                    result.getString("entry_type"),
                                    result.getDouble("entry_value"),
                                    result.getDouble("money_amount"),
                                    result.getString("encoded_item"),
                                    result.getLong("created_at")
                            ));
                        }
                    }
                }
            });
            debug.log("database", "Draw entry history loaded draw=" + drawId + " count=" + records.size());
            return records;
        });
    }

    public CompletableFuture<Void> recordDetailedDraw(DrawResult result, List<JackpotEntry> entries) {
        return runAsync(connection -> {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement draw = connection.prepareStatement(
                        "INSERT INTO ucjackpot_draws(draw_id, jackpot_id, winner_uuid, winner_name, seed, hash,"
                                + "money_prize, item_value, item_count, total_value, entry_count, created_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    draw.setString(1, result.drawId());
                    draw.setString(2, result.jackpotId());
                    draw.setString(3, result.winnerUuid().toString());
                    draw.setString(4, result.winnerName());
                    draw.setString(5, result.seed());
                    draw.setString(6, result.hash());
                    draw.setDouble(7, result.moneyPrize());
                    draw.setDouble(8, result.itemValue());
                    draw.setInt(9, result.itemCount());
                    draw.setDouble(10, result.totalValue());
                    draw.setInt(11, result.entryCount());
                    draw.setLong(12, result.createdAt());
                    draw.executeUpdate();
                }
                try (PreparedStatement entry = connection.prepareStatement(
                        "INSERT INTO ucjackpot_entry_history(entry_id, draw_id, jackpot_id, player_uuid, player_name,"
                                + "entry_type, entry_value, money_amount, encoded_item, created_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    for (JackpotEntry jackpotEntry : entries) {
                        entry.setString(1, jackpotEntry.id().toString());
                        entry.setString(2, result.drawId());
                        entry.setString(3, jackpotEntry.jackpotId());
                        entry.setString(4, jackpotEntry.playerUuid().toString());
                        entry.setString(5, jackpotEntry.playerName());
                        entry.setString(6, jackpotEntry.type().name());
                        entry.setDouble(7, jackpotEntry.value());
                        entry.setDouble(8, jackpotEntry.moneyAmount());
                        entry.setString(9, jackpotEntry.item() == null ? null : ItemSerializer.encode(jackpotEntry.item()));
                        entry.setLong(10, jackpotEntry.createdAt());
                        entry.addBatch();
                    }
                    entry.executeBatch();
                }
                connection.commit();
                debug.log("database", "Detailed draw stored draw=" + result.drawId() + " jackpot=" + result.jackpotId()
                        + " entries=" + entries.size());
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public CompletableFuture<Void> updateSeasonStats(String seasonId, List<JackpotEntry> entries, DrawResult result) {
        return runAsync(connection -> {
            Map<UUID, SeasonAccumulator> grouped = new LinkedHashMap<>();
            for (JackpotEntry entry : entries) {
                grouped.computeIfAbsent(entry.playerUuid(), ignored -> new SeasonAccumulator(entry.playerName()))
                        .add(entry.value());
            }
            SeasonAccumulator winner = grouped.computeIfAbsent(result.winnerUuid(), ignored -> new SeasonAccumulator(result.winnerName()));
            winner.win(result.totalValue());
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, SeasonAccumulator> entry : grouped.entrySet()) {
                upsertSeasonStat(connection, seasonId, entry.getKey(), entry.getValue(), now);
            }
            debug.log("season", "Season stats updated season=" + seasonId + " players=" + grouped.size()
                    + " draw=" + result.drawId());
        });
    }

    public CompletableFuture<List<SeasonStatRecord>> topSeason(String seasonId, int limit) {
        return supplyAsync(() -> {
            List<SeasonStatRecord> records = new ArrayList<>();
            executeSync(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT season_id, player_uuid, player_name, entries, wins, value_in, value_won"
                                + " FROM ucjackpot_season_stats WHERE season_id = ?"
                                + " ORDER BY wins DESC, value_won DESC, value_in DESC LIMIT ?")) {
                    statement.setString(1, seasonId);
                    statement.setInt(2, Math.max(1, limit));
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            records.add(new SeasonStatRecord(
                                    result.getString("season_id"),
                                    result.getString("player_uuid"),
                                    result.getString("player_name"),
                                    result.getInt("entries"),
                                    result.getInt("wins"),
                                    result.getDouble("value_in"),
                                    result.getDouble("value_won")
                            ));
                        }
                    }
                }
            });
            debug.log("season", "Season top loaded season=" + seasonId + " count=" + records.size() + " limit=" + limit);
            return records;
        });
    }

    public CompletableFuture<String> lastWinner() {
        return recentHistory(1).thenApply(records -> records.isEmpty() ? "-" : records.getFirst().winnerName());
    }

    public CompletableFuture<Void> addMailboxItem(UUID playerUuid, String playerName, String encodedItem, String reason) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ucjackpot_mailbox(player_uuid, player_name, encoded_item, reason, created_at)"
                            + " VALUES(?,?,?,?,?)")) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, playerName);
                statement.setString(3, encodedItem);
                statement.setString(4, reason);
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
                debug.log("database", "Mailbox item queued player=" + playerName + " reason=" + reason);
            }
        });
    }

    public CompletableFuture<List<MailboxRecord>> claimMailbox(UUID playerUuid) {
        return supplyAsync(() -> {
            List<MailboxRecord> records = new ArrayList<>();
            executeSync(connection -> {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement select = connection.prepareStatement(
                            "SELECT id, player_uuid, player_name, encoded_item, reason, created_at"
                                    + " FROM ucjackpot_mailbox WHERE player_uuid = ? ORDER BY created_at ASC")) {
                        select.setString(1, playerUuid.toString());
                        try (ResultSet result = select.executeQuery()) {
                            while (result.next()) {
                                records.add(new MailboxRecord(
                                        result.getLong("id"),
                                        result.getString("player_uuid"),
                                        result.getString("player_name"),
                                        result.getString("encoded_item"),
                                        result.getString("reason"),
                                        result.getLong("created_at")
                                ));
                            }
                        }
                    }
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM ucjackpot_mailbox WHERE player_uuid = ?")) {
                        delete.setString(1, playerUuid.toString());
                        delete.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            });
            debug.log("database", "Mailbox claimed player=" + playerUuid + " count=" + records.size());
            return records;
        });
    }

    public CompletableFuture<Void> saveActiveEntry(ActiveEntryRecord record) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ucjackpot_active_entries(entry_id, jackpot_id, player_uuid, player_name, entry_type,"
                            + "entry_value, money_amount, encoded_item, created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, record.entryId());
                statement.setString(2, record.jackpotId());
                statement.setString(3, record.playerUuid());
                statement.setString(4, record.playerName());
                statement.setString(5, record.type());
                statement.setDouble(6, record.entryValue());
                statement.setDouble(7, record.moneyAmount());
                statement.setString(8, record.encodedItem());
                statement.setLong(9, record.createdAt());
                statement.executeUpdate();
                debug.log("database", "Active entry saved jackpot=" + record.jackpotId() + " entry=" + record.entryId()
                        + " type=" + record.type());
            }
        });
    }

    public CompletableFuture<Void> clearActiveEntries(String jackpotId) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM ucjackpot_active_entries WHERE jackpot_id = ?")) {
                statement.setString(1, jackpotId);
                int deleted = statement.executeUpdate();
                debug.log("database", "Active entries cleared jackpot=" + jackpotId + " count=" + deleted);
            }
        });
    }

    public CompletableFuture<Void> clearActiveEntries(String jackpotId, UUID playerUuid) {
        return runAsync(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM ucjackpot_active_entries WHERE jackpot_id = ? AND player_uuid = ?")) {
                statement.setString(1, jackpotId);
                statement.setString(2, playerUuid.toString());
                int deleted = statement.executeUpdate();
                debug.log("database", "Active entries cleared jackpot=" + jackpotId
                        + " player=" + playerUuid + " count=" + deleted);
            }
        });
    }

    public CompletableFuture<List<ActiveEntryRecord>> loadActiveEntries() {
        return supplyAsync(() -> {
            List<ActiveEntryRecord> records = new ArrayList<>();
            executeSync(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT entry_id, jackpot_id, player_uuid, player_name, entry_type, entry_value,"
                                + "money_amount, encoded_item, created_at FROM ucjackpot_active_entries");
                     ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        records.add(new ActiveEntryRecord(
                                result.getString("entry_id"),
                                result.getString("jackpot_id"),
                                result.getString("player_uuid"),
                                result.getString("player_name"),
                                result.getString("entry_type"),
                                result.getDouble("entry_value"),
                                result.getDouble("money_amount"),
                                result.getString("encoded_item"),
                                result.getLong("created_at")
                        ));
                    }
                }
            });
            debug.log("database", "Active entries loaded count=" + records.size());
            return records;
        });
    }

    private void upsertSeasonStat(Connection connection, String seasonId, UUID playerUuid,
                                  SeasonAccumulator accumulator, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE ucjackpot_season_stats SET player_name = ?, entries = entries + ?, wins = wins + ?,"
                        + " value_in = value_in + ?, value_won = value_won + ?, updated_at = ?"
                        + " WHERE season_id = ? AND player_uuid = ?")) {
            update.setString(1, accumulator.playerName);
            update.setInt(2, accumulator.entries);
            update.setInt(3, accumulator.wins);
            update.setDouble(4, accumulator.valueIn);
            update.setDouble(5, accumulator.valueWon);
            update.setLong(6, now);
            update.setString(7, seasonId);
            update.setString(8, playerUuid.toString());
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ucjackpot_season_stats(season_id, player_uuid, player_name, entries, wins,"
                        + " value_in, value_won, updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
            insert.setString(1, seasonId);
            insert.setString(2, playerUuid.toString());
            insert.setString(3, accumulator.playerName);
            insert.setInt(4, accumulator.entries);
            insert.setInt(5, accumulator.wins);
            insert.setDouble(6, accumulator.valueIn);
            insert.setDouble(7, accumulator.valueWon);
            insert.setLong(8, now);
            insert.executeUpdate();
        }
    }

    public CompletableFuture<Boolean> healthCheck() {
        return supplyAsync(() -> {
            final boolean[] ok = {true};
            executeSync(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                    statement.execute("SELECT COUNT(*) FROM ucjackpot_audit_log");
                    statement.execute("SELECT COUNT(*) FROM ucjackpot_history");
                    statement.execute("SELECT COUNT(*) FROM ucjackpot_mailbox");
                    statement.execute("SELECT COUNT(*) FROM ucjackpot_active_entries");
                    statement.execute("SELECT COUNT(*) FROM ucjackpot_player_settings");
                    statement.execute("SELECT COUNT(*) FROM ucjackpot_draws");
                    statement.execute("SELECT COUNT(*) FROM ucjackpot_entry_history");
                    statement.execute("SELECT COUNT(*) FROM ucjackpot_season_stats");
                } catch (SQLException exception) {
                    ok[0] = false;
                }
            });
            debug.log("database", "Storage health check result=" + ok[0]);
            return ok[0];
        });
    }

    @FunctionalInterface
    private interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }

    private static final class SeasonAccumulator {
        private final String playerName;
        private int entries;
        private int wins;
        private double valueIn;
        private double valueWon;

        private SeasonAccumulator(String playerName) {
            this.playerName = playerName;
        }

        private void add(double value) {
            entries++;
            valueIn += value;
        }

        private void win(double value) {
            wins++;
            valueWon += value;
        }
    }
}


