package com.mcplugin;

import org.bukkit.entity.Player;

public class PlayerDataSync {
    private final WebBridgePlugin plugin;
    private final DatabaseManager db;
    public PlayerDataSync(WebBridgePlugin p, DatabaseManager d) { plugin = p; db = d; }
    public void savePlayerData(Player player) {}
}