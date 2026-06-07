package com.unecroe.ucjackpot;

import com.unecroe.ucjackpot.audit.AuditService;
import com.unecroe.ucjackpot.command.JackpotCommand;
import com.unecroe.ucjackpot.config.ConfigService;
import com.unecroe.ucjackpot.debug.DebugLogger;
import com.unecroe.ucjackpot.economy.EconomyListener;
import com.unecroe.ucjackpot.economy.EconomyService;
import com.unecroe.ucjackpot.gui.GuiListener;
import com.unecroe.ucjackpot.gui.GuiService;
import com.unecroe.ucjackpot.jackpot.JackpotService;
import com.unecroe.ucjackpot.lang.MessageService;
import com.unecroe.ucjackpot.placeholder.PlaceholderHook;
import com.unecroe.ucjackpot.storage.StorageService;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class UcJackpotPlugin extends JavaPlugin {
    private ConfigService configService;
    private MessageService messageService;
    private DebugLogger debugLogger;
    private EconomyService economyService;
    private StorageService storageService;
    private AuditService auditService;
    private JackpotService jackpotService;
    private GuiService guiService;
    private JackpotCommand jackpotCommand;
    private Metrics metrics;
    private final Map<String, Command> dynamicCommands = new HashMap<>();

    @Override
    public void onEnable() {
        this.configService = new ConfigService(this);
        this.messageService = new MessageService(this);
        this.debugLogger = new DebugLogger(this);
        this.economyService = new EconomyService(this, debugLogger);
        this.storageService = new StorageService(this, debugLogger);
        this.auditService = new AuditService(this, storageService, debugLogger);
        this.jackpotService = new JackpotService(this, configService, economyService, storageService, auditService, debugLogger, messageService);
        this.guiService = new GuiService(this, configService, economyService, storageService, messageService);
        this.guiService.jackpotService(jackpotService);

        reloadAll();
        registerCommands();
        registerListeners();
        registerPlaceholders();
        registerMetrics();
        getLogger().info("ucJackpot enabled.");
    }

    @Override
    public void onDisable() {
        if (jackpotService != null) {
            jackpotService.shutdown();
        }
        if (storageService != null) {
            storageService.shutdown();
        }
        getLogger().info("ucJackpot disabled.");
    }

    public void reloadAll() {
        configService.reload();
        debugLogger.settings(configService.settings());
        storageService.start(configService.settings());
        auditService.settings(configService.settings());
        messageService.reload(configService.settings().defaultLocale(), configService.settings().fallbackLocale());
        economyService.reload(configService.settings());
        guiService.reload(messageService.locale(), messageService.fallbackLocale());
        jackpotService.reload();
        if (jackpotCommand != null) {
            registerConfiguredCommandAliases();
        }
        if (metrics == null) {
            registerMetrics();
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("ucjackpot");
        if (command == null) {
            throw new IllegalStateException("Command ucjackpot is missing from plugin.yml");
        }
        jackpotCommand = new JackpotCommand(this, configService, economyService, jackpotService, guiService,
                messageService, storageService, auditService, debugLogger, this::reloadAll);
        command.setExecutor(jackpotCommand);
        command.setTabCompleter(jackpotCommand);
        registerConfiguredCommandAliases();
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new GuiListener(this, configService, economyService, jackpotService,
                guiService, messageService, storageService, auditService), this);
        Bukkit.getPluginManager().registerEvents(new EconomyListener(this, configService, economyService), this);
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            PlaceholderHook.register(this, jackpotService, economyService);
            getLogger().info("PlaceholderAPI expansion registered.");
        }
    }

    private void registerMetrics() {
        if (!configService.settings().metricsEnabled()) {
            getLogger().info("bStats metrics disabled in config.yml.");
            return;
        }
        int pluginId = configService.settings().bstatsPluginId();
        if (pluginId <= 0) {
            return;
        }
        metrics = new Metrics(this, pluginId);
        metrics.addCustomChart(new SimplePie("storage_type", () -> configService.settings().storage().type().toLowerCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("default_locale", () -> configService.settings().defaultLocale()));
        metrics.addCustomChart(new SingleLineChart("jackpot_rooms", () -> configService.jackpots().size()));
        getLogger().info("bStats metrics enabled with plugin id " + pluginId + ".");
    }

    private void registerConfiguredCommandAliases() {
        CommandMap map = commandMap();
        if (map == null || jackpotCommand == null) {
            getLogger().warning("Could not access Bukkit CommandMap. Configurable command aliases were not registered.");
            return;
        }
        unregisterConfiguredCommandAliases(map);
        Map<String, Command> known = knownCommands(map);
        for (String alias : configService.settings().commandAliases()) {
            String label = alias.toLowerCase(Locale.ROOT);
            if (known.containsKey(label)) {
                getLogger().warning("Command alias '/" + label + "' is already used by another command. Skipping this alias.");
                continue;
            }
            Command command = new ConfiguredJackpotCommand(label);
            map.register("ucjackpot", command);
            dynamicCommands.put(label, command);
        }
    }

    private void unregisterConfiguredCommandAliases(CommandMap map) {
        Map<String, Command> known = knownCommands(map);
        for (Map.Entry<String, Command> entry : dynamicCommands.entrySet()) {
            entry.getValue().unregister(map);
            known.remove(entry.getKey());
            known.remove("ucjackpot:" + entry.getKey());
        }
        dynamicCommands.clear();
    }

    private CommandMap commandMap() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getCommandMap");
            return (CommandMap) method.invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException ignored) {
            try {
                Field field = Bukkit.getServer().getClass().getDeclaredField("commandMap");
                field.setAccessible(true);
                return (CommandMap) field.get(Bukkit.getServer());
            } catch (ReflectiveOperationException exception) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> knownCommands(CommandMap map) {
        Class<?> type = map.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("knownCommands");
                field.setAccessible(true);
                return (Map<String, Command>) field.get(map);
            } catch (NoSuchFieldException exception) {
                type = type.getSuperclass();
            } catch (IllegalAccessException exception) {
                break;
            }
        }
        return new HashMap<>();
    }

    private final class ConfiguredJackpotCommand extends Command {
        private ConfiguredJackpotCommand(String name) {
            super(name);
            setDescription("Configurable ucJackpot command alias.");
            setUsage("/" + name + " [rooms|open|join|item|ticket|season|stats|top|history|mailbox|admin|reload]");
        }

        @Override
        public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
            return jackpotCommand.onCommand(sender, this, commandLabel, args);
        }

        @Override
        public java.util.List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) {
            return jackpotCommand.onTabComplete(sender, this, alias, args);
        }
    }
}


