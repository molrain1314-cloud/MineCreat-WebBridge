package com.mcplugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class UnbindCommand implements CommandExecutor {
    private final DatabaseManager db;
    public UnbindCommand(DatabaseManager db) { this.db = db; }
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player)) { s.sendMessage("§c仅玩家可用"); return true; }
        Player p = (Player) s;
        if (!db.isPlayerBound(p.getUniqueId().toString())) { p.sendMessage("§e未绑定"); return true; }
        if (a.length == 0 || !a[0].equalsIgnoreCase("confirm")) { p.sendMessage("§e/" + l + " confirm"); return true; }
        final String uuid = p.getUniqueId().toString();
        new BukkitRunnable() { public void run() {
            final boolean ok = db.unbindPlayer(uuid);
            new BukkitRunnable() { public void run() { p.sendMessage(ok ? "§a解绑成功" : "§c解绑失败"); }
            }.runTask(WebBridgePlugin.getInstance());
        }}.runTaskAsynchronously(WebBridgePlugin.getInstance());
        return true;
    }
}