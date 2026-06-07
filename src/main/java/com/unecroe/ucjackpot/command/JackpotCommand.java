package com.unecroe.ucjackpot.command;

import com.unecroe.ucjackpot.audit.AuditEventType;
import com.unecroe.ucjackpot.audit.AuditService;
import com.unecroe.ucjackpot.config.ConfigService;
import com.unecroe.ucjackpot.config.JackpotDefinition;
import com.unecroe.ucjackpot.debug.DebugLogger;
import com.unecroe.ucjackpot.economy.EconomyService;
import com.unecroe.ucjackpot.gui.GuiService;
import com.unecroe.ucjackpot.item.ItemSerializer;
import com.unecroe.ucjackpot.jackpot.DrawResult;
import com.unecroe.ucjackpot.jackpot.JackpotRound;
import com.unecroe.ucjackpot.jackpot.JackpotService;
import com.unecroe.ucjackpot.jackpot.OperationResult;
import com.unecroe.ucjackpot.lang.MessageService;
import com.unecroe.ucjackpot.storage.SeasonStatRecord;
import com.unecroe.ucjackpot.storage.StorageService;
import com.unecroe.ucjackpot.text.PlaceholderBag;
import com.unecroe.ucjackpot.text.TextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class JackpotCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final ConfigService config;
    private final EconomyService economy;
    private final JackpotService jackpot;
    private final GuiService gui;
    private final MessageService messages;
    private final StorageService storage;
    private final AuditService audit;
    private final DebugLogger debug;
    private final Runnable reloadCallback;

    public JackpotCommand(JavaPlugin plugin, ConfigService config, EconomyService economy, JackpotService jackpot, GuiService gui,
                          MessageService messages, StorageService storage, AuditService audit, DebugLogger debug, Runnable reloadCallback) {
        this.plugin = plugin;
        this.config = config;
        this.economy = economy;
        this.jackpot = jackpot;
        this.gui = gui;
        this.messages = messages;
        this.storage = storage;
        this.audit = audit;
        this.debug = debug;
        this.reloadCallback = reloadCallback;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            debug.log("commands", "Command actor=" + sender.getName() + " sub=help args=");
            return help(sender, label);
        }
        if (args[0].equalsIgnoreCase("open")) {
            debug.log("commands", "Command actor=" + sender.getName() + " sub=open args=" + String.join(" ", args));
            return player(sender, player -> open(player, args));
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        debug.log("commands", "Command actor=" + sender.getName() + " sub=" + sub + " args=" + String.join(" ", args));
        return switch (sub) {
            case "rooms" -> player(sender, player -> gui.open(player, "rooms"));
            case "join" -> join(sender, args);
            case "item" -> item(sender, args);
            case "ticket" -> ticket(sender, args, label);
            case "season" -> season(sender, args);
            case "stats" -> player(sender, player -> gui.open(player, "stats"));
            case "history" -> history(sender);
            case "top" -> top(sender);
            case "mailbox" -> player(sender, this::mailbox);
            case "title" -> player(sender, this::toggleTitleNotifications);
            case "reload" -> admin(sender, "ucjackpot.reload", () -> {
                reloadCallback.run();
                audit.log(AuditEventType.CONFIG_RELOAD, null, sender.getName(), "all", "Reload command", "");
                messages.send(sender, "reload");
            });
            case "start" -> admin(sender, "ucjackpot.start", () -> sendAdmin(sender, jackpot.start(roomId(args, 1))));
            case "stop" -> admin(sender, "ucjackpot.stop", () -> sendAdmin(sender, jackpot.stop(roomId(args, 1))));
            case "cancel" -> admin(sender, "ucjackpot.cancel", () -> sendAdmin(sender, jackpot.cancel(roomId(args, 1))));
            case "selftest" -> admin(sender, "ucjackpot.selftest", () -> selftest(sender));
            case "draw" -> admin(sender, "ucjackpot.draw", () -> {
                String targetRoom = roomId(args, 1);
                JackpotRound round = jackpot.round(targetRoom);
                if (round == null) {
                    messages.send(sender, "jackpot-not-found", new PlaceholderBag().put("jackpot", targetRoom));
                    return;
                }
                if (round.drawing()) {
                    messages.send(sender, "draw-in-progress");
                    return;
                }
                DrawResult result = jackpot.draw(targetRoom, true);
                if (result == null) {
                    messages.send(sender, "not-enough-players");
                } else {
                    messages.send(sender, "draw-started");
                }
            });
            case "admin", "help" -> help(sender, label);
            default -> help(sender, label);
        };
    }

    private void open(Player player, String[] args) {
        String jackpotId = roomId(args, 1);
        if (jackpot.round(jackpotId) == null) {
            messages.send(player, "jackpot-not-found", new PlaceholderBag().put("jackpot", jackpotId));
            return;
        }
        gui.open(player, "main", jackpotId);
    }

    private boolean join(CommandSender sender, String[] args) {
        return player(sender, player -> {
            if (!player.hasPermission("ucjackpot.join.money")) {
                messages.send(player, "no-permission");
                return;
            }
            MoneyJoin request = moneyJoin(args);
            if (request.invalidNumber()) {
                messages.send(player, "invalid-number");
                return;
            }
            JackpotRound round = jackpot.round(request.jackpotId());
            if (round == null) {
                messages.send(player, "jackpot-not-found", new PlaceholderBag().put("jackpot", request.jackpotId()));
                return;
            }
            OperationResult result = jackpot.joinMoney(player, round.definition().id(), request.amount());
            sendResult(player, result, new PlaceholderBag()
                    .put("amount", economy.provider().format(request.amount()))
                    .put("chance", String.format(Locale.US, "%.2f", jackpot.chance(player.getUniqueId(), round.definition().id()))));
        });
    }

    private boolean item(CommandSender sender, String[] args) {
        return player(sender, player -> {
            if (!player.hasPermission("ucjackpot.join.item")) {
                messages.send(player, "no-permission");
                return;
            }
            String jackpotId = roomId(args, 1);
            JackpotRound round = jackpot.round(jackpotId);
            if (round == null) {
                messages.send(player, "jackpot-not-found", new PlaceholderBag().put("jackpot", jackpotId));
                return;
            }
            OperationResult result = jackpot.joinItem(player, round.definition().id(), player.getInventory().getItemInMainHand());
            sendResult(player, result, new PlaceholderBag()
                    .put("item", player.getInventory().getItemInMainHand().getType().name())
                    .put("amount", String.format(Locale.US, "%.0f", result.value()))
                    .put("value", economy.provider().format(result.value()))
                    .put("chance", String.format(Locale.US, "%.2f", jackpot.chance(player.getUniqueId(), round.definition().id()))));
        });
    }

    private boolean ticket(CommandSender sender, String[] args, String label) {
        if (args.length > 1 && args[1].equalsIgnoreCase("give")) {
            return admin(sender, "ucjackpot.ticket.give", () -> giveTicket(sender, args, label));
        }
        return player(sender, player -> {
            if (!player.hasPermission("ucjackpot.join.ticket")) {
                messages.send(player, "no-permission");
                return;
            }
            String jackpotId = roomId(args, 1);
            JackpotRound round = jackpot.round(jackpotId);
            if (round == null) {
                messages.send(player, "jackpot-not-found", new PlaceholderBag().put("jackpot", jackpotId));
                return;
            }
            OperationResult result = jackpot.joinTicket(player, round.definition().id());
            sendResult(player, result, new PlaceholderBag()
                    .put("amount", economy.provider().format(result.value()))
                    .put("chance", String.format(Locale.US, "%.2f", jackpot.chance(player.getUniqueId(), round.definition().id()))));
        });
    }

    private void giveTicket(CommandSender sender, String[] args, String label) {
        if (args.length < 4) {
            messages.send(sender, "ticket-give-usage", commandBag(label));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            messages.send(sender, "player-not-found", new PlaceholderBag().put("player", args[2]));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException exception) {
            messages.send(sender, "invalid-number");
            return;
        }
        if (amount <= 0) {
            messages.send(sender, "invalid-number");
            return;
        }
        String jackpotId = roomId(args, 4);
        JackpotRound round = jackpot.round(jackpotId);
        if (round == null) {
            messages.send(sender, "jackpot-not-found", new PlaceholderBag().put("jackpot", jackpotId));
            return;
        }
        int overflow = giveTicketItems(target, round.definition(), amount);
        audit.log(AuditEventType.TICKET_GIVE, target.getUniqueId(), target.getName(), round.definition().id(),
                "Ticket items given", "actor=" + sender.getName() + ",amount=" + amount + ",overflow=" + overflow);
        debug.log("tickets", "Ticket give actor=" + sender.getName() + " target=" + target.getName()
                + " amount=" + amount + " jackpot=" + round.definition().id() + " overflow=" + overflow);
        messages.send(sender, "ticket-give-success", new PlaceholderBag()
                .put("player", target.getName())
                .put("amount", amount)
                .put("jackpot", round.definition().id()));
    }

    private int giveTicketItems(Player target, JackpotDefinition definition, int amount) {
        Material material = Material.matchMaterial(definition.ticketMaterial());
        if (material == null || material.isAir()) {
            material = Material.PAPER;
        }
        int overflowCount = 0;
        int left = amount;
        while (left > 0) {
            int stackAmount = Math.min(left, material.getMaxStackSize());
            ItemStack item = new ItemStack(material, stackAmount);
            ItemMeta meta = item.getItemMeta();
            if (meta != null && !definition.ticketNameContains().isBlank()) {
                meta.setDisplayName(TextFormatter.color(definition.ticketNameContains()));
                item.setItemMeta(meta);
            }
            ItemStack snapshot = item.clone();
            Map<Integer, ItemStack> overflow = target.getInventory().addItem(snapshot.clone());
            int overflowAmount = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
            int deliveredAmount = Math.max(0, stackAmount - overflowAmount);
            if (deliveredAmount > 0) {
                audit.log(AuditEventType.PAYOUT, target.getUniqueId(), target.getName(), definition.id(),
                        "Ticket item delivered", "amount=" + deliveredAmount + ",item=" + itemSummary(snapshot)
                                + ",fingerprint=" + ItemSerializer.fingerprint(snapshot));
            }
            for (ItemStack leftover : overflow.values()) {
                overflowCount += leftover.getAmount();
                storage.addMailboxItem(target.getUniqueId(), target.getName(), ItemSerializer.encode(leftover), "ticket-give-overflow");
                audit.log(AuditEventType.MAILBOX, target.getUniqueId(), target.getName(), definition.id(),
                        "Ticket item queued in mailbox", "amount=" + leftover.getAmount()
                                + ",item=" + itemSummary(leftover) + ",fingerprint=" + ItemSerializer.fingerprint(leftover));
            }
            left -= stackAmount;
        }
        return overflowCount;
    }

    private String itemSummary(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "empty";
        }
        return item.getType().name() + "x" + item.getAmount();
    }

    private void mailbox(Player player) {
        if (!player.hasPermission("ucjackpot.mailbox")) {
            messages.send(player, "no-permission");
            return;
        }
        jackpot.claimMailbox(player, count -> {
            if (count <= 0) {
                messages.send(player, "mailbox-empty");
            } else {
                messages.send(player, "mailbox-delivered", new PlaceholderBag().put("count", count));
            }
        });
    }

    private void toggleTitleNotifications(Player player) {
        if (!config.settings().drawTitlesEnabled() || !config.settings().drawTitlesPlayerToggle()) {
            messages.send(player, "title-toggle-unavailable");
            return;
        }
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        storage.titleNotifications(playerUuid).thenAccept(enabled -> {
            boolean next = !enabled;
            storage.saveTitleNotifications(playerUuid, playerName, next).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                audit.log(AuditEventType.TITLE_TOGGLE, playerUuid, playerName, null,
                        "Draw title notifications toggled", "enabled=" + next);
                debug.log("commands", "Title notifications toggled player=" + playerName + " enabled=" + next);
                Player target = Bukkit.getPlayer(playerUuid);
                if (target != null && target.isOnline()) {
                    messages.send(target, next ? "title-toggle-enabled" : "title-toggle-disabled");
                }
            }));
        });
    }

    private boolean history(CommandSender sender) {
        if (!sender.hasPermission("ucjackpot.history")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (sender instanceof Player player) {
            gui.open(player, "history");
            return true;
        }
        storage.recentHistory(10).thenAccept(records -> {
            messages.send(sender, "history-console-header");
            records.forEach(record -> sender.sendMessage(messages.format("history-console-line", new PlaceholderBag()
                    .put("player", record.winnerName())
                    .put("value", economy.provider().format(record.value()))
                    .put("jackpot", record.jackpotId()))));
        });
        return true;
    }

    private boolean top(CommandSender sender) {
        if (!sender.hasPermission("ucjackpot.top")) {
            messages.send(sender, "no-permission");
            return true;
        }
        storage.recentHistory(50).thenAccept(records -> {
            java.util.Map<String, Double> totals = new java.util.LinkedHashMap<>();
            records.forEach(record -> totals.merge(record.winnerName(), record.value(), Double::sum));
            messages.send(sender, "top-console-header");
            totals.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(10)
                    .forEach(entry -> sender.sendMessage(messages.format("top-console-line", new PlaceholderBag()
                            .put("player", entry.getKey())
                            .put("value", economy.provider().format(entry.getValue())))));
        });
        return true;
    }

    private boolean season(CommandSender sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("reward")) {
            return admin(sender, "ucjackpot.season.reward", () -> rewardSeason(sender, args));
        }
        if (!sender.hasPermission("ucjackpot.season")) {
            messages.send(sender, "no-permission");
            return true;
        }
        String seasonId = args.length > 1 && args[1].equalsIgnoreCase("top")
                ? (args.length > 2 ? args[2] : config.primaryJackpot().seasonId())
                : seasonId(args, 1);
        storage.topSeason(seasonId, 10).thenAccept(records -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (records.isEmpty()) {
                messages.send(sender, "season-empty", new PlaceholderBag().put("season", seasonId));
                return;
            }
            messages.send(sender, "season-top-header", new PlaceholderBag().put("season", seasonId));
            int rank = 1;
            for (SeasonStatRecord record : records) {
                sender.sendMessage(messages.format("season-top-line", seasonBag(record, rank++)));
            }
        }));
        return true;
    }

    private void rewardSeason(CommandSender sender, String[] args) {
        String seasonId = args.length > 2 ? args[2] : config.primaryJackpot().seasonId();
        int limit = 10;
        if (args.length > 3) {
            try {
                limit = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                messages.send(sender, "invalid-number");
                return;
            }
        }
        JackpotDefinition definition = definitionForSeason(seasonId);
        if (definition == null || definition.seasonRewardCommands().isEmpty()) {
            debug.log("season", "Season reward skipped season=" + seasonId + " reason=no-commands");
            messages.send(sender, "season-reward-no-commands", new PlaceholderBag().put("season", seasonId));
            return;
        }
        int finalLimit = Math.max(1, limit);
        debug.log("season", "Season reward requested actor=" + sender.getName() + " season=" + seasonId + " limit=" + finalLimit);
        storage.topSeason(seasonId, finalLimit).thenAccept(records -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (records.isEmpty()) {
                debug.log("season", "Season reward skipped season=" + seasonId + " reason=no-records");
                messages.send(sender, "season-empty", new PlaceholderBag().put("season", seasonId));
                return;
            }
            int rank = 1;
            for (SeasonStatRecord record : records) {
                for (String command : definition.seasonRewardCommands()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), applySeasonCommand(command, record, rank));
                }
                rank++;
            }
            audit.log(AuditEventType.SEASON_REWARD, null, sender.getName(), definition.id(),
                    "Season reward distributed", "season=" + seasonId + ",count=" + records.size() + ",limit=" + finalLimit);
            debug.log("season", "Season reward distributed actor=" + sender.getName() + " season=" + seasonId
                    + " count=" + records.size() + " jackpot=" + definition.id());
            messages.send(sender, "season-reward-complete", new PlaceholderBag()
                    .put("season", seasonId)
                    .put("count", records.size()));
        }));
    }

    private void selftest(CommandSender sender) {
        List<String> issues = new ArrayList<>();
        List<String> guiIssues = gui.validate();
        if (config.jackpots().isEmpty()) {
            issues.add("no jackpot definitions loaded");
        }
        if (jackpot.primaryRound() == null) {
            issues.add("primary round is not available");
        }
        issues.addAll(guiIssues);
        boolean moneyEnabled = config.primaryJackpot().acceptsMoney();
        if (moneyEnabled && !economy.provider().available()) {
            issues.add("money jackpot is enabled but no economy provider is available");
        }
        storage.healthCheck().thenAccept(ok -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (!ok) {
                issues.add("storage health check failed");
            }
            sender.sendMessage(messages.format("selftest-header", new PlaceholderBag().put("status", issues.isEmpty() ? "PASS" : "FAIL")));
            sender.sendMessage(messages.format("selftest-jackpots", new PlaceholderBag().put("count", config.jackpots().size())));
            sender.sendMessage(messages.format("selftest-economy", new PlaceholderBag()
                    .put("provider", economy.provider().name())
                    .put("available", economy.provider().available())));
            sender.sendMessage(messages.format("selftest-gui", new PlaceholderBag().put("count", guiIssues.size())));
            sender.sendMessage(messages.format("selftest-storage", new PlaceholderBag().put("status", ok ? "ok" : "fail")));
            sender.sendMessage(messages.format("selftest-placeholderapi", new PlaceholderBag()
                    .put("available", org.bukkit.Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null)));
            for (String issue : issues) {
                sender.sendMessage(messages.format("selftest-issue", new PlaceholderBag().put("issue", issue)));
            }
        }));
    }

    private boolean help(CommandSender sender, String label) {
        for (String line : messages.list("help", commandBag(label))) {
            sender.sendMessage(line);
        }
        return true;
    }

    private PlaceholderBag commandBag(String label) {
        String command = label == null || label.isBlank() ? "ucjackpot" : label.toLowerCase(Locale.ROOT);
        return new PlaceholderBag().put("command", "/" + command);
    }

    private boolean player(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "only-player");
            return true;
        }
        if (!player.hasPermission("ucjackpot.use")) {
            messages.send(player, "no-permission");
            return true;
        }
        action.accept(player);
        return true;
    }

    private boolean admin(CommandSender sender, String permission, Runnable action) {
        if (!sender.hasPermission(permission) && !sender.hasPermission("ucjackpot.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        debug.log("commands", "Admin command actor=" + sender.getName() + " permission=" + permission);
        action.run();
        audit.log(AuditEventType.ADMIN_ACTION, null, sender.getName(), primaryId(), "Admin command", permission);
        return true;
    }

    private void sendResult(CommandSender sender, OperationResult result, PlaceholderBag bag) {
        if (result.seconds() > 0) {
            bag.put("seconds", result.seconds());
        }
        messages.send(sender, result.messageKey(), bag);
    }

    private void sendAdmin(CommandSender sender, OperationResult result) {
        messages.send(sender, result.messageKey(), new PlaceholderBag().put("action", result.messageKey()));
    }

    private String primaryId() {
        return config.primaryJackpot().id();
    }

    private String roomId(String[] args, int index) {
        if (args.length > index && config.jackpot(args[index]) != null) {
            return config.jackpot(args[index]).id();
        }
        return primaryId();
    }

    private String seasonId(String[] args, int index) {
        if (args.length > index && !args[index].isBlank()) {
            return args[index];
        }
        return config.primaryJackpot().seasonId();
    }

    private MoneyJoin moneyJoin(String[] args) {
        String jackpotId = primaryId();
        double amount = config.primaryJackpot().defaultMoneyEntry();
        if (args.length == 1) {
            return new MoneyJoin(jackpotId, amount, false);
        }
        if (config.jackpot(args[1]) != null) {
            jackpotId = config.jackpot(args[1]).id();
            amount = config.jackpot(args[1]).defaultMoneyEntry();
            if (args.length > 2) {
                Double parsed = parseDouble(args[2]);
                return parsed == null ? new MoneyJoin(jackpotId, amount, true) : new MoneyJoin(jackpotId, parsed, false);
            }
            return new MoneyJoin(jackpotId, amount, false);
        }
        Double parsed = parseDouble(args[1]);
        if (parsed == null) {
            return new MoneyJoin(args[1], amount, false);
        }
        amount = parsed;
        if (args.length > 2) {
            if (config.jackpot(args[2]) == null) {
                return new MoneyJoin(args[2], amount, false);
            }
            jackpotId = config.jackpot(args[2]).id();
        }
        return new MoneyJoin(jackpotId, amount, false);
    }

    private Double parseDouble(String value) {
        try {
            return Double.parseDouble(value.replace(",", "."));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private JackpotDefinition definitionForSeason(String seasonId) {
        return config.jackpots().values().stream()
                .filter(definition -> definition.seasonId().equalsIgnoreCase(seasonId))
                .findFirst()
                .orElse(null);
    }

    private PlaceholderBag seasonBag(SeasonStatRecord record, int rank) {
        return new PlaceholderBag()
                .put("rank", rank)
                .put("season", record.seasonId())
                .put("player", record.playerName())
                .put("entries", record.entries())
                .put("wins", record.wins())
                .put("value_in", economy.provider().format(record.valueIn()))
                .put("value_won", economy.provider().format(record.valueWon()));
    }

    private String applySeasonCommand(String command, SeasonStatRecord record, int rank) {
        return command.replace("%rank%", String.valueOf(rank))
                .replace("%season%", record.seasonId())
                .replace("%player%", record.playerName())
                .replace("%entries%", String.valueOf(record.entries()))
                .replace("%wins%", String.valueOf(record.wins()))
                .replace("%value_in%", String.format(Locale.US, "%.2f", record.valueIn()))
                .replace("%value_won%", String.format(Locale.US, "%.2f", record.valueWon()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = rootOptions(sender);
            return options.stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("open")
                || args[0].equalsIgnoreCase("item") || args[0].equalsIgnoreCase("rooms") || args[0].equalsIgnoreCase("start")
                || args[0].equalsIgnoreCase("stop") || args[0].equalsIgnoreCase("draw")
                || args[0].equalsIgnoreCase("cancel"))) {
            List<String> options = new ArrayList<>(config.jackpots().keySet());
            if (args[0].equalsIgnoreCase("join")) {
                options.add(String.valueOf(config.primaryJackpot().defaultMoneyEntry()));
            }
            return options.stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ticket")) {
            List<String> options = new ArrayList<>(config.jackpots().keySet());
            if (sender.hasPermission("ucjackpot.ticket.give") || sender.hasPermission("ucjackpot.admin")) {
                options.add("give");
            }
            return options.stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("ticket") && args[1].equalsIgnoreCase("give")
                && (sender.hasPermission("ucjackpot.ticket.give") || sender.hasPermission("ucjackpot.admin"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("ticket") && args[1].equalsIgnoreCase("give")
                && (sender.hasPermission("ucjackpot.ticket.give") || sender.hasPermission("ucjackpot.admin"))) {
            return List.of("1", "8", "16", "32", "64").stream()
                    .filter(option -> option.startsWith(args[3]))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("season")) {
            List<String> options = new ArrayList<>(List.of("top"));
            options.addAll(seasonIds());
            if (sender.hasPermission("ucjackpot.season.reward") || sender.hasPermission("ucjackpot.admin")) {
                options.add("reward");
            }
            return options.stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("season")) {
            return seasonIds().stream()
                    .filter(option -> option.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("ticket") && args[1].equalsIgnoreCase("give")) {
            return config.jackpots().keySet().stream()
                    .filter(option -> option.startsWith(args[4].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private List<String> rootOptions(CommandSender sender) {
        List<String> options = new ArrayList<>(List.of("help"));
        if (sender.hasPermission("ucjackpot.use")) {
            options.addAll(List.of("open", "rooms", "title"));
        }
        if (sender.hasPermission("ucjackpot.join.money")) {
            options.add("join");
        }
        if (sender.hasPermission("ucjackpot.join.item")) {
            options.add("item");
        }
        if (sender.hasPermission("ucjackpot.join.ticket") || sender.hasPermission("ucjackpot.ticket.give")
                || sender.hasPermission("ucjackpot.admin")) {
            options.add("ticket");
        }
        addPermitted(options, sender, "ucjackpot.season", "season");
        addPermitted(options, sender, "ucjackpot.stats", "stats");
        addPermitted(options, sender, "ucjackpot.top", "top");
        addPermitted(options, sender, "ucjackpot.history", "history");
        addPermitted(options, sender, "ucjackpot.mailbox", "mailbox");
        addAdminOption(options, sender, "ucjackpot.reload", "reload");
        addAdminOption(options, sender, "ucjackpot.start", "start");
        addAdminOption(options, sender, "ucjackpot.stop", "stop");
        addAdminOption(options, sender, "ucjackpot.draw", "draw");
        addAdminOption(options, sender, "ucjackpot.cancel", "cancel");
        addAdminOption(options, sender, "ucjackpot.selftest", "selftest");
        if (sender.hasPermission("ucjackpot.admin")) {
            options.add("admin");
        }
        return options;
    }

    private void addPermitted(List<String> options, CommandSender sender, String permission, String option) {
        if (sender.hasPermission(permission) || sender.hasPermission("ucjackpot.admin")) {
            options.add(option);
        }
    }

    private void addAdminOption(List<String> options, CommandSender sender, String permission, String option) {
        if (sender.hasPermission(permission) || sender.hasPermission("ucjackpot.admin")) {
            options.add(option);
        }
    }

    private List<String> seasonIds() {
        return config.jackpots().values().stream()
                .map(JackpotDefinition::seasonId)
                .distinct()
                .toList();
    }

    private record MoneyJoin(String jackpotId, double amount, boolean invalidNumber) {
    }
}


