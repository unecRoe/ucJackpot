package com.unecroe.ucjackpot.lang;

import com.unecroe.ucjackpot.text.PlaceholderBag;
import com.unecroe.ucjackpot.text.TextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MessageService {
    public static final List<String> SUPPORTED_LOCALES = List.of("tr", "en", "de", "fr", "es", "pt", "ru", "ar", "zh", "ja", "ko");
    private final JavaPlugin plugin;
    private String locale = "en";
    private String fallbackLocale = "en";
    private FileConfiguration messages;
    private FileConfiguration fallback;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(String locale, String fallbackLocale) {
        this.fallbackLocale = supportedOrDefault(fallbackLocale, "en");
        this.locale = supportedOrDefault(locale, this.fallbackLocale);
        saveDefault("lang/" + this.fallbackLocale + ".yml");
        saveDefault("lang/" + this.locale + ".yml");
        this.fallback = load("lang/" + this.fallbackLocale + ".yml");
        this.messages = this.locale.equals(this.fallbackLocale)
                ? this.fallback
                : load("lang/" + this.locale + ".yml");
    }

    public void send(CommandSender sender, String key, PlaceholderBag placeholders) {
        sender.sendMessage(format(key, placeholders));
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, new PlaceholderBag());
    }

    public void broadcast(String key, PlaceholderBag placeholders, String permission) {
        Bukkit.broadcast(format(key, placeholders), permission);
    }

    public String format(String key, PlaceholderBag placeholders) {
        String text = raw("messages." + key);
        String prefix = raw("prefix");
        PlaceholderBag bag = placeholders == null ? new PlaceholderBag() : placeholders;
        return TextFormatter.color(bag.put("prefix", prefix).apply(text));
    }

    public List<String> list(String key) {
        List<String> list = messages.getStringList("messages." + key);
        if (list.isEmpty()) {
            list = fallback.getStringList("messages." + key);
        }
        return list.stream().map(TextFormatter::color).toList();
    }

    public List<String> list(String key, PlaceholderBag placeholders) {
        List<String> list = messages.getStringList("messages." + key);
        if (list.isEmpty()) {
            list = fallback.getStringList("messages." + key);
        }
        String prefix = raw("prefix");
        PlaceholderBag bag = placeholders == null ? new PlaceholderBag() : placeholders;
        bag.put("prefix", prefix);
        return list.stream()
                .map(bag::apply)
                .map(TextFormatter::color)
                .toList();
    }

    public String locale() {
        return locale;
    }

    public String fallbackLocale() {
        return fallbackLocale;
    }

    private String raw(String path) {
        String value = messages.getString(path);
        if (value == null) {
            value = fallback.getString(path, path);
        }
        return value;
    }

    private void saveDefault(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
    }

    private String supportedOrDefault(String requested, String defaultLocale) {
        String normalized = requested == null ? "" : requested.trim().toLowerCase();
        if (SUPPORTED_LOCALES.contains(normalized)) {
            return normalized;
        }
        plugin.getLogger().warning("Unsupported language '" + requested + "'. Using '" + defaultLocale + "'.");
        return defaultLocale;
    }

    private YamlConfiguration load(String path) {
        File file = new File(plugin.getDataFolder(), path);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Failed to load language file '" + file.getPath() + "': " + exception.getMessage());
            plugin.getLogger().severe("Using bundled fallback for '" + path + "'. Fix the YAML syntax and run /ucjackpot reload.");
            return bundled(path);
        }
    }

    private YamlConfiguration bundled(String path) {
        InputStream stream = plugin.getResource(path);
        if (stream == null) {
            plugin.getLogger().severe("Bundled language file is missing: " + path);
            return new YamlConfiguration();
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            plugin.getLogger().severe("Failed to read bundled language file '" + path + "': " + exception.getMessage());
            return new YamlConfiguration();
        }
    }
}


