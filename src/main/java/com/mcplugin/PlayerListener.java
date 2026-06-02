package com.mcplugin;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {
    private final WebBridgePlugin plugin;
    private final DatabaseManager db;
    public PlayerListener(WebBridgePlugin p, DatabaseManager d) { plugin = p; db = d; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        try { db.update("UPDATE mc_accounts SET last_login=CURRENT_TIMESTAMP WHERE uuid='" + p.getUniqueId().toString().replace("'","''") + "'"); } catch (Exception ex) {}
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, new Runnable() {
            public void run() {
                if (!db.isPlayerBound(p.getUniqueId().toString()))
                    p.sendMessage("§e访问 http://localhost:" + plugin.getConfig().getInt("web.port") + " 注册");
            }
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {}
}