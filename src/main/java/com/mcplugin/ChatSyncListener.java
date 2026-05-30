package com.mcplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChatSyncListener implements Listener {
    private final WebBridgePlugin plugin;
    private final DatabaseManager db;
    private final CopyOnWriteArrayList<ChatMessage> msgs = new CopyOnWriteArrayList<>();

    public ChatSyncListener(WebBridgePlugin p, DatabaseManager d) { plugin = p; db = d; }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        try { PreparedStatement ps = db.prepare("INSERT INTO chat_messages (player_uuid,player_name,message,source) VALUES (?,?,?,'game')");
            ps.setString(1, p.getUniqueId().toString()); ps.setString(2, p.getName()); ps.setString(3, e.getMessage()); ps.executeUpdate();
        } catch (Exception ex) {}
        msgs.add(0, new ChatMessage(p.getUniqueId().toString(), p.getName(), e.getMessage(), "game", System.currentTimeMillis()));
        if (msgs.size() > 100) msgs.remove(msgs.size() - 1);
    }

    public void sendWebMessageToGame(String name, String msg) {
        Bukkit.broadcastMessage("§b[网页] §7" + name + ": §f" + msg);
        msgs.add(0, new ChatMessage(null, name, msg, "web", System.currentTimeMillis()));
        if (msgs.size() > 100) msgs.remove(msgs.size() - 1);
    }

    public List<ChatMessage> getRecentMessages(int since) { return new ArrayList<>(msgs); }
    public List<String> getOnlinePlayers() { List<String> l = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName()); return l; }
    public int getOnlineCount() { return Bukkit.getOnlinePlayers().size(); }

    public static class ChatMessage {
        public String uuid, playerName, message, source; public long timestamp;
        public ChatMessage(String u, String n, String m, String s, long t) { uuid=u; playerName=n; message=m; source=s; timestamp=t; }
    }
}