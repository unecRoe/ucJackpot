package com.unecroe.ucjackpot.gui;

import com.unecroe.ucjackpot.audit.AuditEventType;
import com.unecroe.ucjackpot.audit.AuditService;
import com.unecroe.ucjackpot.config.ConfigService;
import com.unecroe.ucjackpot.economy.EconomyService;
import com.unecroe.ucjackpot.item.ItemSerializer;
import com.unecroe.ucjackpot.jackpot.JackpotRound;
import com.unecroe.ucjackpot.jackpot.JackpotService;
import com.unecroe.ucjackpot.jackpot.OperationResult;
import com.unecroe.ucjackpot.lang.MessageService;
import com.unecroe.ucjackpot.storage.StorageService;
import com.unecroe.ucjackpot.text.PlaceholderBag;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiListener implements Listener {
    private final JavaPlugin plugin;
    private final ConfigService config;
    private final EconomyService economy;
    private final JackpotService jackpot;
    private final GuiService gui;
    private final MessageService messages;
    private final StorageService storage;
    private final AuditService audit;
    private final Map<UUID, Map<Integer, String>> selectedItems = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingMoneyInput = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingFavoriteInput = new ConcurrentHashMap<>();
    private final Map<UUID, Double> favoriteAmounts = new ConcurrentHashMap<>();
    private final Set<UUID> pendingSpecialItemApproval = ConcurrentHashMap.newKeySet();

    public GuiListener(JavaPlugin plugin, ConfigService config, EconomyService economy, JackpotService jackpot,
                       GuiService gui, MessageService messages, StorageService storage, AuditService audit) {
        this.plugin = plugin;
        this.config = config;
        this.economy = economy;
        this.jackpot = jackpot;
        this.gui = gui;
        this.messages = messages;
        this.storage = storage;
        this.audit = audit;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof JackpotMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (holder.menuId().equals("confirm-item") && isPlayerInventoryClick(event, player)) {
            toggleItemSelection(player, holder.jackpotId(), event.getSlot());
            return;
        }
        String action = holder.action(event.getRawSlot());
        if (action == null || action.equals("none")) {
            return;
        }
        handle(player, holder.jackpotId(), action);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String moneyJackpot = pendingMoneyInput.remove(player.getUniqueId());
        String favoriteJackpot = pendingFavoriteInput.remove(player.getUniqueId());
        if (moneyJackpot == null && favoriteJackpot == null) {
            return;
        }
        event.setCancelled(true);
        String raw = event.getMessage().trim().replace(",", ".");
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                double amount = Double.parseDouble(raw);
                if (favoriteJackpot != null) {
                    favoriteAmounts.put(player.getUniqueId(), amount);
                    gui.cacheFavoriteAmount(player.getUniqueId(), amount);
                    storage.saveFavoriteAmount(player.getUniqueId(), player.getName(), amount);
                    audit.log(AuditEventType.FAVORITE_AMOUNT, player.getUniqueId(), player.getName(), favoriteJackpot,
                            "Favorite amount saved", "amount=" + amount);
                    messages.send(player, "favorite-saved", new PlaceholderBag().put("amount", economy.provider().format(amount)));
                    gui.open(player, "money", favoriteJackpot);
                } else {
                    joinMoney(player, round(moneyJackpot), amount);
                }
            } catch (NumberFormatException exception) {
                messages.send(player, "invalid-number");
            }
        });
    }

    private void handle(Player player, String jackpotId, String action) {
        JackpotRound round = round(jackpotId);
        if (action.startsWith("money-quick-")) {
            joinQuickMoney(player, round, action);
            return;
        }
        if (action.startsWith("deposits-player:")) {
            openDepositDetail(player, round.definition().id(), action);
            return;
        }
        if (action.startsWith("history-draw:")) {
            gui.openHistoryDetail(player, action.substring("history-draw:".length()));
            return;
        }
        if (action.startsWith("room:")) {
            gui.open(player, "main", action.substring("room:".length()));
            return;
        }
        switch (action) {
            case "rooms" -> gui.open(player, "rooms");
            case "preview" -> gui.open(player, "preview", round.definition().id());
            case "fairness" -> gui.open(player, "fairness", round.definition().id());
            case "watch" -> gui.open(player, "watch", round.definition().id());
            case "season" -> gui.openSeason(player, round.definition().id());
            case "ticket" -> joinTicket(player, round);
            case "reclaim" -> reclaim(player, round);
            case "money-menu" -> gui.open(player, "money", round.definition().id());
            case "money-manual" -> requestManualMoney(player, round.definition().id());
            case "money-favorite" -> joinFavoriteMoney(player, round);
            case "money-favorite-set" -> requestFavoriteMoney(player, round.definition().id());
            case "join-money" -> joinMoney(player, round, round.definition().defaultMoneyEntry());
            case "item-confirm" -> {
                selectedItems.remove(player.getUniqueId());
                pendingSpecialItemApproval.remove(player.getUniqueId());
                gui.open(player, "confirm-item", round.definition().id());
            }
            case "confirm-item" -> joinSelectedItems(player, round);
            case "stats" -> gui.open(player, "stats", round.definition().id());
            case "history" -> gui.open(player, "history");
            case "deposits" -> gui.open(player, "deposits", round.definition().id());
            case "open-main" -> gui.open(player, "main", round.definition().id());
            case "close" -> player.closeInventory();
            default -> {
            }
        }
    }

    private boolean isPlayerInventoryClick(InventoryClickEvent event, Player player) {
        Inventory clicked = event.getClickedInventory();
        ItemStack item = event.getCurrentItem();
        return clicked != null
                && clicked.equals(player.getInventory())
                && item != null
                && !item.getType().isAir();
    }

    private void joinQuickMoney(Player player, JackpotRound round, String action) {
        int index;
        try {
            index = Integer.parseInt(action.substring("money-quick-".length())) - 1;
        } catch (NumberFormatException exception) {
            messages.send(player, "invalid-number");
            return;
        }
        double amount = index >= 0 && round.definition().quickMoneyAmounts().size() > index
                ? round.definition().quickMoneyAmounts().get(index)
                : round.definition().defaultMoneyEntry();
        joinMoney(player, round, amount);
    }

    private JackpotRound round(String jackpotId) {
        JackpotRound round = jackpotId == null || jackpotId.isBlank() ? null : jackpot.round(jackpotId);
        return round == null ? jackpot.primaryRound() : round;
    }

    private void requestManualMoney(Player player, String jackpotId) {
        if (!player.hasPermission("ucjackpot.join.money")) {
            messages.send(player, "no-permission");
            return;
        }
        pendingMoneyInput.put(player.getUniqueId(), jackpotId);
        player.closeInventory();
        messages.send(player, "money-manual-prompt");
    }

    private void requestFavoriteMoney(Player player, String jackpotId) {
        pendingFavoriteInput.put(player.getUniqueId(), jackpotId);
        player.closeInventory();
        messages.send(player, "favorite-prompt");
    }

    private void openDepositDetail(Player player, String jackpotId, String action) {
        try {
            gui.openDepositDetail(player, jackpotId, UUID.fromString(action.substring("deposits-player:".length())));
        } catch (IllegalArgumentException exception) {
            gui.open(player, "deposits", jackpotId);
        }
    }

    private void joinMoney(Player player, JackpotRound round, double amount) {
        if (!player.hasPermission("ucjackpot.join.money")) {
            messages.send(player, "no-permission");
            return;
        }
        OperationResult result = jackpot.joinMoney(player, round.definition().id(), amount);
        sendResult(player, result, new PlaceholderBag()
                .put("amount", economy.provider().format(amount))
                .put("chance", String.format(java.util.Locale.US, "%.2f", jackpot.primaryChance(player.getUniqueId()))));
        gui.open(player, "main", round.definition().id());
    }

    private void joinFavoriteMoney(Player player, JackpotRound round) {
        Double cached = favoriteAmounts.get(player.getUniqueId());
        if (cached != null && cached > 0.0) {
            joinMoney(player, round, cached);
            return;
        }
        storage.favoriteAmount(player.getUniqueId()).thenAccept(amount -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            boolean hasFavorite = amount != null && amount > 0.0;
            double resolved = hasFavorite ? amount : round.definition().defaultMoneyEntry();
            favoriteAmounts.put(player.getUniqueId(), resolved);
            gui.cacheFavoriteAmount(player.getUniqueId(), hasFavorite ? resolved : 0.0);
            joinMoney(player, round, resolved);
        }));
    }

    private void joinSelectedItems(Player player, JackpotRound round) {
        if (!player.hasPermission("ucjackpot.join.item")) {
            messages.send(player, "no-permission");
            return;
        }
        Map<Integer, String> selected = selectedItems.get(player.getUniqueId());
        if (round.definition().specialItemProtection() && hasSpecialSelectedItem(player, selected)
                && !pendingSpecialItemApproval.remove(player.getUniqueId())) {
            pendingSpecialItemApproval.add(player.getUniqueId());
            messages.send(player, "special-item-warning");
            return;
        }
        selectedItems.remove(player.getUniqueId());
        OperationResult result = jackpot.joinItemsFromInventorySlots(player, round.definition().id(),
                selected == null ? java.util.Map.of() : Map.copyOf(selected));
        sendResult(player, result, new PlaceholderBag()
                .put("item", selected == null ? "0" : selected.size())
                .put("amount", String.format(Locale.US, "%.0f", result.value()))
                .put("value", economy.provider().format(result.value()))
                .put("chance", String.format(Locale.US, "%.2f", jackpot.primaryChance(player.getUniqueId()))));
        gui.open(player, "main", round.definition().id());
    }

    private void joinTicket(Player player, JackpotRound round) {
        if (!player.hasPermission("ucjackpot.join.ticket")) {
            messages.send(player, "no-permission");
            return;
        }
        OperationResult result = jackpot.joinTicket(player, round.definition().id());
        sendResult(player, result, new PlaceholderBag()
                .put("amount", economy.provider().format(result.value()))
                .put("chance", String.format(Locale.US, "%.2f", jackpot.chance(player.getUniqueId(), round.definition().id()))));
        gui.open(player, "main", round.definition().id());
    }

    private void reclaim(Player player, JackpotRound round) {
        OperationResult result = jackpot.reclaim(player, round.definition().id());
        sendResult(player, result, new PlaceholderBag()
                .put("money", economy.provider().format(result.value())));
        gui.open(player, "main", round.definition().id());
    }

    private void joinItem(Player player, JackpotRound round) {
        if (!player.hasPermission("ucjackpot.join.item")) {
            messages.send(player, "no-permission");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        OperationResult result = jackpot.joinItem(player, round.definition().id(), item);
        sendResult(player, result, new PlaceholderBag()
                .put("item", item == null ? "-" : item.getType().name())
                .put("amount", String.format(Locale.US, "%.0f", result.value()))
                .put("value", economy.provider().format(result.value()))
                .put("chance", String.format(java.util.Locale.US, "%.2f", jackpot.primaryChance(player.getUniqueId()))));
        gui.open(player, "main", round.definition().id());
    }

    private void joinClickedInventoryItem(Player player, int slot) {
        if (!player.hasPermission("ucjackpot.join.item")) {
            messages.send(player, "no-permission");
            return;
        }
        ItemStack item = player.getInventory().getItem(slot);
        OperationResult result = jackpot.joinItemFromInventorySlot(player, jackpot.primaryRound().definition().id(), slot);
        sendResult(player, result, new PlaceholderBag()
                .put("item", item == null ? "-" : item.getType().name())
                .put("amount", String.format(Locale.US, "%.0f", result.value()))
                .put("value", economy.provider().format(result.value()))
                .put("chance", String.format(java.util.Locale.US, "%.2f", jackpot.primaryChance(player.getUniqueId()))));
        gui.open(player, "main");
    }

    private void toggleItemSelection(Player player, String jackpotId, int slot) {
        Map<Integer, String> selected = selectedItems.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashMap<>());
        JackpotRound round = round(jackpotId);
        ItemStack item = player.getInventory().getItem(slot);
        if (selected.containsKey(slot)) {
            selected.remove(slot);
            pendingSpecialItemApproval.remove(player.getUniqueId());
            audit.log(AuditEventType.ITEM_DESELECTED, player.getUniqueId(), player.getName(), round.definition().id(),
                    "GUI item deselected", "slot=" + slot + ",item=" + itemSummary(item));
            messages.send(player, "item-deselected", new PlaceholderBag().put("count", selected.size()));
            return;
        }
        if (selected.size() + 1 > round.definition().maxItemsPerEntry()) {
            messages.send(player, "max-items", new PlaceholderBag().put("amount", round.definition().maxItemsPerEntry()));
            return;
        }
        OperationResult validation = jackpot.validateItemSelection(round.definition().id(), item);
        if (!validation.success()) {
            audit.log(AuditEventType.SUSPICIOUS_ENTRY, player.getUniqueId(), player.getName(), round.definition().id(),
                    "GUI item selection rejected", "slot=" + slot + ",item=" + itemSummary(item)
                            + ",message=" + validation.messageKey());
            sendResult(player, validation, new PlaceholderBag()
                    .put("amount", round.definition().minItemsPerEntry())
                    .put("item", item == null ? "-" : item.getType().name()));
            return;
        }
        selected.put(slot, ItemSerializer.fingerprint(item.clone()));
        pendingSpecialItemApproval.remove(player.getUniqueId());
        if (isSpecial(player.getInventory().getItem(slot))) {
            messages.send(player, "special-item-selected");
        }
        audit.log(AuditEventType.ITEM_SELECTED, player.getUniqueId(), player.getName(), round.definition().id(),
                "GUI item selected", "slot=" + slot + ",item=" + itemSummary(item)
                        + ",fingerprint=" + ItemSerializer.fingerprint(item.clone()));
        messages.send(player, "item-selected", new PlaceholderBag().put("count", selected.size()));
    }

    private boolean hasSpecialSelectedItem(Player player, Map<Integer, String> selected) {
        if (selected == null) {
            return false;
        }
        for (int slot : selected.keySet()) {
            if (isSpecial(player.getInventory().getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private String itemSummary(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "empty";
        }
        return item.getType().name() + "x" + item.getAmount();
    }

    private boolean isSpecial(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (!item.getEnchantments().isEmpty() || item.getType().name().contains("SHULKER_BOX")) {
            return true;
        }
        if (!item.hasItemMeta()) {
            return false;
        }
        var meta = item.getItemMeta();
        return meta.hasDisplayName() || meta.hasLore() || meta.hasCustomModelData();
    }

    private void sendResult(Player player, OperationResult result, PlaceholderBag bag) {
        if (result.seconds() > 0) {
            bag.put("seconds", result.seconds());
        }
        messages.send(player, result.messageKey(), bag);
        if (!result.success()) {
            gui.play(player, "main", "error");
        }
    }
}


