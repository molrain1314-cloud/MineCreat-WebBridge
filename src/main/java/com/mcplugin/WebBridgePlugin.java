package com.mcplugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

public class WebBridgePlugin extends JavaPlugin {
    private static WebBridgePlugin instance;
    private DatabaseManager db;
    private WebAPIServer api;
    private ChatSyncListener chat;
    private MailSender mail;
    private EconomyConfig economyConfig;
    private OperatingSystemMXBean osBean;
    private static final String CONFIG_VERSION = "26.5.30";

    @Override
    public void onEnable() {
        instance = this;
        checkAndUpdateConfig();
        saveDefaultConfig();
        osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        economyConfig = new EconomyConfig(this);
        db = new DatabaseManager(this);
        if (!db.connect()) { getServer().getPluginManager().disablePlugin(this); return; }
        mail = new MailSender(this);
        getCommand("molrain").setExecutor(new BindCommand(db));
        getCommand("unmolrain").setExecutor(new UnbindCommand(db));
        getCommand("webbridge").setExecutor(new AdminCommand(this));
        getServer().getPluginManager().registerEvents(new PlayerListener(this, db), this);
        chat = new ChatSyncListener(this, db);
        getServer().getPluginManager().registerEvents(chat, this);
        api = new WebAPIServer(this, db, chat, mail, economyConfig);
        api.start(getConfig().getInt("web.port", 4567));
        getLogger().info("WebBridge v" + getDescription().getVersion() + " 已启用！");
    }

    private void checkAndUpdateConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) return;
        YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
        String fileVersion = currentConfig.getString("config-version", "0");
        if (!CONFIG_VERSION.equals(fileVersion)) {
            File backupFile = new File(getDataFolder(), "config.yml.old");
            configFile.renameTo(backupFile);
            getLogger().warning("检测到旧版本配置文件(" + fileVersion + ")，已备份为 config.yml.old，将生成新配置文件");
            configFile.delete();
        }
    }

    @Override
    public void onDisable() { if (api != null) api.stop(); if (db != null) db.disconnect(); }
    public static WebBridgePlugin getInstance() { return instance; }
    public DatabaseManager getDB() { return db; }
    public EconomyConfig getEconomyConfig() { return economyConfig; }
    public double getEconomyBalance(Player p) { return 0.0; }
    public String getPlayerGroup(Player p) { return "default"; }
    public double getCPUUsage() { return osBean.getProcessCpuLoad() * 100; }
    public long getTotalMemory() { return Runtime.getRuntime().totalMemory() / 1024 / 1024; }
    public long getFreeMemory() { return Runtime.getRuntime().freeMemory() / 1024 / 1024; }
    public long getUsedMemory() { return getTotalMemory() - getFreeMemory(); }
    public String getMOTD() { return getConfig().getString("server.motd", "&a欢迎！"); }
}