package com.mcplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import java.sql.PreparedStatement;
import java.util.*;

public class ChatSyncListener implements Listener {
    private final WebBridgePlugin plugin;
    private final DatabaseManager db;
    private final List<ChatMessage> msgs = new ArrayList<ChatMessage>();
    private static final int MAX = 100;

    public ChatSyncListener(WebBridgePlugin p, DatabaseManager d) { plugin = p; db = d; }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        try { PreparedStatement ps = db.prepare("INSERT INTO chat_messages (player_uuid,player_name,message,source) VALUES (?,?,?,'game')");
            ps.setString(1, p.getUniqueId().toString()); ps.setString(2, p.getName()); ps.setString(3, e.getMessage()); ps.executeUpdate();
        } catch (Exception ex) {}
        synchronized(msgs) { msgs.add(0, new ChatMessage(p.getUniqueId().toString(), p.getName(), e.getMessage(), "game", System.currentTimeMillis())); if (msgs.size() > MAX) msgs.remove(msgs.size() - 1); }
    }

    public void sendWebMessageToGame(String name, String msg) {
        Bukkit.broadcastMessage("§b[网页] §7" + name + ": §f" + msg);
        synchronized(msgs) { msgs.add(0, new ChatMessage(null, name, msg, "web", System.currentTimeMillis())); if (msgs.size() > MAX) msgs.remove(msgs.size() - 1); }
    }

    public void sendPMAndNotify(String senderName, String senderUuid, String targetName, String targetUuid, String msg) {
        Player target = Bukkit.getPlayer(targetName);
        if (target != null) { target.sendMessage("§d[私信] §e" + senderName + "§7: §f" + msg); }
        try {
            PreparedStatement ps = db.prepare("INSERT INTO notifications (recipient_uuid, sender_name, sender_uuid, message, source) VALUES (?,?,?,?,'web')");
            ps.setString(1, targetUuid); ps.setString(2, senderName); ps.setString(3, senderUuid); ps.setString(4, msg);
            ps.executeUpdate();
        } catch (Exception e) { plugin.getLogger().warning("保存通知失败: " + e.getMessage()); }
    }

    public List<ChatMessage> getRecentMessages(int since) { synchronized(msgs) { return new ArrayList<ChatMessage>(msgs); } }
    public List<String> getOnlinePlayers() { List<String> l = new ArrayList<String>(); for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName()); return l; }
    public int getOnlineCount() { return Bukkit.getOnlinePlayers().size(); }

    public List<Map<String, String>> getOnlineAdmins(Set<String> webAdmins) {
        List<Map<String, String>> admins = new ArrayList<Map<String, String>>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("webbridge.admin")) {
                Map<String, String> m = new HashMap<String, String>();
                m.put("name", p.getName()); m.put("source", "game"); admins.add(m);
            }
        }
        if (webAdmins != null) {
            for (String n : webAdmins) {
                boolean found = false;
                for (Map<String, String> a : admins) {
                    if (a.get("name").equals(n)) { a.put("source", "both"); found = true; break; }
                }
                if (!found) { Map<String, String> m = new HashMap<String, String>(); m.put("name", n); m.put("source", "web"); admins.add(m); }
            }
        }
        return admins;
    }

    public static class ChatMessage {
        public String uuid, playerName, message, source; public long timestamp;
        public ChatMessage(String u, String n, String m, String s, long t) { uuid=u; playerName=n; message=m; source=s; timestamp=t; }
    }
}