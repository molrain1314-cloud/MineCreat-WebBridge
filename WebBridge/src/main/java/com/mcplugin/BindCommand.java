package com.mcplugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class BindCommand implements CommandExecutor {
    private final DatabaseManager db;
    public BindCommand(DatabaseManager db) { this.db = db; }
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player)) { s.sendMessage("§c仅玩家可用"); return true; }
        Player p = (Player) s;
        if (a.length != 1) { p.sendMessage("§e/" + l + " <绑定码>"); return true; }
        if (db.isPlayerBound(p.getUniqueId().toString())) { p.sendMessage("§e已绑定"); return true; }
        final String code = a[0], uuid = p.getUniqueId().toString(), name = p.getName();
        new BukkitRunnable() { public void run() {
            final boolean ok = db.bindPlayer(uuid, name, code);
            new BukkitRunnable() { public void run() { p.sendMessage(ok ? "§a绑定成功" : "§c绑定失败"); }
            }.runTask(WebBridgePlugin.getInstance());
        }}.runTaskAsynchronously(WebBridgePlugin.getInstance());
        return true;
    }
}