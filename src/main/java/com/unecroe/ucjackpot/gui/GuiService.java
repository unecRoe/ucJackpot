package com.unecroe.ucjackpot.gui;

import com.unecroe.ucjackpot.config.ConfigService;
import com.unecroe.ucjackpot.config.JackpotDefinition;
import com.unecroe.ucjackpot.config.JackpotMode;
import com.unecroe.ucjackpot.item.ItemSerializer;
import com.unecroe.ucjackpot.jackpot.DrawResult;
import com.unecroe.ucjackpot.economy.EconomyService;
import com.unecroe.ucjackpot.jackpot.EntryType;
import com.unecroe.ucjackpot.jackpot.JackpotEntry;
import com.unecroe.ucjackpot.jackpot.JackpotRound;
import com.unecroe.ucjackpot.jackpot.JackpotService;
import com.unecroe.ucjackpot.lang.MessageService;
import com.unecroe.ucjackpot.storage.DrawEntryRecord;
import com.unecroe.ucjackpot.storage.HistoryRecord;
import com.unecroe.ucjackpot.storage.SeasonStatRecord;
import com.unecroe.ucjackpot.storage.StorageService;
import com.unecroe.ucjackpot.text.PlaceholderBag;
import com.unecroe.ucjackpot.text.TextFormatter;
import com.unecroe.ucjackpot.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiService {
    private static final DateTimeFormatter GUI_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final List<String> GUI_MENUS = List.of(
            "main", "confirm-item", "money", "deposits", "deposit-detail", "rooms",
            "preview", "fairness", "watch", "season", "stats", "history"
    );
    private final JavaPlugin plugin;
    private final ConfigService config;
    private final EconomyService economy;
    private final StorageService storage;
    private final MessageService messages;
    private JackpotService jackpotService;
    private final Map<String, GuiDefinition> menus = new HashMap<>();
    private final Map<String, String> guiText = new HashMap<>();
    private final Map<String, List<String>> guiLists = new HashMap<>();
    private final Map<UUID, Double> favoriteAmounts = new ConcurrentHashMap<>();
    private final Map<UUID, WatchSession> watchSessions = new ConcurrentHashMap<>();
    private String locale = "en";
    private String fallbackLocale = "en";

    public GuiService(JavaPlugin plugin, ConfigService config, EconomyService economy, StorageService storage, MessageService messages) {
        this.plugin = plugin;
        this.config = config;
        this.economy = economy;
        this.storage = storage;
        this.messages = messages;
    }

    public void jackpotService(JackpotService jackpotService) {
        this.jackpotService = jackpotService;
    }

    public void cacheFavoriteAmount(UUID playerUuid, double amount) {
        if (amount <= 0.0) {
            favoriteAmounts.remove(playerUuid);
            return;
        }
        favoriteAmounts.put(playerUuid, amount);
    }

    public void reload(String locale, String fallbackLocale) {
        this.locale = locale;
        this.fallbackLocale = fallbackLocale;
        for (String menu : GUI_MENUS) {
            saveDefault("gui/" + this.fallbackLocale + "/" + menu + ".yml");
            if (!this.locale.equals(this.fallbackLocale)) {
                saveDefault("gui/" + this.locale + "/" + menu + ".yml");
            }
        }
        menus.clear();
        guiText.clear();
        guiLists.clear();
        for (String menu : GUI_MENUS) {
            load(menu);
        }
    }

    private void load(String id) {
        String path = "gui/" + locale + "/" + id + ".yml";
        YamlConfiguration yaml = loadYaml(path);
        if (!guiFile(locale, id).exists()) {
            path = "gui/" + fallbackLocale + "/" + id + ".yml";
            yaml = loadYaml(path);
        }
        Map<Integer, GuiItemDefinition> items = new HashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection item = section.getConfigurationSection(key);
                if (item == null) {
                    continue;
                }
                GuiItemDefinition definition = new GuiItemDefinition(
                        key,
                        item.getInt("slot", -1),
                        item.getString("material", ""),
                        item.getString("head", ""),
                        item.getString("name", ""),
                        item.getStringList("lore"),
                        item.getString("action", "none"),
                        item.getBoolean("glow", false),
                        item.getInt("custom-model-data", 0)
                );
                items.put(definition.slot(), definition);
            }
        }
        readText(yaml.getConfigurationSection("text"));
        Map<String, RoomGuiDefinition> rooms = readRooms(yaml.getConfigurationSection("rooms"));
        GuiDefinition definition = new GuiDefinition(
                id,
                yaml.getString("title", ""),
                normalizeSize(yaml.getInt("size")),
                yaml.getString("filler.material", ""),
                yaml.getString("filler.name", ""),
                items,
                rooms,
                readDynamicSlots(yaml.getConfigurationSection("dynamic")),
                readSounds(yaml.getConfigurationSection("sounds")),
                yaml.getString("history-item.material", ""),
                yaml.getString("history-item.name", ""),
                yaml.getStringList("history-item.lore")
        );
        menus.put(id, definition);
    }

    private Map<String, String> readSounds(ConfigurationSection section) {
        Map<String, String> sounds = new HashMap<>();
        if (section == null) {
            return sounds;
        }
        for (String key : section.getKeys(false)) {
            sounds.put(key, section.getString(key, ""));
        }
        return sounds;
    }

    private void readText(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            if (section.isList(key)) {
                guiLists.put(key, section.getStringList(key));
            } else {
                guiText.put(key, section.getString(key, key));
            }
        }
    }

    private Map<String, List<Integer>> readDynamicSlots(ConfigurationSection section) {
        Map<String, List<Integer>> slots = new HashMap<>();
        if (section == null) {
            return slots;
        }
        for (String key : section.getKeys(false)) {
            if (section.isList(key)) {
                slots.put(key, section.getIntegerList(key));
                continue;
            }
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) {
                continue;
            }
            if (child.isList("slots")) {
                slots.put(key, child.getIntegerList("slots"));
            } else if (child.contains("slot")) {
                slots.put(key, List.of(child.getInt("slot", -1)));
            }
        }
        return slots;
    }

    private Map<String, RoomGuiDefinition> readRooms(ConfigurationSection section) {
        Map<String, RoomGuiDefinition> rooms = new HashMap<>();
        if (section == null) {
            return rooms;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection room = section.getConfigurationSection(key);
            if (room == null) {
                continue;
            }
            rooms.put(key.toLowerCase(Locale.ROOT), new RoomGuiDefinition(
                    key.toLowerCase(Locale.ROOT),
                    room.getInt("slot", -1),
                    room.getString("material", ""),
                    room.getString("display-name", ""),
                    room.getString("name", ""),
                    room.getStringList("lore"),
                    room.getBoolean("glow", false)
            ));
        }
        return rooms;
    }

    private int normalizeSize(int size) {
        int normalized = Math.max(9, Math.min(54, size));
        return normalized % 9 == 0 ? normalized : ((normalized / 9) + 1) * 9;
    }

    private File guiFile(String locale, String id) {
        return new File(plugin.getDataFolder(), "gui/" + locale + "/" + id + ".yml");
    }

    private void saveDefault(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) {
            plugin.saveResource(path, false);
        }
    }

    private YamlConfiguration loadYaml(String path) {
        File file = new File(plugin.getDataFolder(), path);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Failed to load GUI file '" + file.getPath() + "': " + exception.getMessage());
            plugin.getLogger().severe("Using bundled fallback for '" + path + "'. Fix the YAML syntax and run /ucjackpot reload.");
            return bundled(path);
        }
    }

    private YamlConfiguration bundled(String path) {
        InputStream stream = plugin.getResource(path);
        if (stream == null) {
            plugin.getLogger().severe("Bundled GUI file is missing: " + path);
            return new YamlConfiguration();
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            plugin.getLogger().severe("Failed to read bundled GUI file '" + path + "': " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    public void open(Player player, String id) {
        open(player, id, jackpotService.primaryRound().definition().id());
    }

    public void open(Player player, String id, String jackpotId) {
        if ("history".equals(id)) {
            openHistory(player);
            return;
        }
        if ("deposits".equals(id)) {
            openDeposits(player, jackpotId);
            return;
        }
        if ("rooms".equals(id)) {
            openRooms(player);
            return;
        }
        if ("preview".equals(id)) {
            openPreview(player, jackpotId);
            return;
        }
        if ("fairness".equals(id)) {
            openFairness(player, jackpotId);
            return;
        }
        if ("watch".equals(id)) {
            openWatch(player, jackpotId);
            return;
        }
        GuiDefinition definition = menus.getOrDefault(id, menus.get("main"));
        PlaceholderBag placeholders = placeholders(player, jackpotId);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder(definition.id(), jackpotId, actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
            actions.put(item.slot(), item.action());
        }
        player.openInventory(inventory);
        play(player, definition, "open");
        startLiveRefresh(player, definition, jackpotId, inventory, actions);
        if ("money".equals(definition.id())) {
            refreshFavoriteMoney(player, definition, jackpotId, inventory);
        }
    }

    private void refreshFavoriteMoney(Player player, GuiDefinition definition, String jackpotId, Inventory inventory) {
        storage.favoriteAmount(player.getUniqueId()).thenAccept(amount -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inventory) {
                return;
            }
            if (!(inventory.getHolder() instanceof JackpotMenuHolder holder) || !"money".equals(holder.menuId())) {
                return;
            }
            cacheFavoriteAmount(player.getUniqueId(), amount == null ? 0.0 : amount);
            PlaceholderBag placeholders = placeholders(player, jackpotId);
            fill(inventory, definition, placeholders);
            for (GuiItemDefinition item : definition.items().values()) {
                inventory.setItem(item.slot(), render(player, item, placeholders));
            }
        }));
    }

    private void openHistory(Player player) {
        GuiDefinition definition = menus.get("history");
        PlaceholderBag placeholders = placeholders(player);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder("history", actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
            actions.put(item.slot(), item.action());
        }
        List<Integer> recordSlots = dynamicSlots(definition, "record-slots");
        storage.recentHistory(recordSlots.size()).thenAccept(records -> Bukkit.getScheduler().runTask(plugin, () -> {
            int index = 1;
            for (int slotIndex = 0; slotIndex < records.size() && slotIndex < recordSlots.size(); slotIndex++) {
                int slot = recordSlots.get(slotIndex);
                if (!validSlot(slot, definition)) {
                    break;
                }
                HistoryRecord record = records.get(slotIndex);
                PlaceholderBag bag = placeholders(player)
                        .put("index", index++)
                        .put("winner", record.winnerName())
                        .put("value", economy.provider().format(record.value()))
                        .put("time", GUI_DATE_FORMAT.format(Instant.ofEpochMilli(record.createdAt())))
                        .put("jackpot", roomName(record.jackpotId()));
                inventory.setItem(slot, renderHistoryItem(definition, bag));
                if (record.drawId() != null && !record.drawId().isBlank()) {
                    actions.put(slot, "history-draw:" + record.drawId());
                }
            }
        }));
        player.openInventory(inventory);
        play(player, definition, "open");
    }

    public void openHistoryDetail(Player player, String drawId) {
        GuiDefinition definition = menus.get("history");
        PlaceholderBag placeholders = placeholders(player);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder("history-detail", actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
            actions.put(item.slot(), item.action());
            if ("open-main".equals(item.action())) {
                actions.put(item.slot(), "history");
            }
        }
        List<Integer> recordSlots = dynamicSlots(definition, "record-slots");
        storage.drawEntries(drawId).thenAccept(records -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!sameOpenInventory(player, inventory, "history-detail")) {
                return;
            }
            if (records.isEmpty()) {
                setDynamicItem(inventory, definition, "record-slots", renderHistoryEmpty());
                return;
            }
            for (int slotIndex = 0; slotIndex < records.size() && slotIndex < recordSlots.size(); slotIndex++) {
                int slot = recordSlots.get(slotIndex);
                if (!validSlot(slot, definition)) {
                    break;
                }
                inventory.setItem(slot, renderHistoryEntry(records.get(slotIndex)));
            }
        }));
        player.openInventory(inventory);
        play(player, definition, "open");
    }

    private void openDeposits(Player player, String jackpotId) {
        GuiDefinition definition = menus.get("deposits");
        PlaceholderBag placeholders = placeholders(player, jackpotId);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder("deposits", jackpotId, actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
            actions.put(item.slot(), item.action());
        }

        JackpotRound round = roundOrPrimary(jackpotId);
        Map<UUID, List<JackpotEntry>> grouped = new LinkedHashMap<>();
        for (JackpotEntry entry : round.entries()) {
            grouped.computeIfAbsent(entry.playerUuid(), ignored -> new ArrayList<>()).add(entry);
        }
        List<Integer> depositorSlots = dynamicSlots(definition, "depositor-slots");
        int slotIndex = 0;
        for (Map.Entry<UUID, List<JackpotEntry>> group : grouped.entrySet()) {
            if (slotIndex >= depositorSlots.size()) {
                break;
            }
            int slot = depositorSlots.get(slotIndex++);
            if (!validSlot(slot, definition)) {
                continue;
            }
            inventory.setItem(slot, renderDepositor(group.getKey(), group.getValue(), round));
            actions.put(slot, "deposits-player:" + group.getKey());
        }
        player.openInventory(inventory);
        play(player, definition, "open");
    }

    public void openDepositDetail(Player viewer, UUID targetUuid) {
        openDepositDetail(viewer, jackpotService.primaryRound().definition().id(), targetUuid);
    }

    public void openDepositDetail(Player viewer, String jackpotId, UUID targetUuid) {
        JackpotRound round = roundOrPrimary(jackpotId);
        List<JackpotEntry> entries = round.entries().stream()
                .filter(entry -> entry.playerUuid().equals(targetUuid))
                .toList();
        String targetName = entries.isEmpty() ? Bukkit.getOfflinePlayer(targetUuid).getName() : entries.get(0).playerName();
        GuiDefinition definition = menus.get("deposit-detail");
        PlaceholderBag placeholders = placeholders(viewer, jackpotId).put("target", targetName == null ? targetUuid.toString() : targetName);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder("deposit-detail", jackpotId, actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(viewer, item, placeholders));
            actions.put(item.slot(), item.action());
        }
        setDynamicItem(inventory, definition, "money-summary-slot", renderMoneySummary(targetUuid, targetName, round));
        List<Integer> itemSlots = dynamicSlots(definition, "item-slots");
        int slotIndex = 0;
        for (JackpotEntry entry : entries) {
            if (entry.type() != EntryType.ITEM || entry.item() == null) {
                continue;
            }
            if (slotIndex >= itemSlots.size()) {
                break;
            }
            int slot = itemSlots.get(slotIndex++);
            if (!validSlot(slot, definition)) {
                continue;
            }
            inventory.setItem(slot, renderDepositedItem(entry));
        }
        viewer.openInventory(inventory);
        play(viewer, definition, "open");
    }

    private void openRooms(Player player) {
        GuiDefinition definition = menus.get("rooms");
        PlaceholderBag placeholders = placeholders(player);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder("rooms", jackpotService.primaryRound().definition().id(), actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
            actions.put(item.slot(), item.action());
        }
        populateRooms(inventory, definition, actions);
        player.openInventory(inventory);
        play(player, definition, "open");
        startLiveRefresh(player, definition, holder.jackpotId(), inventory, actions);
    }

    private void populateRooms(Inventory inventory, GuiDefinition definition, Map<Integer, String> actions) {
        actions.entrySet().removeIf(entry -> entry.getValue().startsWith("room:"));
        int fallbackIndex = 0;
        List<Integer> fallbackSlots = dynamicSlots(definition, "fallback-room-slots");
        for (JackpotRound round : jackpotService.rounds()) {
            RoomGuiDefinition roomGui = roomGui(definition, round.definition().id());
            if (roomGui == null) {
                continue;
            }
            int slot = roomGui.slot();
            if (slot < 0 || definition.items().containsKey(slot) || actions.containsKey(slot)) {
                while (fallbackIndex < fallbackSlots.size()
                        && (definition.items().containsKey(fallbackSlots.get(fallbackIndex))
                        || actions.containsKey(fallbackSlots.get(fallbackIndex)))) {
                    fallbackIndex++;
                }
                if (fallbackIndex >= fallbackSlots.size()) {
                    break;
                }
                slot = fallbackSlots.get(fallbackIndex++);
            }
            if (slot >= inventory.getSize()) {
                break;
            }
            inventory.setItem(slot, renderRoom(round, roomGui));
            actions.put(slot, "room:" + round.definition().id());
        }
    }

    private void openPreview(Player player, String jackpotId) {
        GuiDefinition definition = menus.get("preview");
        JackpotRound round = roundOrPrimary(jackpotId);
        PlaceholderBag placeholders = placeholders(player, jackpotId);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder("preview", round.definition().id(), actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
            actions.put(item.slot(), item.action());
        }
        setDynamicItem(inventory, definition, "prize-summary-slot", renderPrizeSummary(round));
        List<Integer> itemSlots = dynamicSlots(definition, "item-slots");
        int slotIndex = 0;
        for (JackpotEntry entry : round.entries().stream().filter(e -> e.type() == EntryType.ITEM && e.item() != null).toList()) {
            if (slotIndex >= itemSlots.size()) {
                break;
            }
            int slot = itemSlots.get(slotIndex++);
            if (!validSlot(slot, definition)) {
                continue;
            }
            inventory.setItem(slot, renderDepositedItem(entry));
        }
        player.openInventory(inventory);
        play(player, definition, "open");
    }

    private void openFairness(Player player, String jackpotId) {
        GuiDefinition definition = menus.get("fairness");
        JackpotRound round = roundOrPrimary(jackpotId);
        PlaceholderBag placeholders = placeholders(player, jackpotId);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder("fairness", round.definition().id(), actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
            actions.put(item.slot(), item.action());
        }
        Map<UUID, List<JackpotEntry>> grouped = new LinkedHashMap<>();
        for (JackpotEntry entry : round.entries()) {
            grouped.computeIfAbsent(entry.playerUuid(), ignored -> new ArrayList<>()).add(entry);
        }
        List<Integer> playerSlots = dynamicSlots(definition, "player-slots");
        int slotIndex = 0;
        for (UUID uuid : grouped.keySet()) {
            if (slotIndex >= playerSlots.size()) {
                break;
            }
            int slot = playerSlots.get(slotIndex++);
            if (!validSlot(slot, definition)) {
                continue;
            }
            inventory.setItem(slot, renderFairnessPlayer(uuid, grouped.get(uuid), round));
        }
        player.openInventory(inventory);
        play(player, definition, "open");
    }

    private void openWatch(Player player, String jackpotId) {
        GuiDefinition definition = menus.get("watch");
        JackpotRound round = roundOrPrimary(jackpotId);
        PlaceholderBag placeholders = placeholders(player, jackpotId);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder("watch", round.definition().id(), actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
            actions.put(item.slot(), item.action());
        }
        player.openInventory(inventory);
        watchSessions.put(player.getUniqueId(), new WatchSession(round.definition().id(), inventory));
        animateWatch(player, inventory, definition, round);
        startLiveRefresh(player, definition, round.definition().id(), inventory, actions);
        play(player, definition, "open");
    }

    public void openSeason(Player player, String jackpotId) {
        GuiDefinition definition = menus.get("season");
        JackpotRound round = roundOrPrimary(jackpotId);
        PlaceholderBag placeholders = placeholders(player, jackpotId);
        Map<Integer, String> actions = new HashMap<>();
        JackpotMenuHolder holder = new JackpotMenuHolder("season", jackpotId, actions);
        Inventory inventory = Bukkit.createInventory(holder, definition.size(),
                TextFormatter.color(placeholders.apply(definition.title())));
        fill(inventory, definition, placeholders);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
            actions.put(item.slot(), item.action());
        }
        List<Integer> leaderboardSlots = dynamicSlots(definition, "leaderboard-slots");
        storage.topSeason(round.definition().seasonId(), leaderboardSlots.size()).thenAccept(records -> Bukkit.getScheduler().runTask(plugin, () -> {
            for (int index = 0; index < records.size() && index < leaderboardSlots.size(); index++) {
                int slot = leaderboardSlots.get(index);
                if (!validSlot(slot, definition)) {
                    continue;
                }
                inventory.setItem(slot, renderSeasonStat(records.get(index), index + 1));
            }
        }));
        player.openInventory(inventory);
        play(player, definition, "open");
    }

    private List<Integer> dynamicSlots(GuiDefinition definition, String key) {
        return definition.dynamicSlots().getOrDefault(key, List.of());
    }

    private void startLiveRefresh(Player player, GuiDefinition definition, String jackpotId, Inventory inventory,
                                  Map<Integer, String> actions) {
        if (!needsLiveRefresh(definition)) {
            return;
        }
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!sameOpenInventory(player, inventory, definition.id())) {
                task.cancel();
                return;
            }
            refreshStaticItems(player, definition, jackpotId, inventory);
            if ("rooms".equals(definition.id())) {
                populateRooms(inventory, definition, actions);
            }
        }, 20L, 20L);
    }

    private boolean sameOpenInventory(Player player, Inventory inventory, String menuId) {
        if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inventory) {
            return false;
        }
        return inventory.getHolder() instanceof JackpotMenuHolder holder && holder.menuId().equals(menuId);
    }

    private void refreshStaticItems(Player player, GuiDefinition definition, String jackpotId, Inventory inventory) {
        PlaceholderBag placeholders = placeholders(player, jackpotId);
        for (GuiItemDefinition item : definition.items().values()) {
            inventory.setItem(item.slot(), render(player, item, placeholders));
        }
    }

    private boolean needsLiveRefresh(GuiDefinition definition) {
        if ("rooms".equals(definition.id())) {
            return true;
        }
        if (containsTimeLeft(definition.title()) || containsTimeLeft(definition.fillerName())) {
            return true;
        }
        for (GuiItemDefinition item : definition.items().values()) {
            if (containsTimeLeft(item.name()) || containsTimeLeft(item.lore())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsTimeLeft(String value) {
        return value != null && value.contains("%time_left%");
    }

    private boolean containsTimeLeft(List<String> values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (containsTimeLeft(value)) {
                return true;
            }
        }
        return false;
    }

    private void setDynamicItem(Inventory inventory, GuiDefinition definition, String key, ItemStack item) {
        for (int slot : dynamicSlots(definition, key)) {
            if (validSlot(slot, definition)) {
                inventory.setItem(slot, item);
                return;
            }
        }
    }

    private boolean validSlot(int slot, GuiDefinition definition) {
        return slot >= 0 && slot < definition.size() && !definition.items().containsKey(slot);
    }

    private void fill(Inventory inventory, GuiDefinition definition, PlaceholderBag placeholders) {
        ItemStack filler = filler(definition, placeholders);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private ItemStack filler(GuiDefinition definition, PlaceholderBag placeholders) {
        Material material = material(definition.fillerMaterial());
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TextFormatter.color(placeholders.apply(definition.fillerName())));
            filler.setItemMeta(meta);
        }
        return filler;
    }

    private ItemStack render(Player player, GuiItemDefinition definition, PlaceholderBag placeholders) {
        Material material = material(definition.material());
        ItemStack item = new ItemStack(material);
        if (material == Material.PLAYER_HEAD && definition.head() != null && !definition.head().isBlank()) {
            applyHead(player, item, placeholders.apply(definition.head()));
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TextFormatter.color(placeholders.apply(definition.name())));
            meta.setLore(definition.lore().stream()
                    .map(placeholders::apply)
                    .map(TextFormatter::color)
                    .toList());
            if (definition.customModelData() > 0) {
                meta.setCustomModelData(definition.customModelData());
            }
            item.setItemMeta(meta);
        }
        if (definition.glow()) {
            glow(item);
        }
        return item;
    }

    private ItemStack renderHistoryItem(GuiDefinition definition, PlaceholderBag placeholders) {
        ItemStack item = new ItemStack(material(definition.historyMaterial()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TextFormatter.color(placeholders.apply(definition.historyName())));
            meta.setLore(definition.historyLore().stream()
                    .map(placeholders::apply)
                    .map(TextFormatter::color)
                    .toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack renderHistoryEntry(DrawEntryRecord record) {
        EntryType type = historyEntryType(record.type());
        return switch (type) {
            case MONEY -> renderHistoryMoneyEntry(record);
            case ITEM -> renderHistoryItemEntry(record);
            case TICKET -> renderHistoryTicketEntry(record);
        };
    }

    private ItemStack renderHistoryMoneyEntry(DrawEntryRecord record) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PlaceholderBag bag = historyEntryPlaceholders(record).put("money", economy.provider().format(record.moneyAmount()));
            meta.setDisplayName(guiText("history-money-entry-name", bag));
            meta.setLore(guiList("history-money-entry-lore", bag));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack renderHistoryTicketEntry(DrawEntryRecord record) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PlaceholderBag bag = historyEntryPlaceholders(record);
            meta.setDisplayName(guiText("history-ticket-entry-name", bag));
            meta.setLore(guiList("history-ticket-entry-lore", bag));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack renderHistoryItemEntry(DrawEntryRecord record) {
        ItemStack item = decodeHistoryItem(record);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PlaceholderBag bag = historyEntryPlaceholders(record)
                    .put("item", item.getType().name())
                    .put("amount", item.getAmount());
            meta.setDisplayName(guiText("history-item-entry-name", bag));
            List<String> lore = new ArrayList<>();
            if (meta.hasLore() && meta.getLore() != null) {
                lore.addAll(meta.getLore());
                lore.add("");
            }
            lore.addAll(guiList("history-item-entry-lore", bag));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack renderHistoryEmpty() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(guiText("history-empty-name", new PlaceholderBag()));
            meta.setLore(guiList("history-empty-lore", new PlaceholderBag()));
            item.setItemMeta(meta);
        }
        return item;
    }

    private PlaceholderBag historyEntryPlaceholders(DrawEntryRecord record) {
        return new PlaceholderBag()
                .put("player", record.playerName())
                .put("value", economy.provider().format(record.entryValue()))
                .put("time", GUI_DATE_FORMAT.format(Instant.ofEpochMilli(record.createdAt())))
                .put("type", entryTypeName(historyEntryType(record.type())));
    }

    private ItemStack decodeHistoryItem(DrawEntryRecord record) {
        if (record.encodedItem() == null || record.encodedItem().isBlank()) {
            return new ItemStack(Material.BARRIER);
        }
        try {
            return ItemSerializer.decode(record.encodedItem()).clone();
        } catch (RuntimeException exception) {
            return new ItemStack(Material.BARRIER);
        }
    }

    private EntryType historyEntryType(String raw) {
        try {
            return EntryType.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return EntryType.MONEY;
        }
    }

    private String guiText(String key, PlaceholderBag placeholders) {
        PlaceholderBag bag = placeholders == null ? new PlaceholderBag() : placeholders;
        String text = guiText.get(key);
        return TextFormatter.color(bag.apply(text == null ? key : text));
    }

    private List<String> guiList(String key, PlaceholderBag placeholders) {
        PlaceholderBag bag = placeholders == null ? new PlaceholderBag() : placeholders;
        List<String> lines = guiLists.get(key);
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream()
                .map(bag::apply)
                .map(TextFormatter::color)
                .toList();
    }

    private ItemStack renderDepositor(UUID uuid, List<JackpotEntry> entries, JackpotRound round) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        OfflinePlayer owner = Bukkit.getOfflinePlayer(uuid);
        ItemMeta raw = item.getItemMeta();
        if (raw instanceof SkullMeta meta) {
            meta.setOwningPlayer(owner);
            PlaceholderBag bag = new PlaceholderBag()
                    .put("player", entries.get(0).playerName())
                    .put("money", economy.provider().format(round.playerMoney(uuid)))
                    .put("items", round.playerItemCount(uuid))
                    .put("entries", round.playerEntryCount(uuid))
                    .put("chance", String.format(Locale.US, "%.2f", jackpotService.chance(uuid, round.definition().id())));
            meta.setDisplayName(guiText("depositor-name", bag));
            meta.setLore(guiList("depositor-lore", bag));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack renderMoneySummary(UUID uuid, String name, JackpotRound round) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PlaceholderBag bag = new PlaceholderBag()
                    .put("player", name == null ? uuid : name)
                    .put("money", economy.provider().format(round.playerMoney(uuid)))
                    .put("items", round.playerItemCount(uuid));
            meta.setDisplayName(guiText("money-summary-name", bag));
            meta.setLore(guiList("money-summary-lore", bag));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack renderDepositedItem(JackpotEntry entry) {
        ItemStack item = entry.item().clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String baseName = meta.hasDisplayName() ? meta.getDisplayName() : entry.item().getType().name();
            PlaceholderBag bag = new PlaceholderBag()
                    .put("item", baseName)
                    .put("player", entry.playerName())
                    .put("amount", entry.item().getAmount());
            meta.setDisplayName(guiText("deposited-item-name", bag));
            List<String> lore = new ArrayList<>();
            if (meta.hasLore() && meta.getLore() != null) {
                lore.addAll(meta.getLore());
                lore.add("");
            }
            lore.addAll(guiList("deposited-item-lore", bag));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack renderRoom(JackpotRound round, RoomGuiDefinition roomGui) {
        ItemStack item = new ItemStack(material(roomGui.material()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PlaceholderBag bag = new PlaceholderBag()
                    .put("name", roomName(round.definition(), roomGui))
                    .put("mode", modeName(round.definition().mode()))
                    .put("money", economy.provider().format(round.moneyPot()))
                    .put("items", round.itemCount())
                    .put("players", round.participantCount())
                    .put("time", TimeUtil.compact(round.secondsLeft()));
            String name = roomGui.name().isBlank()
                    ? guiText("room-name", bag)
                    : TextFormatter.color(bag.apply(roomGui.name()));
            List<String> lore = roomGui.lore().isEmpty()
                    ? guiList("room-lore", bag)
                    : roomGui.lore().stream().map(bag::apply).map(TextFormatter::color).toList();
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        if (roomGui.glow()) {
            glow(item);
        }
        return item;
    }

    private String modeName(JackpotMode mode) {
        return switch (mode) {
            case MONEY -> guiText("mode-money", new PlaceholderBag());
            case ITEM -> guiText("mode-item", new PlaceholderBag());
            case HYBRID -> guiText("mode-hybrid", new PlaceholderBag());
        };
    }

    private String roomName(JackpotDefinition definition) {
        return roomName(definition.id(), definition.displayName());
    }

    private String roomName(JackpotDefinition definition, RoomGuiDefinition roomGui) {
        if (roomGui != null && roomGui.displayName() != null && !roomGui.displayName().isBlank()) {
            return TextFormatter.color(roomGui.displayName());
        }
        return roomName(definition);
    }

    private RoomGuiDefinition roomGui(GuiDefinition definition, String jackpotId) {
        return definition.rooms().get(jackpotId.toLowerCase(Locale.ROOT));
    }

    private String roomName(String jackpotId) {
        JackpotRound round = jackpotService.round(jackpotId);
        return round == null ? jackpotId : roomName(round.definition());
    }

    private String roomName(String jackpotId, String fallback) {
        GuiDefinition roomsMenu = menus.get("rooms");
        RoomGuiDefinition roomGui = roomsMenu == null ? null : roomGui(roomsMenu, jackpotId);
        if (roomGui != null && roomGui.displayName() != null && !roomGui.displayName().isBlank()) {
            return TextFormatter.color(roomGui.displayName());
        }
        return fallback;
    }

    private String entryTypeName(EntryType type) {
        return switch (type) {
            case MONEY -> guiText("entry-type-money", new PlaceholderBag());
            case ITEM -> guiText("entry-type-item", new PlaceholderBag());
            case TICKET -> guiText("entry-type-ticket", new PlaceholderBag());
        };
    }

    private ItemStack renderPrizeSummary(JackpotRound round) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PlaceholderBag bag = new PlaceholderBag()
                    .put("money", economy.provider().format(round.moneyPot()))
                    .put("items", round.itemCount())
                    .put("tickets", round.ticketCount());
            meta.setDisplayName(guiText("prize-summary-name", bag));
            meta.setLore(guiList("prize-summary-lore", bag));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack renderFairnessPlayer(UUID uuid, List<JackpotEntry> entries, JackpotRound round) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = item.getItemMeta();
        if (raw instanceof SkullMeta meta) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(uuid);
            meta.setOwningPlayer(owner);
            String name = entries.isEmpty() ? String.valueOf(owner.getName()) : entries.get(0).playerName();
            PlaceholderBag bag = new PlaceholderBag()
                    .put("player", name)
                    .put("chance", String.format(Locale.US, "%.2f", jackpotService.chance(uuid, round.definition().id())))
                    .put("weight", String.format(Locale.US, "%.2f", round.playerValue(uuid)))
                    .put("money", economy.provider().format(round.playerMoney(uuid)))
                    .put("items", round.playerItemCount(uuid))
                    .put("tickets", round.playerTicketCount(uuid));
            meta.setDisplayName(guiText("fairness-player-name", bag));
            meta.setLore(guiList("fairness-player-lore", bag));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack renderSeasonStat(SeasonStatRecord record, int rank) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = item.getItemMeta();
        if (raw instanceof SkullMeta meta) {
            try {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(record.playerUuid())));
            } catch (IllegalArgumentException ignored) {
            }
            PlaceholderBag bag = new PlaceholderBag()
                    .put("rank", rank)
                    .put("season", seasonName(record.seasonId()))
                    .put("player", record.playerName())
                    .put("entries", record.entries())
                    .put("wins", record.wins())
                    .put("value_in", economy.provider().format(record.valueIn()))
                    .put("value_won", economy.provider().format(record.valueWon()));
            meta.setDisplayName(guiText("season-stat-name", bag));
            meta.setLore(guiList("season-stat-lore", bag));
            item.setItemMeta(meta);
        }
        return item;
    }

    public void showWatchWinner(DrawResult result) {
        GuiDefinition definition = menus.get("watch");
        if (definition == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            WatchSession session = watchSessions.get(player.getUniqueId());
            if (session == null || !session.jackpotId().equalsIgnoreCase(result.jackpotId())) {
                continue;
            }
            Inventory inventory = session.inventory();
            if (!sameOpenInventory(player, inventory, "watch")) {
                watchSessions.remove(player.getUniqueId(), session);
                continue;
            }
            if (!(inventory.getHolder() instanceof JackpotMenuHolder holder)
                    || !holder.jackpotId().equalsIgnoreCase(result.jackpotId())) {
                watchSessions.remove(player.getUniqueId(), session);
                continue;
            }
            watchSessions.remove(player.getUniqueId(), session);
            PlaceholderBag placeholders = placeholders(player, result.jackpotId());
            fill(inventory, definition, placeholders);
            refreshStaticItems(player, definition, result.jackpotId(), inventory);
            for (int slot : dynamicSlots(definition, "winner-slot")) {
                if (validSlot(slot, definition)) {
                    inventory.setItem(slot, renderWatchWinner(result));
                    break;
                }
            }
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.1f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (sameOpenInventory(player, inventory, "watch")) {
                    player.closeInventory();
                }
            }, 40L);
        }
    }

    private void animateWatch(Player player, Inventory inventory, GuiDefinition definition, JackpotRound round) {
        List<Integer> slots = dynamicSlots(definition, "entry-slots");
        if (slots.isEmpty()) {
            return;
        }
        long[] frame = {0L};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            WatchSession session = watchSessions.get(player.getUniqueId());
            if (!sameOpenInventory(player, inventory, "watch")
                    || session == null
                    || session.inventory() != inventory
                    || !session.jackpotId().equalsIgnoreCase(round.definition().id())) {
                if (session == null) {
                    watchSessions.remove(player.getUniqueId());
                } else {
                    watchSessions.remove(player.getUniqueId(), session);
                }
                task.cancel();
                return;
            }
            List<JackpotEntry> entries = round.entries();
            if (entries.isEmpty()) {
                ItemStack filler = filler(definition, placeholders(player, round.definition().id()));
                for (int slot : slots) {
                    if (validSlot(slot, definition)) {
                        inventory.setItem(slot, filler.clone());
                    }
                }
                return;
            }
            List<ItemStack> frames = entries.stream()
                    .map(entry -> renderAnimationEntry(entry, round))
                    .toList();
            boolean drawing = round.drawing();
            long tick = drawing ? frame[0]++ : System.currentTimeMillis() / 350L;
            for (int i = 0; i < slots.size(); i++) {
                ItemStack frameItem = frames.get((int) ((tick + i) % frames.size()));
                int slot = slots.get(i);
                if (validSlot(slot, definition)) {
                    inventory.setItem(slot, frameItem.clone());
                }
            }
            if (drawing && frame[0] % 4L == 0L) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.55f, 1.25f);
            }
        }, 0L, 5L);
    }

    private ItemStack renderWatchWinner(DrawResult result) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = item.getItemMeta();
        if (raw instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(result.winnerUuid()));
            PlaceholderBag bag = new PlaceholderBag()
                    .put("player", result.winnerName())
                    .put("money", economy.provider().format(result.moneyPrize()))
                    .put("items", result.itemCount());
            meta.setDisplayName(guiText("watch-winner-name", bag));
            meta.setLore(guiList("watch-winner-lore", bag));
            item.setItemMeta(meta);
        }
        glow(item);
        return item;
    }

    private ItemStack renderAnimationEntry(JackpotEntry entry, JackpotRound round) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = item.getItemMeta();
        if (raw instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.playerUuid()));
            PlaceholderBag bag = new PlaceholderBag()
                    .put("player", entry.playerName())
                    .put("chance", String.format(Locale.US, "%.2f", jackpotService.chance(entry.playerUuid(), round.definition().id())))
                    .put("type", entryTypeName(entry.type()));
            meta.setDisplayName(guiText("watch-entry-name", bag));
            meta.setLore(guiList("watch-entry-lore", bag));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyHead(Player player, ItemStack item, String value) {
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof SkullMeta meta)) {
            return;
        }
        if ("%player%".equals(value) || value.equalsIgnoreCase(player.getName())) {
            meta.setOwningPlayer(player);
        } else {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(value));
        }
        item.setItemMeta(meta);
    }

    private void glow(ItemStack item) {
        Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        if (enchantment == null) {
            return;
        }
        item.addUnsafeEnchantment(enchantment, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
    }

    private Material material(String name) {
        Material material = Material.matchMaterial(String.valueOf(name));
        return material == null ? Material.BARRIER : material;
    }

    private PlaceholderBag placeholders(Player player) {
        return placeholders(player, jackpotService.primaryRound().definition().id());
    }

    private PlaceholderBag placeholders(Player player, String jackpotId) {
        JackpotRound round = roundOrPrimary(jackpotId);
        JackpotDefinition definition = round.definition();
        DrawResult lastDraw = jackpotService.lastDraw();
        double chance = jackpotService.chance(player.getUniqueId(), definition.id());
        return new PlaceholderBag()
                .put("player", player.getName())
                .put("jackpot", definition.id())
                .put("jackpot_name", roomName(definition))
                .put("season", seasonName(definition.seasonId()))
                .put("status", roundStatus(round))
                .put("pot_value", economy.provider().format(round.totalValue()))
                .put("money_pot", economy.provider().format(round.moneyPot()))
                .put("item_pot", round.itemCount())
                .put("item_value", economy.provider().format(round.itemValue()))
                .put("ticket_entries", round.ticketCount())
                .put("ticket_value", economy.provider().format(definition.ticketEntryValue()))
                .put("players", round.participantCount())
                .put("time_left", TimeUtil.compact(round.secondsLeft()))
                .put("time", TimeUtil.compact(round.secondsLeft()))
                .put("default_entry", economy.provider().format(definition.defaultMoneyEntry()))
                .put("min_money", economy.provider().format(definition.minMoneyEntry()))
                .put("max_money", economy.provider().format(definition.maxMoneyEntry()))
                .put("min_items", definition.minItemsPerEntry())
                .put("max_items", definition.maxItemsPerEntry())
                .put("quick_money_1", quickAmount(definition, 0))
                .put("quick_money_2", quickAmount(definition, 1))
                .put("quick_money_3", quickAmount(definition, 2))
                .put("quick_money_4", quickAmount(definition, 3))
                .put("quick_money_5", quickAmount(definition, 4))
                .put("favorite_money", favoriteAmount(player.getUniqueId()))
                .put("reclaim_money", economy.provider().format(round.playerMoney(player.getUniqueId())))
                .put("reclaim_items", round.playerItemCount(player.getUniqueId()))
                .put("reclaim_tickets", round.playerTicketCount(player.getUniqueId()))
                .put("player_entries", round.playerEntryCount(player.getUniqueId()))
                .put("player_chance", String.format(Locale.US, "%.2f", chance))
                .put("last_winner", "-")
                .put("last_draw_id", lastDraw == null ? "-" : lastDraw.drawId())
                .put("last_draw_hash", lastDraw == null ? "-" : lastDraw.hash())
                .put("last_draw_seed", lastDraw == null ? "-" : lastDraw.seed())
                .put("item", player.getInventory().getItemInMainHand().getType().name())
                .put("value", "0");
    }

    private JackpotRound roundOrPrimary(String jackpotId) {
        JackpotRound round = jackpotId == null || jackpotId.isBlank() ? null : jackpotService.round(jackpotId);
        return round == null ? jackpotService.primaryRound() : round;
    }

    private String roundStatus(JackpotRound round) {
        if (!round.running()) {
            return guiText("status-stopped", new PlaceholderBag());
        }
        if (!round.countdownStarted()) {
            return guiText("status-waiting", new PlaceholderBag());
        }
        return guiText("status-live", new PlaceholderBag());
    }

    private String seasonName(String seasonId) {
        String normalized = seasonId == null || seasonId.isBlank() ? "season-1" : seasonId.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        String localized = switch (lower) {
            case "low-season" -> guiText("season-low", new PlaceholderBag());
            case "high-season" -> guiText("season-high", new PlaceholderBag());
            case "item-season" -> guiText("season-item", new PlaceholderBag());
            case "event-season" -> guiText("season-event", new PlaceholderBag());
            default -> null;
        };
        if (localized != null) {
            return localized;
        }
        if (lower.matches("season[-_ ]?\\d+")) {
            String number = lower.replaceAll("[^0-9]", "");
            return guiText("season-name", new PlaceholderBag().put("number", number));
        }
        String[] parts = normalized.replace('_', '-').split("-");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            words.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1).toLowerCase(Locale.ROOT));
        }
        return words.isEmpty() ? normalized : String.join(" ", words);
    }

    private String quickAmount(JackpotDefinition definition, int index) {
        List<Double> amounts = definition.quickMoneyAmounts();
        double amount = index < amounts.size() ? amounts.get(index) : definition.defaultMoneyEntry();
        return economy.provider().format(amount);
    }

    private String favoriteAmount(UUID playerUuid) {
        Double amount = favoriteAmounts.get(playerUuid);
        return amount == null || amount <= 0.0
                ? guiText("favorite-none", new PlaceholderBag())
                : economy.provider().format(amount);
    }

    public void play(Player player, String menuId, String soundKey) {
        play(player, menus.get(menuId), soundKey);
    }

    public List<String> validate() {
        java.util.ArrayList<String> issues = new java.util.ArrayList<>();
        for (GuiDefinition menu : menus.values()) {
            if (menu.size() < 9 || menu.size() > 54 || menu.size() % 9 != 0) {
                issues.add(menu.id() + ": invalid size " + menu.size());
            }
            if (material(menu.fillerMaterial()) == Material.BARRIER && !"BARRIER".equalsIgnoreCase(menu.fillerMaterial())) {
                issues.add(menu.id() + ": invalid filler material " + menu.fillerMaterial());
            }
            for (GuiItemDefinition item : menu.items().values()) {
                if (item.slot() < 0 || item.slot() >= menu.size()) {
                    issues.add(menu.id() + ":" + item.id() + " slot out of range");
                }
                if (material(item.material()) == Material.BARRIER && !"BARRIER".equalsIgnoreCase(item.material())) {
                    issues.add(menu.id() + ":" + item.id() + " invalid material " + item.material());
                }
            }
            for (RoomGuiDefinition room : menu.rooms().values()) {
                if (room.slot() < 0 || room.slot() >= menu.size()) {
                    issues.add(menu.id() + ":room:" + room.id() + " slot out of range");
                }
                if (material(room.material()) == Material.BARRIER && !"BARRIER".equalsIgnoreCase(room.material())) {
                    issues.add(menu.id() + ":room:" + room.id() + " invalid material " + room.material());
                }
            }
            for (Map.Entry<String, List<Integer>> dynamic : menu.dynamicSlots().entrySet()) {
                for (int slot : dynamic.getValue()) {
                    if (slot < 0 || slot >= menu.size()) {
                        issues.add(menu.id() + ":dynamic:" + dynamic.getKey() + " slot out of range " + slot);
                    }
                    if (menu.items().containsKey(slot)) {
                        issues.add(menu.id() + ":dynamic:" + dynamic.getKey() + " conflicts with static slot " + slot);
                    }
                }
            }
            for (Map.Entry<String, String> sound : menu.sounds().entrySet()) {
                try {
                    Sound.valueOf(sound.getValue());
                } catch (IllegalArgumentException exception) {
                    issues.add(menu.id() + ": invalid sound " + sound.getKey() + "=" + sound.getValue());
                }
            }
        }
        GuiDefinition roomsMenu = menus.get("rooms");
        if (roomsMenu != null) {
            for (String jackpotId : config.jackpots().keySet()) {
                if (!roomsMenu.rooms().containsKey(jackpotId.toLowerCase(Locale.ROOT))) {
                    issues.add("rooms: missing room gui definition for jackpot " + jackpotId);
                }
            }
        }
        return issues;
    }

    private void play(Player player, GuiDefinition definition, String soundKey) {
        if (definition == null) {
            return;
        }
        String name = definition.sounds().get(soundKey);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), Sound.valueOf(name), 0.75f, 1.0f);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private record WatchSession(String jackpotId, Inventory inventory) {
    }
}


