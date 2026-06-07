package com.unecroe.ucjackpot.resources;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceConsistencyTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path RESOURCES = ROOT.resolve("src/main/resources");
    private static final List<String> LOCALES = List.of("tr", "en", "de", "fr", "es", "pt", "ru", "ar", "zh", "ja", "ko");
    private static final List<String> GUI_MENUS = List.of("main", "confirm-item", "money", "deposits", "deposit-detail",
            "rooms", "preview", "fairness", "watch", "season", "stats", "history");
    private static final Set<String> GUI_ACTIONS = Set.of(
            "none", "join-money", "money-menu", "money-manual", "money-quick-1", "money-quick-2",
            "money-quick-3", "money-quick-4", "money-quick-5", "item-confirm", "confirm-item",
            "money-favorite", "money-favorite-set", "stats", "history", "deposits", "rooms",
            "preview", "fairness", "watch", "season", "ticket", "reclaim", "open-main", "close"
    );
    private static final Set<String> GUI_PLACEHOLDERS = Set.of(
            "player", "status", "pot_value", "money_pot", "item_pot", "players", "time_left",
            "jackpot", "jackpot_name", "season", "item_value", "default_entry", "min_money", "max_money", "min_items", "max_items",
            "quick_money_1", "quick_money_2", "quick_money_3", "quick_money_4", "quick_money_5",
            "favorite_money",
            "reclaim_money", "reclaim_items", "reclaim_tickets",
            "ticket_entries", "ticket_value", "player_entries", "player_chance", "last_winner", "item", "value", "target",
            "index", "winner", "time", "last_draw_id", "last_draw_hash", "last_draw_seed",
            "name", "mode", "money", "items", "tickets", "weight", "amount", "type", "number",
            "entries", "chance", "rank", "wins", "value_in", "value_won"
    );

    @Test
    void languageFilesContainEveryUsedMessageKey() throws IOException {
        Set<String> required = usedMessageKeys();
        required.remove("ok");
        assertFalse(required.isEmpty(), "No message keys were discovered from Java sources.");
        Set<String> reference = messageKeys("en");
        for (String locale : LOCALES) {
            Set<String> keys = messageKeys(locale);
            assertEquals(reference, keys, "Lang files must expose the same keys for " + locale + ".");
            assertFalse(keys.stream().anyMatch(key -> key.startsWith("gui-")), "GUI keys must be stored in gui/*.yml, not lang/" + locale + ".yml.");
            assertTrue(keys.containsAll(required), "Missing " + locale + " keys: " + difference(required, keys));
        }
    }

    @Test
    void guiFilesAreCompleteAndValid() {
        for (String locale : LOCALES) {
            for (String menu : GUI_MENUS) {
                validateGui(locale, menu);
            }
        }
    }

    @Test
    void guiTextFilesContainEveryUsedGuiKey() throws IOException {
        Set<String> required = usedGuiTextKeys();
        assertFalse(required.isEmpty(), "No GUI text keys were discovered from Java sources.");
        for (String locale : LOCALES) {
            Set<String> available = guiTextKeys(locale);
            assertEquals(required, available, "GUI text keys must match Java usage exactly for " + locale + ".");
        }
    }

    @Test
    void playerGuiDoesNotExposeConfigLanguage() throws IOException {
        for (Path file : Files.walk(RESOURCES.resolve("gui")).filter(path -> path.toString().endsWith(".yml")).toList()) {
            String text = Files.readString(file).toLowerCase();
            for (String forbidden : List.of("config", "whitelist", "blacklist")) {
                assertFalse(text.contains(forbidden), "Player GUI must not mention " + forbidden + ": " + file);
            }
        }
    }

    private Set<String> usedMessageKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        Pattern direct = Pattern.compile("messages\\.send\\([^,]+,\\s*\"([^\"]+)\"");
        Pattern formatted = Pattern.compile("messages\\.(?:format|broadcast)\\(\"([^\"]+)\"");
        Pattern list = Pattern.compile("messages\\.list\\(\"([^\"]+)\"\\)");
        Pattern operation = Pattern.compile("OperationResult\\.(?:ok|fail|wait)\\(\"([^\"]+)\"");
        for (Path file : Files.walk(ROOT.resolve("src/main/java")).filter(path -> path.toString().endsWith(".java")).toList()) {
            String source = Files.readString(file);
            collect(source, direct, keys);
            collect(source, formatted, keys);
            collect(source, list, keys);
            collect(source, operation, keys);
        }
        return keys;
    }

    private Set<String> usedGuiTextKeys() throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        Pattern guiText = Pattern.compile("gui(?:Text|List)\\(\"([^\"]+)\"");
        for (Path file : Files.walk(ROOT.resolve("src/main/java")).filter(path -> path.toString().endsWith(".java")).toList()) {
            collect(Files.readString(file), guiText, keys);
        }
        return keys;
    }

    private Set<String> guiTextKeys(String locale) {
        Set<String> keys = new LinkedHashSet<>();
        for (String menu : GUI_MENUS) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(RESOURCES.resolve("gui/" + locale + "/" + menu + ".yml").toFile());
            ConfigurationSection text = yaml.getConfigurationSection("text");
            if (text != null) {
                keys.addAll(text.getKeys(false));
            }
        }
        return keys;
    }

    private void collect(String source, Pattern pattern, Set<String> keys) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
    }

    private Set<String> messageKeys(String locale) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(RESOURCES.resolve("lang/" + locale + ".yml").toFile());
        ConfigurationSection section = yaml.getConfigurationSection("messages");
        assertNotNull(section, "messages section missing in lang/" + locale + ".yml");
        Set<String> keys = section.getKeys(false);
        for (String key : keys) {
            if (yaml.isList("messages." + key)) {
                assertFalse(yaml.getStringList("messages." + key).isEmpty(), "Empty message list: " + locale + "/" + key);
            } else {
                assertFalse(yaml.getString("messages." + key, "").isBlank(), "Empty message: " + locale + "/" + key);
            }
        }
        return new LinkedHashSet<>(keys);
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> copy = new LinkedHashSet<>(left);
        copy.removeAll(right);
        return copy;
    }

    private void validateGui(String locale, String menu) {
        File file = RESOURCES.resolve("gui/" + locale + "/" + menu + ".yml").toFile();
        assertTrue(file.exists(), "Missing GUI file: " + file);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int size = yaml.getInt("size");
        assertTrue(size >= 9 && size <= 54 && size % 9 == 0, "Invalid GUI size in " + file);
        assertFalse(yaml.getString("title", "").isBlank(), "Missing GUI title in " + file);
        assertMaterial(yaml.getString("filler.material"), file + " filler");
        validatePlaceholders(yaml.getString("title", ""), file + " title");
        validatePlaceholders(yaml.getString("filler.name", ""), file + " filler name");

        ConfigurationSection items = yaml.getConfigurationSection("items");
        assertNotNull(items, "Missing items section in " + file);
        Set<Integer> slots = new LinkedHashSet<>();
        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            assertNotNull(item, "Invalid item section " + id + " in " + file);
            int slot = item.getInt("slot", -1);
            assertTrue(slot >= 0 && slot < size, "Invalid slot for " + id + " in " + file);
            assertTrue(slots.add(slot), "Duplicate slot " + slot + " in " + file);
            assertMaterial(item.getString("material"), file + " item " + id);
            assertTrue(GUI_ACTIONS.contains(item.getString("action", "")), "Unknown action " + item.getString("action") + " in " + file);
            assertFalse(item.getString("name", "").isBlank(), "Missing name for " + id + " in " + file);
            assertTrue(item.isList("lore"), "Missing lore list for " + id + " in " + file);
            validatePlaceholders(item.getString("name", ""), file + " item " + id + " name");
            validatePlaceholders(String.join("\n", item.getStringList("lore")), file + " item " + id + " lore");
            validatePlaceholders(item.getString("head", ""), file + " item " + id + " head");
        }
        validateTextTemplates(yaml, file);
        validateDynamicSlots(yaml, file, size, slots);

        ConfigurationSection sounds = yaml.getConfigurationSection("sounds");
        assertNotNull(sounds, "Missing sounds section in " + file);
        for (String key : List.of("open", "click", "error")) {
            String value = sounds.getString(key);
            assertNotNull(value, "Missing sound " + key + " in " + file);
            assertTrue(value.matches("[A-Z0-9_]+"), "Invalid sound name format " + value + " in " + file);
        }
        validateHistoryTemplate(yaml, file);
        validateRoomTemplates(yaml, file, size, slots);
    }

    private void validateRoomTemplates(YamlConfiguration yaml, File file, int size, Set<Integer> usedSlots) {
        ConfigurationSection rooms = yaml.getConfigurationSection("rooms");
        if (rooms == null) {
            return;
        }
        Set<Integer> roomSlots = new LinkedHashSet<>(usedSlots);
        for (String id : rooms.getKeys(false)) {
            ConfigurationSection room = rooms.getConfigurationSection(id);
            assertNotNull(room, "Invalid room section " + id + " in " + file);
            int slot = room.getInt("slot", -1);
            assertTrue(slot >= 0 && slot < size, "Invalid room slot for " + id + " in " + file);
            assertTrue(roomSlots.add(slot), "Duplicate room slot " + slot + " in " + file);
            assertMaterial(room.getString("material"), file + " room " + id);
            assertFalse(room.getString("name", "").isBlank(), "Missing room name for " + id + " in " + file);
            assertFalse(room.getString("display-name", "").isBlank(), "Missing room display-name for " + id + " in " + file);
            assertTrue(room.isList("lore"), "Missing room lore list for " + id + " in " + file);
            validatePlaceholders(room.getString("display-name", ""), file + " room " + id + " display-name");
            validatePlaceholders(room.getString("name", ""), file + " room " + id + " name");
            validatePlaceholders(String.join("\n", room.getStringList("lore")), file + " room " + id + " lore");
        }
    }

    private void validateTextTemplates(YamlConfiguration yaml, File file) {
        ConfigurationSection text = yaml.getConfigurationSection("text");
        if (text == null) {
            return;
        }
        for (String key : text.getKeys(false)) {
            if (text.isList(key)) {
                List<String> lines = text.getStringList(key);
                assertFalse(lines.isEmpty(), "Empty GUI text list " + key + " in " + file);
                validatePlaceholders(String.join("\n", lines), file + " text " + key);
            } else {
                String value = text.getString(key, "");
                assertFalse(value.isBlank(), "Empty GUI text " + key + " in " + file);
                validatePlaceholders(value, file + " text " + key);
            }
        }
    }

    private void validateDynamicSlots(YamlConfiguration yaml, File file, int size, Set<Integer> staticSlots) {
        ConfigurationSection dynamic = yaml.getConfigurationSection("dynamic");
        if (dynamic == null) {
            return;
        }
        for (String key : dynamic.getKeys(false)) {
            List<Integer> slots = dynamic.getIntegerList(key);
            assertFalse(slots.isEmpty(), "Empty dynamic slot list " + key + " in " + file);
            Set<Integer> seen = new LinkedHashSet<>();
            for (int slot : slots) {
                assertTrue(slot >= 0 && slot < size, "Invalid dynamic slot " + slot + " for " + key + " in " + file);
                assertFalse(staticSlots.contains(slot), "Dynamic slot " + slot + " conflicts with static item in " + file);
                assertTrue(seen.add(slot), "Duplicate dynamic slot " + slot + " for " + key + " in " + file);
            }
        }
    }

    private void validateHistoryTemplate(YamlConfiguration yaml, File file) {
        if (!yaml.contains("history-item")) {
            return;
        }
        assertMaterial(yaml.getString("history-item.material"), file + " history-item");
        assertFalse(yaml.getString("history-item.name", "").isBlank(), "Missing history item name in " + file);
        assertTrue(yaml.isList("history-item.lore"), "Missing history item lore in " + file);
        validatePlaceholders(yaml.getString("history-item.name", ""), file + " history name");
        validatePlaceholders(String.join("\n", yaml.getStringList("history-item.lore")), file + " history lore");
    }

    private void assertMaterial(String name, String context) {
        assertNotNull(name, "Missing material for " + context);
        assertNotNull(Material.matchMaterial(name), "Invalid material " + name + " for " + context);
    }

    private void validatePlaceholders(String text, String context) {
        Matcher matcher = Pattern.compile("%([a-zA-Z0-9_]+)%").matcher(text);
        List<String> unknown = new ArrayList<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!GUI_PLACEHOLDERS.contains(key)) {
                unknown.add(key);
            }
        }
        assertTrue(unknown.isEmpty(), "Unknown GUI placeholders in " + context + ": " + unknown);
    }
}


