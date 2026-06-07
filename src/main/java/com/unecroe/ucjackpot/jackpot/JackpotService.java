package com.unecroe.ucjackpot.jackpot;

import com.unecroe.ucjackpot.audit.AuditEventType;
import com.unecroe.ucjackpot.audit.AuditService;
import com.unecroe.ucjackpot.config.ConfigService;
import com.unecroe.ucjackpot.config.JackpotDefinition;
import com.unecroe.ucjackpot.config.PluginSettings;
import com.unecroe.ucjackpot.debug.DebugLogger;
import com.unecroe.ucjackpot.economy.EconomyProvider;
import com.unecroe.ucjackpot.economy.EconomyService;
import com.unecroe.ucjackpot.gui.GuiService;
import com.unecroe.ucjackpot.item.ItemSerializer;
import com.unecroe.ucjackpot.item.ItemValue;
import com.unecroe.ucjackpot.item.ItemValuator;
import com.unecroe.ucjackpot.lang.MessageService;
import com.unecroe.ucjackpot.storage.ActiveEntryRecord;
import com.unecroe.ucjackpot.storage.MailboxRecord;
import com.unecroe.ucjackpot.storage.StorageService;
import com.unecroe.ucjackpot.text.PlaceholderBag;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class JackpotService {
    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final EconomyService economyService;
    private final StorageService storage;
    private final AuditService audit;
    private final DebugLogger debug;
    private final MessageService messages;
    private final ItemValuator itemValuator = new ItemValuator();
    private final Map<String, JackpotRound> rounds = new ConcurrentHashMap<>();
    private BukkitTask task;
    private volatile DrawResult lastDraw;
    private GuiService guiService;

    public JackpotService(JavaPlugin plugin, ConfigService configService, EconomyService economyService,
                          StorageService storage, AuditService audit, DebugLogger debug, MessageService messages) {
        this.plugin = plugin;
        this.configService = configService;
        this.economyService = economyService;
        this.storage = storage;
        this.audit = audit;
        this.debug = debug;
        this.messages = messages;
    }

    public void reload() {
        stopTicker();
        rounds.clear();
        for (JackpotDefinition definition : configService.jackpots().values()) {
            rounds.put(definition.id().toLowerCase(Locale.ROOT), new JackpotRound(definition));
        }
        restoreActiveEntries();
        startTicker();
    }

    public void shutdown() {
        stopTicker();
    }

    public JackpotRound primaryRound() {
        return rounds.get(configService.primaryJackpot().id().toLowerCase(Locale.ROOT));
    }

    public JackpotRound round(String jackpotId) {
        if (jackpotId == null || jackpotId.isBlank()) {
            return null;
        }
        return rounds.get(jackpotId.toLowerCase(Locale.ROOT));
    }

    public List<JackpotRound> rounds() {
        return List.copyOf(rounds.values());
    }

    public DrawResult lastDraw() {
        return lastDraw;
    }

    public void guiService(GuiService guiService) {
        this.guiService = guiService;
    }

    private void startTicker() {
        PluginSettings settings = configService.settings();
        long period = Math.max(1L, settings.tickIntervalSeconds()) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    private void stopTicker() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (JackpotRound round : rounds.values()) {
            if (round.isReadyToDraw()) {
                draw(round.definition().id(), false);
            }
        }
    }

    private void restoreActiveEntries() {
        if (!configService.settings().saveActiveEntries()) {
            return;
        }
        storage.loadActiveEntries().thenAccept(records -> Bukkit.getScheduler().runTask(plugin, () -> {
            Map<String, List<JackpotEntry>> grouped = new HashMap<>();
            for (ActiveEntryRecord record : records) {
                JackpotRound round = round(record.jackpotId());
                if (round == null) {
                    continue;
                }
                ItemStack item = record.encodedItem() == null || record.encodedItem().isBlank()
                        ? null
                        : ItemSerializer.decode(record.encodedItem());
                JackpotEntry entry = new JackpotEntry(
                        UUID.fromString(record.entryId()),
                        record.jackpotId(),
                        UUID.fromString(record.playerUuid()),
                        record.playerName(),
                        EntryType.valueOf(record.type()),
                        record.entryValue(),
                        record.moneyAmount(),
                        item,
                        record.createdAt()
                );
                grouped.computeIfAbsent(record.jackpotId().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(entry);
            }
            for (Map.Entry<String, List<JackpotEntry>> entry : grouped.entrySet()) {
                JackpotRound round = rounds.get(entry.getKey());
                if (round != null) {
                    round.restore(entry.getValue());
                    debug.log("draw", "Restored " + entry.getValue().size() + " active entries for " + entry.getKey());
                }
            }
        }));
    }

    public OperationResult joinMoney(Player player, String jackpotId, double amount) {
        JackpotRound round = round(jackpotId);
        if (round == null) {
            return OperationResult.fail("jackpot-not-found");
        }
        JackpotDefinition definition = round.definition();
        if (!definition.acceptsMoney()) {
            return OperationResult.fail("jackpot-disabled");
        }
        OperationResult gate = validateEntryGate(player, round);
        if (!gate.success()) {
            return gate;
        }
        if (amount < definition.minMoneyEntry()) {
            return OperationResult.fail("min-entry");
        }
        if (amount > definition.maxMoneyEntry()) {
            return OperationResult.fail("max-entry");
        }
        EconomyProvider economy = economyService.provider();
        if (!economy.available()) {
            return OperationResult.fail("economy-missing");
        }
        if (!economy.has(player, amount)) {
            return OperationResult.fail("insufficient-money");
        }
        if (!economy.withdraw(player, amount)) {
            return OperationResult.fail("insufficient-money");
        }
        JackpotEntry entry = new JackpotEntry(UUID.randomUUID(), definition.id(), player.getUniqueId(), player.getName(),
                EntryType.MONEY, amount * entryMultiplier(player, round), amount, null, System.currentTimeMillis());
        round.addEntry(entry);
        persistActive(entry);
        checkMilestones(round);
        notifyChanceUpdate(round, player.getUniqueId());
        audit.log(AuditEventType.MONEY_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                "Money entry accepted", "amount=" + amount + ",chance=" + chance(player.getUniqueId(), definition.id()));
        debug.log("draw", "Money entry accepted jackpot=" + definition.id() + " player=" + player.getName()
                + " amount=" + amount + " value=" + entry.value() + " entries=" + round.entries().size());
        return OperationResult.ok("money-entry-success", amount);
    }

    public OperationResult joinItem(Player player, String jackpotId, ItemStack item) {
        return joinItem(player, jackpotId, item, (amount, expected) -> removeFromHand(player, amount, expected));
    }

    public OperationResult joinItemFromInventorySlot(Player player, String jackpotId, int inventorySlot) {
        if (inventorySlot < 0) {
            return OperationResult.fail("item-not-allowed");
        }
        ItemStack item = player.getInventory().getItem(inventorySlot);
        return joinItem(player, jackpotId, item, (amount, expected) -> removeFromInventorySlot(player, inventorySlot, amount, expected));
    }

    public OperationResult validateItemSelection(String jackpotId, ItemStack item) {
        JackpotRound round = round(jackpotId);
        if (round == null) {
            return OperationResult.fail("jackpot-not-found");
        }
        JackpotDefinition definition = round.definition();
        if (!definition.acceptsItems()) {
            return OperationResult.fail("jackpot-disabled");
        }
        ItemValue value = itemValuator.evaluate(configService.settings(), definition, item);
        if (!value.accepted()) {
            if (value.reason().equals("below-min-items")) {
                return OperationResult.fail("min-items", definition.minItemsPerEntry());
            }
            return value.reason().equals("no-value-rule") ? OperationResult.fail("item-no-value") : OperationResult.fail("item-not-allowed");
        }
        return OperationResult.ok("ok", value.value());
    }

    public OperationResult joinItemsFromInventorySlots(Player player, String jackpotId, Map<Integer, String> inventorySlots) {
        JackpotRound round = round(jackpotId);
        if (round == null) {
            return OperationResult.fail("jackpot-not-found");
        }
        JackpotDefinition definition = round.definition();
        if (!definition.acceptsItems()) {
            return OperationResult.fail("jackpot-disabled");
        }
        OperationResult gate = validateEntryGate(player, round);
        if (!gate.success()) {
            return gate;
        }
        Map<Integer, ItemValue> accepted = new LinkedHashMap<>();
        int selectedStacks = 0;
        double totalValue = 0.0;
        for (Integer slot : inventorySlots.keySet()) {
            if (slot == null || slot < 0 || slot >= player.getInventory().getSize()) {
                continue;
            }
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                        "Selected item missing", "slot=" + slot);
                return OperationResult.fail("item-selection-missing");
            }
            String expectedFingerprint = inventorySlots.get(slot);
            if (expectedFingerprint == null || !expectedFingerprint.equals(ItemSerializer.fingerprint(item.clone()))) {
                audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                        "Selected item changed before deposit", "slot=" + slot + ",current=" + itemSummary(item)
                                + ",expected=" + expectedFingerprint + ",currentFingerprint=" + ItemSerializer.fingerprint(item.clone()));
                debug.log("items", "Selected item changed before deposit jackpot=" + definition.id()
                        + " player=" + player.getName() + " slot=" + slot);
                return OperationResult.fail("item-selection-changed");
            }
            if (accepted.containsKey(slot)) {
                continue;
            }
            selectedStacks++;
            if (selectedStacks > definition.maxItemsPerEntry()) {
                return OperationResult.fail("max-items", definition.maxItemsPerEntry());
            }
            ItemValue value = itemValuator.evaluate(configService.settings(), definition, item);
            if (!value.accepted()) {
                audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                        "Batch item rejected", "slot=" + slot + ",reason=" + value.reason());
                debug.log("items", "Batch item rejected jackpot=" + definition.id() + " player=" + player.getName()
                        + " slot=" + slot + " reason=" + value.reason());
                if (value.reason().equals("below-min-items")) {
                    return OperationResult.fail("min-items", definition.minItemsPerEntry());
                }
                return value.reason().equals("no-value-rule") ? OperationResult.fail("item-no-value") : OperationResult.fail("item-not-allowed");
            }
            accepted.put(slot, value);
            totalValue += value.value();
        }
        if (accepted.isEmpty()) {
            return OperationResult.fail("item-selection-empty");
        }
        if (selectedStacks < definition.minItemsPerEntry()) {
            return OperationResult.fail("min-items", definition.minItemsPerEntry());
        }
        if (!player.hasPermission("ucjackpot.bypass.limit")
                && round.playerEntryCount(player.getUniqueId()) + accepted.size() > definition.maxEntriesPerPlayer()) {
            return OperationResult.fail("entry-limit");
        }
        for (Map.Entry<Integer, ItemValue> selected : accepted.entrySet()) {
            ItemValue value = selected.getValue();
            ItemStack current = player.getInventory().getItem(selected.getKey());
            if (current == null || current.getType().isAir()) {
                audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                        "Selected item disappeared during deposit", "slot=" + selected.getKey());
                return OperationResult.fail("item-selection-missing");
            }
            String expectedFingerprint = inventorySlots.get(selected.getKey());
            if (expectedFingerprint == null || !expectedFingerprint.equals(ItemSerializer.fingerprint(current.clone()))) {
                audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                        "Selected item changed during deposit", "slot=" + selected.getKey()
                                + ",current=" + itemSummary(current));
                return OperationResult.fail("item-selection-changed");
            }
            if (!removeFromInventorySlot(player, selected.getKey(), value.normalized().getAmount(), current.clone())) {
                audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                        "Selected item consume failed", "slot=" + selected.getKey() + ",item=" + itemSummary(current));
                return OperationResult.fail("item-selection-changed");
            }
            JackpotEntry entry = new JackpotEntry(UUID.randomUUID(), definition.id(), player.getUniqueId(), player.getName(),
                    EntryType.ITEM, value.value() * entryMultiplier(player, round), 0.0, value.normalized().clone(), System.currentTimeMillis());
            round.addEntry(entry);
            persistActive(entry);
            audit.log(AuditEventType.ITEM_CONSUMED, player.getUniqueId(), player.getName(), definition.id(),
                    "Item consumed for jackpot", "entry=" + entry.id() + ",slot=" + selected.getKey()
                            + ",item=" + itemSummary(value.normalized()) + ",fingerprint=" + ItemSerializer.fingerprint(value.normalized()));
            audit.log(AuditEventType.ITEM_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                    "Item entry accepted", "entry=" + entry.id() + ",item=" + itemSummary(value.normalized())
                            + ",value=" + value.value() + ",chance=" + chance(player.getUniqueId(), definition.id()));
            debug.log("items", "Batch item entry accepted jackpot=" + definition.id() + " player=" + player.getName()
                    + " slot=" + selected.getKey() + " item=" + value.normalized().getType()
                    + " amount=" + value.normalized().getAmount() + " value=" + value.value());
        }
        checkMilestones(round);
        notifyChanceUpdate(round, player.getUniqueId());
        return OperationResult.ok("item-entry-success", totalValue);
    }

    private OperationResult joinItem(Player player, String jackpotId, ItemStack item, ItemRemover remover) {
        ItemStack source = item == null ? null : item.clone();
        JackpotRound round = round(jackpotId);
        if (round == null) {
            return OperationResult.fail("jackpot-not-found");
        }
        JackpotDefinition definition = round.definition();
        if (!definition.acceptsItems()) {
            return OperationResult.fail("jackpot-disabled");
        }
        OperationResult gate = validateEntryGate(player, round);
        if (!gate.success()) {
            return gate;
        }
        ItemValue value = itemValuator.evaluate(configService.settings(), definition, source);
        if (!value.accepted()) {
            audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                    "Item rejected", "reason=" + value.reason());
            debug.log("items", "Item rejected jackpot=" + definition.id() + " player=" + player.getName()
                    + " reason=" + value.reason());
            if (value.reason().equals("below-min-items")) {
                return OperationResult.fail("min-items", definition.minItemsPerEntry());
            }
            return value.reason().equals("no-value-rule") ? OperationResult.fail("item-no-value") : OperationResult.fail("item-not-allowed");
        }
        if (definition.minItemsPerEntry() > 1) {
            return OperationResult.fail("min-items", definition.minItemsPerEntry());
        }
        if (definition.maxItemsPerEntry() < 1) {
            return OperationResult.fail("max-items", definition.maxItemsPerEntry());
        }
        boolean consumed = remover.remove(value.normalized().getAmount(), source);
        if (!consumed) {
            audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                    "Item changed before single deposit", "item=" + itemSummary(source));
            return OperationResult.fail("item-selection-changed");
        }
        JackpotEntry entry = new JackpotEntry(UUID.randomUUID(), definition.id(), player.getUniqueId(), player.getName(),
                EntryType.ITEM, value.value() * entryMultiplier(player, round), 0.0, value.normalized().clone(), System.currentTimeMillis());
        round.addEntry(entry);
        persistActive(entry);
        checkMilestones(round);
        notifyChanceUpdate(round, player.getUniqueId());
        audit.log(AuditEventType.ITEM_CONSUMED, player.getUniqueId(), player.getName(), definition.id(),
                "Item consumed for jackpot", "entry=" + entry.id() + ",item=" + itemSummary(value.normalized())
                        + ",fingerprint=" + ItemSerializer.fingerprint(value.normalized()));
        audit.log(AuditEventType.ITEM_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                "Item entry accepted", "entry=" + entry.id() + ",item=" + itemSummary(value.normalized())
                        + ",value=" + value.value() + ",chance=" + chance(player.getUniqueId(), definition.id()));
        debug.log("items", "Item entry accepted jackpot=" + definition.id() + " player=" + player.getName()
                + " item=" + value.normalized().getType() + " amount=" + value.normalized().getAmount()
                + " value=" + value.value());
        return OperationResult.ok("item-entry-success", value.value());
    }

    public OperationResult joinTicket(Player player, String jackpotId) {
        JackpotRound round = round(jackpotId);
        if (round == null) {
            return OperationResult.fail("jackpot-not-found");
        }
        JackpotDefinition definition = round.definition();
        if (!definition.ticketEnabled()) {
            return OperationResult.fail("ticket-missing");
        }
        OperationResult gate = validateEntryGate(player, round);
        if (!gate.success()) {
            return gate;
        }
        int slot = findTicketSlot(player, definition);
        if (slot < 0) {
            return OperationResult.fail("ticket-missing");
        }
        ItemStack ticket = player.getInventory().getItem(slot);
        if (!removeFromInventorySlot(player, slot, 1, ticket == null ? null : ticket.clone())) {
            audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                    "Ticket changed before deposit", "slot=" + slot + ",item=" + itemSummary(ticket));
            return OperationResult.fail("ticket-missing");
        }
        JackpotEntry entry = new JackpotEntry(UUID.randomUUID(), definition.id(), player.getUniqueId(), player.getName(),
                EntryType.TICKET, definition.ticketEntryValue() * entryMultiplier(player, round), 0.0, null, System.currentTimeMillis());
        round.addEntry(entry);
        persistActive(entry);
        checkMilestones(round);
        notifyChanceUpdate(round, player.getUniqueId());
        audit.log(AuditEventType.TICKET_ENTRY, player.getUniqueId(), player.getName(), definition.id(),
                "Ticket entry accepted", "entry=" + entry.id() + ",slot=" + slot
                        + ",value=" + definition.ticketEntryValue() + ",chance=" + chance(player.getUniqueId(), definition.id()));
        debug.log("tickets", "Ticket entry accepted jackpot=" + definition.id() + " player=" + player.getName()
                + " value=" + entry.value() + " entries=" + round.entries().size());
        return OperationResult.ok("ticket-entry-success", definition.ticketEntryValue());
    }

    private int findTicketSlot(Player player, JackpotDefinition definition) {
        Material material = Material.matchMaterial(definition.ticketMaterial());
        if (material == null) {
            material = Material.PAPER;
        }
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType() != material || item.getAmount() <= 0) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (definition.ticketNameContains().isBlank()
                    || (meta != null && meta.hasDisplayName()
                    && meta.getDisplayName().toLowerCase(Locale.ROOT).contains(definition.ticketNameContains().toLowerCase(Locale.ROOT)))) {
                return slot;
            }
        }
        return -1;
    }

    private OperationResult validateEntryGate(Player player, JackpotRound round) {
        if (round.drawing()) {
            return OperationResult.fail("draw-in-progress");
        }
        PluginSettings settings = configService.settings();
        if (settings.blockedWorlds().contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return OperationResult.fail("blocked-world");
        }
        if (!player.hasPermission("ucjackpot.bypass.cooldown")) {
            long cooldown = round.cooldownLeft(player.getUniqueId());
            if (cooldown > 0) {
                return OperationResult.wait("entry-cooldown", cooldown);
            }
        }
        if (!player.hasPermission("ucjackpot.bypass.limit")
                && round.playerEntryCount(player.getUniqueId()) >= round.definition().maxEntriesPerPlayer()) {
            return OperationResult.fail("entry-limit");
        }
        return OperationResult.ok("ok", 0.0);
    }

    private boolean removeFromHand(Player player, int amount, ItemStack expected) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!sameStack(hand, expected)) {
            return false;
        }
        if (hand.getAmount() <= amount) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - amount);
            player.getInventory().setItemInMainHand(hand);
        }
        return true;
    }

    private boolean removeFromInventorySlot(Player player, int slot, int amount, ItemStack expected) {
        ItemStack item = player.getInventory().getItem(slot);
        if (!sameStack(item, expected)) {
            return false;
        }
        if (item.getAmount() <= amount) {
            player.getInventory().setItem(slot, null);
        } else {
            item.setAmount(item.getAmount() - amount);
            player.getInventory().setItem(slot, item);
        }
        return true;
    }

    private boolean sameStack(ItemStack current, ItemStack expected) {
        if (current == null || current.getType().isAir() || expected == null || expected.getType().isAir()) {
            return false;
        }
        return ItemSerializer.fingerprint(current.clone()).equals(ItemSerializer.fingerprint(expected.clone()));
    }

    private String itemSummary(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "empty";
        }
        return item.getType().name() + "x" + item.getAmount();
    }

    private void persistActive(JackpotEntry entry) {
        if (!configService.settings().saveActiveEntries()) {
            return;
        }
        storage.saveActiveEntry(new ActiveEntryRecord(
                entry.id().toString(),
                entry.jackpotId(),
                entry.playerUuid().toString(),
                entry.playerName(),
                entry.type().name(),
                entry.value(),
                entry.moneyAmount(),
                entry.item() == null ? null : ItemSerializer.encode(entry.item()),
                entry.createdAt()
        ));
    }

    public DrawResult draw(String jackpotId, boolean forced) {
        JackpotRound round = round(jackpotId);
        if (round == null || !round.markDrawing()) {
            return null;
        }
        List<JackpotEntry> entries = round.entries();
        if (!forced && (round.participantCount() < round.definition().minPlayers()
                || round.totalValue() < round.definition().minTotalValue())) {
            round.postpone();
            return null;
        }
        if (entries.isEmpty()) {
            round.restart();
            return null;
        }
        String drawId = UUID.randomUUID().toString();
        String seed = UUID.randomUUID() + ":" + System.nanoTime();
        JackpotEntry winningEntry = selectWinner(entries, seed);
        UUID winnerUuid = winningEntry.playerUuid();
        String winnerName = winningEntry.playerName();
        double moneyPrize = Math.max(0.0, round.moneyPot() * (1.0 - round.definition().taxPercent() / 100.0));
        double itemValue = round.itemValue();
        int itemCount = round.itemCount();
        long createdAt = System.currentTimeMillis();
        String hash = drawHash(drawId, seed, entries);
        DrawResult result = new DrawResult(drawId, round.definition().id(), winnerUuid, winnerName, seed, hash,
                moneyPrize, itemValue, itemCount, moneyPrize + itemValue, entries.size(), createdAt);
        lastDraw = result;
        long winnerBroadcastDelay = playDrawAnimation(entries, result);
        if (winnerBroadcastDelay > 0L) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> finishDraw(round, entries, winningEntry, result, forced), winnerBroadcastDelay);
        } else {
            finishDraw(round, entries, winningEntry, result, forced);
        }
        return result;
    }

    private void finishDraw(JackpotRound round, List<JackpotEntry> entries, JackpotEntry winningEntry,
                            DrawResult result, boolean forced) {
        JackpotDefinition definition = round.definition();
        storage.recordHistory(definition.id(), result.winnerUuid(), result.winnerName(), result.totalValue());
        storage.recordDetailedDraw(result, entries);
        storage.updateSeasonStats(definition.seasonId(), entries, result);
        storage.clearActiveEntries(definition.id());
        OfflinePlayer winner = Bukkit.getOfflinePlayer(result.winnerUuid());
        EconomyProvider economy = economyService.provider();
        if (result.moneyPrize() > 0 && economy.available()) {
            economy.deposit(winner, result.moneyPrize());
            audit.log(AuditEventType.PAYOUT, result.winnerUuid(), result.winnerName(), definition.id(),
                    "Money prize delivered", "draw=" + result.drawId() + ",amount=" + result.moneyPrize());
        }
        if (definition.winnerTakesItems()) {
            for (JackpotEntry entry : entries) {
                if (entry.type() == EntryType.ITEM && entry.item() != null) {
                    deliverItem(result.winnerUuid(), result.winnerName(), definition.id(), entry.item(),
                            "jackpot-win,draw=" + result.drawId() + ",entry=" + entry.id());
                }
            }
        }
        audit.log(AuditEventType.DRAW, result.winnerUuid(), result.winnerName(), definition.id(),
                "Draw completed", "draw=" + result.drawId() + ",hash=" + result.hash() + ",money=" + result.moneyPrize()
                        + ",items=" + result.itemValue() + ",entries=" + entries.size());
        debug.log("draw", "Draw finalized jackpot=" + definition.id() + " draw=" + result.drawId()
                + " winner=" + result.winnerName() + " hash=" + result.hash() + " money=" + result.moneyPrize()
                + " itemCount=" + result.itemCount() + " entries=" + entries.size() + " forced=" + forced);
        runConsolation(round, entries, winningEntry);
        runRareBonus(result, definition);
        if (guiService != null) {
            guiService.showWatchWinner(result);
        }
        broadcastWinner(result);
        round.restart();
    }

    public OperationResult cancel(String jackpotId) {
        JackpotRound round = round(jackpotId);
        if (round == null) {
            return OperationResult.fail("jackpot-not-found");
        }
        if (round.drawing()) {
            return OperationResult.fail("draw-in-progress");
        }
        List<JackpotEntry> entries = round.entries();
        EconomyProvider economy = economyService.provider();
        for (JackpotEntry entry : entries) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.playerUuid());
            if (entry.type() == EntryType.MONEY && economy.available()) {
                economy.deposit(player, entry.moneyAmount());
                audit.log(AuditEventType.REFUND, entry.playerUuid(), entry.playerName(), round.definition().id(),
                        "Money refunded on cancel", "entry=" + entry.id() + ",amount=" + entry.moneyAmount());
            } else if (entry.type() == EntryType.ITEM && entry.item() != null) {
                deliverItem(entry.playerUuid(), entry.playerName(), round.definition().id(), entry.item(),
                        "jackpot-refund,entry=" + entry.id());
            }
        }
        storage.clearActiveEntries(round.definition().id());
        audit.log(AuditEventType.REFUND, null, "console", round.definition().id(),
                "Round cancelled and refunded", "entries=" + entries.size());
        debug.log("draw", "Round cancelled jackpot=" + round.definition().id() + " refundedEntries=" + entries.size());
        round.restart();
        return OperationResult.ok("draw-cancelled", entries.size());
    }

    public OperationResult reclaim(Player player, String jackpotId) {
        JackpotRound round = round(jackpotId);
        if (round == null) {
            return OperationResult.fail("jackpot-not-found");
        }
        if (round.countdownStarted()) {
            return OperationResult.fail("reclaim-locked");
        }
        List<JackpotEntry> playerEntries = round.entries().stream()
                .filter(entry -> entry.playerUuid().equals(player.getUniqueId()))
                .toList();
        if (playerEntries.isEmpty()) {
            return OperationResult.fail("reclaim-empty");
        }
        double money = playerEntries.stream().mapToDouble(JackpotEntry::moneyAmount).sum();
        EconomyProvider economy = economyService.provider();
        if (money > 0.0 && !economy.available()) {
            return OperationResult.fail("reclaim-economy-missing");
        }
        List<JackpotEntry> removed = round.removeEntries(player.getUniqueId());
        if (removed.isEmpty()) {
            return OperationResult.fail("reclaim-empty");
        }
        for (JackpotEntry entry : removed) {
            if (entry.type() == EntryType.MONEY && entry.moneyAmount() > 0.0) {
                economy.deposit(player, entry.moneyAmount());
                audit.log(AuditEventType.REFUND, player.getUniqueId(), player.getName(), round.definition().id(),
                        "Money reclaimed", "entry=" + entry.id() + ",amount=" + entry.moneyAmount());
            } else if (entry.type() == EntryType.ITEM && entry.item() != null) {
                deliverItem(player.getUniqueId(), player.getName(), round.definition().id(), entry.item(),
                        "jackpot-reclaim,entry=" + entry.id());
            } else if (entry.type() == EntryType.TICKET) {
                deliverItem(player.getUniqueId(), player.getName(), round.definition().id(), ticketItem(round.definition()),
                        "ticket-reclaim,entry=" + entry.id());
            }
        }
        storage.clearActiveEntries(round.definition().id(), player.getUniqueId());
        audit.log(AuditEventType.REFUND, player.getUniqueId(), player.getName(), round.definition().id(),
                "Player reclaimed waiting entries", "entries=" + removed.size() + ",money=" + money);
        debug.log("draw", "Player reclaimed entries jackpot=" + round.definition().id() + " player=" + player.getName()
                + " entries=" + removed.size() + " money=" + money);
        return OperationResult.ok("reclaim-success", money);
    }

    private JackpotEntry selectWinner(List<JackpotEntry> entries, String seed) {
        double total = entries.stream().mapToDouble(JackpotEntry::value).filter(value -> value > 0).sum();
        if (total <= 0.0) {
            throw new IllegalArgumentException("Total weight must be positive");
        }
        double target = seedTarget(seed, total);
        double cursor = 0.0;
        for (JackpotEntry entry : entries) {
            cursor += Math.max(0.0, entry.value());
            if (target <= cursor) {
                return entry;
            }
        }
        return entries.getLast();
    }

    private double seedTarget(String seed, double total) {
        byte[] digest = sha256(seed);
        long value = 0L;
        for (int index = 0; index < 8; index++) {
            value = (value << 8) | (digest[index] & 0xffL);
        }
        long positive = value & Long.MAX_VALUE;
        return positive / (double) Long.MAX_VALUE * total;
    }

    private String drawHash(String drawId, String seed, List<JackpotEntry> entries) {
        StringBuilder canonical = new StringBuilder(drawId).append('|').append(seed);
        entries.stream()
                .sorted(Comparator.comparing(entry -> entry.id().toString()))
                .forEach(entry -> canonical.append('|')
                        .append(entry.id()).append(';')
                        .append(entry.playerUuid()).append(';')
                        .append(entry.type()).append(';')
                        .append(String.format(Locale.US, "%.4f", entry.value())).append(';')
                        .append(String.format(Locale.US, "%.4f", entry.moneyAmount())).append(';')
                        .append(entry.item() == null ? "-" : entry.item().getType().name() + ":" + entry.item().getAmount()
                                + ":" + ItemSerializer.fingerprint(entry.item())));
        return hex(sha256(canonical.toString()));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    private long playDrawAnimation(List<JackpotEntry> entries, DrawResult result) {
        List<String> names = entries.stream().map(JackpotEntry::playerName).distinct().toList();
        long durationTicks = Math.max(1L, configService.settings().drawAnimationSeconds()) * 20L;
        if (names.isEmpty()) {
            return durationTicks;
        }
        List<UUID> participantUuids = entries.stream()
                .map(JackpotEntry::playerUuid)
                .distinct()
                .toList();
        if (!configService.settings().drawTitlesEnabled()) {
            debug.log("draw", "Draw title animation skipped draw=" + result.drawId() + " reason=disabled");
            return durationTicks;
        }
        long framePeriodTicks = 10L;
        int frames = Math.max(1, (int) (durationTicks / framePeriodTicks));
        debug.log("draw", "Draw animation scheduled draw=" + result.drawId() + " frames=" + frames
                + " candidates=" + names.size() + " participants=" + participantUuids.size());
        storage.titleNotifications(participantUuids).thenAccept(settings -> Bukkit.getScheduler().runTask(plugin, () -> {
            List<UUID> targets = participantUuids.stream()
                    .filter(uuid -> !configService.settings().drawTitlesPlayerToggle()
                            || settings.getOrDefault(uuid, true))
                    .toList();
            if (targets.isEmpty()) {
                debug.log("draw", "Draw animation skipped draw=" + result.drawId() + " reason=no-targets");
                return;
            }
            for (int index = 0; index < frames; index++) {
                String previewName = names.get(index % names.size());
                float pitch = 0.8f + index * 0.03f;
                Bukkit.getScheduler().runTaskLater(plugin, () -> sendDrawFrame(targets, previewName, pitch), index * framePeriodTicks);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> sendDrawWinnerFrame(targets, result), durationTicks);
        }));
        return durationTicks;
    }

    private void sendDrawFrame(List<UUID> targets, String name, float pitch) {
        for (UUID uuid : targets) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            PlaceholderBag bag = new PlaceholderBag().put("player", name);
            player.sendTitle(messages.format("draw-animation-title", bag),
                    messages.format("draw-animation-candidate", bag), 0, 16, 0);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, pitch);
        }
    }

    private void sendDrawWinnerFrame(List<UUID> targets, DrawResult result) {
        for (UUID uuid : targets) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            PlaceholderBag bag = new PlaceholderBag().put("player", result.winnerName());
            player.sendTitle(messages.format("draw-animation-winner-title", bag),
                    messages.format("draw-animation-winner-subtitle", bag), 5, 45, 10);
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.85f, 1.0f);
        }
    }

    public OperationResult start(String jackpotId) {
        JackpotRound round = round(jackpotId);
        if (round == null) {
            return OperationResult.fail("jackpot-not-found");
        }
        if (round.drawing()) {
            return OperationResult.fail("draw-in-progress");
        }
        round.restart();
        storage.clearActiveEntries(round.definition().id());
        audit.log(AuditEventType.ADMIN_ACTION, null, "console", jackpotId, "Round started", "");
        debug.log("draw", "Round started jackpot=" + round.definition().id());
        return OperationResult.ok("admin-action", 0.0);
    }

    public OperationResult stop(String jackpotId) {
        JackpotRound round = round(jackpotId);
        if (round == null) {
            return OperationResult.fail("jackpot-not-found");
        }
        if (round.drawing()) {
            return OperationResult.fail("draw-in-progress");
        }
        round.stop();
        audit.log(AuditEventType.ADMIN_ACTION, null, "console", jackpotId, "Round stopped", "");
        debug.log("draw", "Round stopped jackpot=" + round.definition().id());
        return OperationResult.ok("admin-action", 0.0);
    }

    private ItemStack ticketItem(JackpotDefinition definition) {
        Material material = Material.matchMaterial(definition.ticketMaterial());
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null && !definition.ticketNameContains().isBlank()) {
            meta.setDisplayName(definition.ticketNameContains());
            item.setItemMeta(meta);
        }
        return item;
    }

    private void deliverItem(UUID playerUuid, String playerName, String jackpotId, ItemStack item, String reason) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return;
        }
        ItemStack prize = item.clone();
        int originalAmount = prize.getAmount();
        Player online = Bukkit.getPlayer(playerUuid);
        if (online != null && online.isOnline()) {
            Map<Integer, ItemStack> overflow = online.getInventory().addItem(prize.clone());
            int overflowAmount = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
            int deliveredAmount = Math.max(0, originalAmount - overflowAmount);
            if (deliveredAmount > 0) {
                audit.log(AuditEventType.PAYOUT, playerUuid, playerName, jackpotId, "Item delivered to inventory",
                        "reason=" + reason + ",item=" + prize.getType().name()
                                + ",delivered=" + deliveredAmount + ",overflow=" + overflowAmount
                                + ",fingerprint=" + ItemSerializer.fingerprint(prize));
                debug.log("items", "Item delivered player=" + playerName + " uuid=" + playerUuid
                        + " jackpot=" + jackpotId + " item=" + prize.getType().name()
                        + " delivered=" + deliveredAmount + " overflow=" + overflowAmount + " reason=" + reason);
            }
            if (overflow.isEmpty()) {
                return;
            }
            for (ItemStack leftover : overflow.values()) {
                storage.addMailboxItem(playerUuid, playerName, ItemSerializer.encode(leftover), reason);
                audit.log(AuditEventType.MAILBOX, playerUuid, playerName, jackpotId, "Item queued in mailbox",
                        "reason=" + reason + ",item=" + itemSummary(leftover)
                                + ",fingerprint=" + ItemSerializer.fingerprint(leftover));
            }
        } else {
            storage.addMailboxItem(playerUuid, playerName, ItemSerializer.encode(prize), reason);
            audit.log(AuditEventType.MAILBOX, playerUuid, playerName, jackpotId, "Item queued in mailbox",
                    "reason=" + reason + ",item=" + itemSummary(prize)
                            + ",fingerprint=" + ItemSerializer.fingerprint(prize) + ",offline=true");
        }
    }

    public void claimMailbox(Player player, java.util.function.IntConsumer callback) {
        storage.claimMailbox(player.getUniqueId()).thenAccept(records -> Bukkit.getScheduler().runTask(plugin, () -> {
            int delivered = 0;
            List<ItemStack> failed = new ArrayList<>();
            for (MailboxRecord record : records) {
                ItemStack item;
                try {
                    item = ItemSerializer.decode(record.encodedItem());
                } catch (RuntimeException exception) {
                    audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), null,
                            "Mailbox item decode failed", "mailbox=" + record.id() + ",reason=" + record.reason()
                                    + ",error=" + exception.getMessage());
                    debug.warn("items", "Mailbox item decode failed id=" + record.id()
                            + " player=" + player.getName(), exception);
                    continue;
                }
                ItemStack original = item.clone();
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(original.clone());
                int overflowAmount = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
                int deliveredAmount = Math.max(0, original.getAmount() - overflowAmount);
                if (deliveredAmount > 0) {
                    audit.log(AuditEventType.MAILBOX_CLAIM, player.getUniqueId(), player.getName(), null,
                            "Mailbox item delivered", "mailbox=" + record.id() + ",reason=" + record.reason()
                                    + ",item=" + original.getType().name() + ",delivered=" + deliveredAmount
                                    + ",overflow=" + overflowAmount + ",fingerprint=" + ItemSerializer.fingerprint(original));
                }
                if (overflow.isEmpty()) {
                    delivered++;
                } else {
                    failed.addAll(overflow.values());
                }
            }
            for (ItemStack item : failed) {
                storage.addMailboxItem(player.getUniqueId(), player.getName(), ItemSerializer.encode(item), "mailbox-overflow");
                audit.log(AuditEventType.MAILBOX, player.getUniqueId(), player.getName(), null,
                        "Mailbox item returned to mailbox", "item=" + itemSummary(item)
                                + ",fingerprint=" + ItemSerializer.fingerprint(item));
            }
            debug.log("items", "Mailbox claim player=" + player.getName() + " uuid=" + player.getUniqueId()
                    + " records=" + records.size() + " delivered=" + delivered + " failed=" + failed.size());
            callback.accept(delivered);
        }));
    }

    public double chance(UUID playerUuid, String jackpotId) {
        JackpotRound round = round(jackpotId);
        if (round == null || round.totalValue() <= 0) {
            return 0.0;
        }
        return round.playerValue(playerUuid) / round.totalValue() * 100.0;
    }

    public double primaryChance(UUID playerUuid) {
        JackpotRound round = primaryRound();
        return round == null ? 0.0 : chance(playerUuid, round.definition().id());
    }

    public int primaryPlayerEntries(UUID playerUuid) {
        JackpotRound round = primaryRound();
        return round == null ? 0 : round.playerEntryCount(playerUuid);
    }

    private void runConsolation(JackpotRound round, List<JackpotEntry> entries, JackpotEntry winningEntry) {
        if (!round.definition().consolationEnabled() || entries.size() < round.definition().consolationMinEntries()) {
            return;
        }
        for (JackpotEntry entry : entries) {
            if (entry.playerUuid().equals(winningEntry.playerUuid())) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.playerUuid());
            if (player == null) {
                continue;
            }
            for (String command : round.definition().consolationCommands()) {
                String parsed = command.replace("%player%", player.getName())
                        .replace("%jackpot%", round.definition().id());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
            }
        }
    }

    private double entryMultiplier(Player player, JackpotRound round) {
        JackpotDefinition definition = round.definition();
        if (!definition.comboEnabled()) {
            return 1.0;
        }
        int previousEntries = round.playerEntryCount(player.getUniqueId());
        double bonus = Math.min(definition.comboMaxPercent(), previousEntries * definition.comboStepPercent());
        return 1.0 + Math.max(0.0, bonus) / 100.0;
    }

    private void checkMilestones(JackpotRound round) {
        double total = round.totalValue();
        for (double milestone : round.definition().milestoneValues()) {
            if (total >= milestone && round.markMilestone(milestone)) {
                messages.broadcast("milestone-broadcast",
                        new PlaceholderBag().put("amount", String.format(Locale.US, "%.2f", milestone)),
                        "ucjackpot.use");
            }
        }
    }

    private void notifyChanceUpdate(JackpotRound round, UUID actorUuid) {
        String mode = chanceUpdateMode();
        if ("off".equals(mode)) {
            return;
        }
        Set<UUID> participants = new LinkedHashSet<>();
        for (JackpotEntry entry : round.entries()) {
            participants.add(entry.playerUuid());
        }
        boolean includeActor = configService.settings().chanceUpdateIncludeActor();
        for (UUID uuid : participants) {
            if (!includeActor && uuid.equals(actorUuid)) {
                continue;
            }
            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) {
                continue;
            }
            PlaceholderBag bag = new PlaceholderBag()
                    .put("jackpot", round.definition().displayName())
                    .put("chance", String.format(Locale.US, "%.2f", chance(uuid, round.definition().id())));
            if ("chat".equals(mode)) {
                messages.send(target, "chance-update", bag);
            } else {
                messages.actionBar(target, "chance-update", bag);
            }
            debug.log("draw", "Chance update sent jackpot=" + round.definition().id()
                    + " target=" + target.getName() + " targetUuid=" + uuid
                    + " actorUuid=" + actorUuid + " mode=" + mode);
        }
    }

    private String chanceUpdateMode() {
        String mode = configService.settings().chanceUpdateMode();
        return switch (mode == null ? "" : mode.toLowerCase(Locale.ROOT)) {
            case "chat" -> "chat";
            case "off" -> "off";
            default -> "actionbar";
        };
    }

    private void runRareBonus(DrawResult result, JackpotDefinition definition) {
        if (!definition.rareBonusEnabled() || definition.rareBonusCommands().isEmpty()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble(100.0) >= definition.rareBonusChancePercent()) {
            return;
        }
        for (String command : definition.rareBonusCommands()) {
            String parsed = command.replace("%player%", result.winnerName())
                    .replace("%jackpot%", result.jackpotId())
                    .replace("%money%", String.format(Locale.US, "%.2f", result.moneyPrize()))
                    .replace("%items%", String.valueOf(result.itemCount()));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
        messages.broadcast("rare-bonus-broadcast", new PlaceholderBag()
                .put("player", result.winnerName())
                .put("jackpot", result.jackpotId())
                .put("money", String.format(Locale.US, "%.2f", result.moneyPrize()))
                .put("items", result.itemCount()), "ucjackpot.use");
    }

    private void broadcastWinner(DrawResult result) {
        messages.broadcast("draw-winner-broadcast", new PlaceholderBag()
                .put("player", result.winnerName())
                .put("money", String.format(Locale.US, "%.2f", result.moneyPrize()))
                .put("items", result.itemCount())
                .put("draw", result.drawId()), "ucjackpot.use");
    }

    @FunctionalInterface
    private interface ItemRemover {
        boolean remove(int amount, ItemStack expected);
    }
}


