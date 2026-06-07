package com.unecroe.ucjackpot.jackpot;

import com.unecroe.ucjackpot.config.JackpotDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class JackpotRound {
    private final JackpotDefinition definition;
    private final List<JackpotEntry> entries = new ArrayList<>();
    private final Map<UUID, Long> lastEntryTimes = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerStats> playerStats = new HashMap<>();
    private final Set<Double> announcedMilestones = new HashSet<>();
    private double totalValue;
    private double moneyPot;
    private double itemValue;
    private int itemCount;
    private int ticketCount;
    private long endsAtMillis;
    private boolean running;
    private boolean drawing;
    private boolean countdownStarted;

    public JackpotRound(JackpotDefinition definition) {
        this.definition = definition;
        restart();
    }

    public synchronized void restart() {
        entries.clear();
        lastEntryTimes.clear();
        playerStats.clear();
        announcedMilestones.clear();
        totalValue = 0.0;
        moneyPot = 0.0;
        itemValue = 0.0;
        itemCount = 0;
        ticketCount = 0;
        endsAtMillis = 0L;
        running = true;
        drawing = false;
        countdownStarted = false;
    }

    public synchronized void restore(List<JackpotEntry> restoredEntries) {
        entries.clear();
        lastEntryTimes.clear();
        playerStats.clear();
        announcedMilestones.clear();
        totalValue = 0.0;
        moneyPot = 0.0;
        itemValue = 0.0;
        itemCount = 0;
        ticketCount = 0;
        for (JackpotEntry entry : restoredEntries) {
            entries.add(entry);
            addStats(entry);
        }
        running = true;
        drawing = false;
        if (canStartCountdown()) {
            startCountdown();
        } else {
            endsAtMillis = 0L;
            countdownStarted = false;
        }
    }

    public synchronized void addEntry(JackpotEntry entry) {
        entries.add(entry);
        addStats(entry);
        lastEntryTimes.put(entry.playerUuid(), System.currentTimeMillis());
        if (!countdownStarted && canStartCountdown()) {
            startCountdown();
        }
    }

    public synchronized List<JackpotEntry> entries() {
        return List.copyOf(entries);
    }

    public synchronized List<JackpotEntry> removeEntries(UUID playerUuid) {
        List<JackpotEntry> removed = entries.stream()
                .filter(entry -> entry.playerUuid().equals(playerUuid))
                .toList();
        if (removed.isEmpty()) {
            return List.of();
        }
        entries.removeIf(entry -> entry.playerUuid().equals(playerUuid));
        lastEntryTimes.remove(playerUuid);
        rebuildStats();
        if (!canStartCountdown()) {
            endsAtMillis = 0L;
            countdownStarted = false;
            drawing = false;
        }
        return removed;
    }

    public JackpotDefinition definition() {
        return definition;
    }

    public synchronized long secondsLeft() {
        if (!countdownStarted) {
            return definition.durationSeconds();
        }
        return Math.max(0L, (endsAtMillis - System.currentTimeMillis()) / 1000L);
    }

    public synchronized int playerEntryCount(UUID playerUuid) {
        return stats(playerUuid).entries;
    }

    public synchronized double playerValue(UUID playerUuid) {
        return stats(playerUuid).value;
    }

    public synchronized double totalValue() {
        return totalValue;
    }

    public synchronized double moneyPot() {
        return moneyPot;
    }

    public synchronized double itemValue() {
        return itemValue;
    }

    public synchronized int ticketCount() {
        return ticketCount;
    }

    public synchronized int itemCount() {
        return itemCount;
    }

    public synchronized double playerMoney(UUID playerUuid) {
        return stats(playerUuid).money;
    }

    public synchronized int playerItemCount(UUID playerUuid) {
        return stats(playerUuid).items;
    }

    public synchronized int playerTicketCount(UUID playerUuid) {
        return stats(playerUuid).tickets;
    }

    public synchronized boolean markMilestone(double value) {
        return announcedMilestones.add(value);
    }

    public synchronized int participantCount() {
        return playerStats.size();
    }

    public synchronized boolean isReadyToDraw() {
        return running && countdownStarted && !drawing && secondsLeft() <= 0 && playerStats.size() >= definition.minPlayers()
                && totalValue >= definition.minTotalValue();
    }

    public synchronized boolean markDrawing() {
        if (drawing) {
            return false;
        }
        drawing = true;
        return true;
    }

    public synchronized void stop() {
        running = false;
    }

    public synchronized void postpone() {
        endsAtMillis = System.currentTimeMillis() + definition.startDelaySeconds() * 1000L;
        countdownStarted = true;
        drawing = false;
    }

    public synchronized boolean running() {
        return running;
    }

    public synchronized boolean countdownStarted() {
        return countdownStarted;
    }

    public synchronized long cooldownLeft(UUID playerUuid) {
        long last = lastEntryTimes.getOrDefault(playerUuid, 0L);
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        return Math.max(0, definition.cooldownSeconds() - elapsed);
    }

    private void addStats(JackpotEntry entry) {
        totalValue += entry.value();
        PlayerStats stats = playerStats.computeIfAbsent(entry.playerUuid(), ignored -> new PlayerStats());
        stats.entries++;
        stats.value += entry.value();
        if (entry.type() == EntryType.MONEY) {
            moneyPot += entry.moneyAmount();
            stats.money += entry.moneyAmount();
        } else if (entry.type() == EntryType.ITEM) {
            itemValue += entry.value();
            if (entry.item() != null) {
                int amount = entry.item().getAmount();
                itemCount += amount;
                stats.items += amount;
            }
        } else if (entry.type() == EntryType.TICKET) {
            ticketCount++;
            stats.tickets++;
        }
    }

    private void rebuildStats() {
        playerStats.clear();
        totalValue = 0.0;
        moneyPot = 0.0;
        itemValue = 0.0;
        itemCount = 0;
        ticketCount = 0;
        for (JackpotEntry entry : entries) {
            addStats(entry);
        }
    }

    private boolean canStartCountdown() {
        return running && playerStats.size() >= definition.minPlayers() && totalValue >= definition.minTotalValue();
    }

    private void startCountdown() {
        endsAtMillis = System.currentTimeMillis() + definition.durationSeconds() * 1000L;
        countdownStarted = true;
    }

    private PlayerStats stats(UUID playerUuid) {
        PlayerStats stats = playerStats.get(playerUuid);
        return stats == null ? PlayerStats.EMPTY : stats;
    }

    private static final class PlayerStats {
        private static final PlayerStats EMPTY = new PlayerStats();
        private int entries;
        private double value;
        private double money;
        private int items;
        private int tickets;
    }
}


