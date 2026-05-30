package com.mcplugin;

import org.bukkit.plugin.java.JavaPlugin;

public class WebBridgePlugin extends JavaPlugin {
    private static WebBridgePlugin instance;
    private DatabaseManager db;
    private WebAPIServer api;
    private ChatSyncListener chat;
    private MailSender mail;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        db = new DatabaseManager(this);
        if (!db.connect()) { getServer().getPluginManager().disablePlugin(this); return; }
        mail = new MailSender(this);
        getCommand("molrain").setExecutor(new BindCommand(db));
        getCommand("unmolrain").setExecutor(new UnbindCommand(db));
        getCommand("webbridge").setExecutor(new AdminCommand(this));
        getServer().getPluginManager().registerEvents(new PlayerListener(this, db), this);
        chat = new ChatSyncListener(this, db);
        getServer().getPluginManager().registerEvents(chat, this);
        api = new WebAPIServer(this, db, chat, mail);
        api.start(getConfig().getInt("web.port", 4567));
        getLogger().info("WebBridge 2.1.5 已启用！");
    }

    @Override
    public void onDisable() { if (api != null) api.stop(); if (db != null) db.disconnect(); }
    public static WebBridgePlugin getInstance() { return instance; }
    public DatabaseManager getDB() { return db; }
}