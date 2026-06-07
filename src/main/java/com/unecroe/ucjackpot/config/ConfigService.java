package com.unecroe.ucjackpot.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ConfigService {
    private final JavaPlugin plugin;
    private PluginSettings settings;
    private final Map<String, JackpotDefinition> jackpots = new LinkedHashMap<>();

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        saveDefault("jackpots/default.yml");
        saveDefault("jackpots/low.yml");
        saveDefault("jackpots/high.yml");
        saveDefault("jackpots/item.yml");
        saveDefault("jackpots/event.yml");
        this.settings = readSettings(plugin.getConfig());
        this.jackpots.clear();
        File folder = new File(plugin.getDataFolder(), "jackpots");
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                YamlConfiguration yaml = loadYaml(file);
                if (yaml == null) {
                    continue;
                }
                JackpotDefinition definition = readJackpot(yaml);
                jackpots.put(definition.id().toLowerCase(Locale.ROOT), definition);
            }
        }
        if (jackpots.isEmpty()) {
            plugin.getLogger().severe("No valid jackpot room files were loaded. Using bundled jackpots/default.yml.");
            JackpotDefinition definition = readJackpot(bundledJackpot("jackpots/default.yml"));
            jackpots.put(definition.id().toLowerCase(Locale.ROOT), definition);
        }
    }

    public PluginSettings settings() {
        return settings;
    }

    public Map<String, JackpotDefinition> jackpots() {
        return Map.copyOf(jackpots);
    }

    public JackpotDefinition primaryJackpot() {
        return jackpots.values().stream().findFirst().orElseThrow();
    }

    public JackpotDefinition jackpot(String id) {
        return jackpots.get(id.toLowerCase(Locale.ROOT));
    }

    private PluginSettings readSettings(FileConfiguration config) {
        StorageSettings storage = new StorageSettings(
                config.getString("storage.type", "sqlite"),
                config.getString("storage.sqlite-file", "data/ucjackpot.db"),
                config.getString("storage.mysql.host", "127.0.0.1"),
                config.getInt("storage.mysql.port", 3306),
                config.getString("storage.mysql.database", "ucjackpot"),
                config.getString("storage.mysql.username", "root"),
                config.getString("storage.mysql.password", ""),
                config.getBoolean("storage.mysql.use-ssl", false),
                config.getInt("storage.mysql.pool-size", 8)
        );
        return new PluginSettings(
                config.getString("settings.default-locale", "en"),
                config.getString("settings.fallback-locale", "en"),
                Math.max(1, config.getInt("settings.tick-interval-seconds", 1)),
                config.getBoolean("settings.auto-start-rounds", true),
                config.getBoolean("settings.save-active-entries", true),
                readCommandAliases(config),
                config.getBoolean("metrics.enabled", true),
                config.getBoolean("economy.vault.enabled", true),
                config.getString("economy.formatting.symbol", "$"),
                config.getInt("economy.formatting.decimals", 2),
                config.getBoolean("logging.debug", false),
                enabledKeys(config.getConfigurationSection("logging.categories")),
                lowerSet(config.getStringList("security.blocked-worlds").stream().toList()),
                upperSet(config.getStringList("security.blocked-materials").stream().toList()),
                config.getBoolean("security.require-item-value-rule", false),
                config.getBoolean("logging.audit.database", true),
                config.getBoolean("logging.audit.file", true),
                config.getInt("logging.audit.keep-days", 30),
                storage
        );
    }

    private JackpotDefinition readJackpot(FileConfiguration config) {
        ConfigurationSection values = config.getConfigurationSection("items.material-values");
        Map<String, Double> materialValues = new LinkedHashMap<>();
        if (values != null) {
            for (String key : values.getKeys(false)) {
                materialValues.put(key.toUpperCase(Locale.ROOT), values.getDouble(key));
            }
        }
        return new JackpotDefinition(
                config.getString("id", "default"),
                config.getBoolean("enabled", true),
                config.getString("display-name", "General Room"),
                JackpotMode.parse(config.getString("mode", "hybrid")),
                config.getBoolean("currency.enabled", true),
                config.getDouble("currency.min-entry", 100.0),
                config.getDouble("currency.max-entry", 1000000.0),
                config.getDouble("currency.default-entry", 1000.0),
                readQuickMoneyAmounts(config),
                config.getDouble("currency.tax-percent", 0.0),
                config.getBoolean("items.enabled", true),
                config.getDouble("items.min-value", 50.0),
                config.getInt("items.min-items-per-entry", 1),
                config.getInt("items.max-items-per-entry", 1),
                config.getBoolean("items.accept-enchanted-items", true),
                config.getBoolean("items.accept-custom-model-data", true),
                materialValues,
                config.getInt("round.duration-seconds", 300),
                config.getInt("round.min-players", 2),
                config.getDouble("round.min-total-value", 100.0),
                config.getInt("round.max-entries-per-player", 25),
                config.getInt("round.cooldown-seconds", 10),
                config.getBoolean("round.auto-draw", true),
                config.getInt("round.start-delay-seconds", 10),
                config.getBoolean("odds.luck-streak-dampener.enabled", true),
                config.getInt("odds.luck-streak-dampener.recent-win-window-hours", 24),
                config.getDouble("odds.luck-streak-dampener.multiplier-after-win", 0.75),
                config.getBoolean("engagement.combo.enabled", true),
                config.getDouble("engagement.combo.step-percent", 1.0),
                config.getDouble("engagement.combo.max-percent", 5.0),
                config.getString("season.id", "season-1"),
                config.getStringList("season.reward-commands"),
                config.getBoolean("rewards.winner-takes-items", true),
                config.getBoolean("rewards.consolation.enabled", true),
                config.getInt("rewards.consolation.min-entries", 3),
                config.getDouble("rewards.consolation.money-percent", 1.5),
                config.getStringList("rewards.consolation.commands"),
                config.getBoolean("rewards.rare-bonus.enabled", true),
                config.getDouble("rewards.rare-bonus.chance-percent", 2.0),
                config.getStringList("rewards.rare-bonus.commands"),
                config.getBoolean("tickets.enabled", true),
                config.getString("tickets.material", "PAPER"),
                config.getString("tickets.name-contains", "Jackpot Ticket"),
                config.getDouble("tickets.entry-value", 1000.0),
                config.getBoolean("items.special-protection", true),
                new TreeSet<>(config.getIntegerList("broadcasts.countdown-seconds")),
                new TreeSet<>(config.getDoubleList("broadcasts.milestone-values"))
        );
    }

    private List<Double> readQuickMoneyAmounts(FileConfiguration config) {
        List<Double> amounts = new ArrayList<>();
        for (Object value : config.getList("currency.quick-amounts", List.of(100.0, 1000.0, 5000.0, 10000.0, 50000.0))) {
            if (value instanceof Number number && number.doubleValue() > 0.0) {
                amounts.add(number.doubleValue());
                continue;
            }
            try {
                double parsed = Double.parseDouble(String.valueOf(value));
                if (parsed > 0.0) {
                    amounts.add(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return amounts.isEmpty() ? List.of(100.0, 1000.0, 5000.0, 10000.0, 50000.0) : List.copyOf(amounts);
    }

    private List<String> readCommandAliases(FileConfiguration config) {
        List<String> aliases = new ArrayList<>();
        for (String raw : config.getStringList("commands.aliases")) {
            String alias = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!alias.matches("[a-z0-9_-]{2,32}") || alias.equals("ucjackpot") || aliases.contains(alias)) {
                continue;
            }
            aliases.add(alias);
        }
        return aliases.isEmpty() ? List.of("jackpot", "jp", "ucj") : List.copyOf(aliases);
    }

    private Set<String> lowerSet(java.util.List<String> values) {
        Set<String> set = new TreeSet<>();
        for (String value : values) {
            set.add(value.toLowerCase(Locale.ROOT));
        }
        return set;
    }

    private Set<String> enabledKeys(ConfigurationSection section) {
        Set<String> set = new TreeSet<>();
        if (section == null) {
            return set;
        }
        for (String key : section.getKeys(false)) {
            if (section.getBoolean(key, false)) {
                set.add(key.toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    private Set<String> upperSet(java.util.List<String> values) {
        Set<String> set = new TreeSet<>();
        for (String value : values) {
            set.add(value.toUpperCase(Locale.ROOT));
        }
        return set;
    }

    private void saveDefault(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
    }

    private YamlConfiguration loadYaml(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Failed to load jackpot room file '" + file.getPath() + "': " + exception.getMessage());
            plugin.getLogger().severe("This room was skipped. Fix the YAML syntax and run /ucjackpot reload.");
            return null;
        }
    }

    private YamlConfiguration bundledJackpot(String path) {
        InputStream stream = plugin.getResource(path);
        if (stream == null) {
            plugin.getLogger().severe("Bundled jackpot room file is missing: " + path);
            return new YamlConfiguration();
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            plugin.getLogger().severe("Failed to read bundled jackpot room file '" + path + "': " + exception.getMessage());
            return new YamlConfiguration();
        }
    }
}


