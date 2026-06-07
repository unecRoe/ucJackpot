package com.unecroe.ucjackpot.audit;

import com.unecroe.ucjackpot.config.PluginSettings;
import com.unecroe.ucjackpot.debug.DebugLogger;
import com.unecroe.ucjackpot.storage.StorageService;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class AuditService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final JavaPlugin plugin;
    private final StorageService storage;
    private final DebugLogger debug;
    private PluginSettings settings;

    public AuditService(JavaPlugin plugin, StorageService storage, DebugLogger debug) {
        this.plugin = plugin;
        this.storage = storage;
        this.debug = debug;
    }

    public void settings(PluginSettings settings) {
        this.settings = settings;
    }

    public void log(AuditEventType type, UUID actorUuid, String actorName, String jackpotId, String message, String payload) {
        if (settings == null) {
            return;
        }
        if (settings.auditDatabase()) {
            storage.audit(type.name(), actorUuid, actorName, jackpotId, message, payload);
        }
        if (settings.auditFile()) {
            writeFile(type, actorUuid, actorName, jackpotId, message, payload);
        }
    }

    private void writeFile(AuditEventType type, UUID actorUuid, String actorName, String jackpotId, String message, String payload) {
        File folder = new File(plugin.getDataFolder(), "logs");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Unable to create audit log folder.");
            return;
        }
        File file = new File(folder, "audit-" + DATE.format(LocalDate.now()) + ".log");
        long now = System.currentTimeMillis();
        String line = DATE_TIME.format(OffsetDateTime.now())
                + " | epoch_ms=" + now
                + " | " + type
                + " | actor=" + actorName + "(" + actorUuid + ")"
                + " | jackpot=" + jackpotId
                + " | " + message
                + " | " + payload
                + System.lineSeparator();
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            debug.log("audit", "Audit file stored type=" + type + " actor=" + actorName + " jackpot=" + jackpotId);
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to write audit log: " + exception.getMessage());
        }
    }
}


