package com.mcplugin;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import java.util.UUID;

public class AdminCommand implements CommandExecutor {
    private final WebBridgePlugin plugin;
    public AdminCommand(WebBridgePlugin p) { plugin = p; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("webbridge.admin")) { s.sendMessage("§c无权限"); return true; }
        if (a.length == 0) { s.sendMessage("§a/webbridge reload|status|ban|unban"); return true; }
        switch (a[0].toLowerCase()) {
            case "reload": plugin.reloadConfig(); s.sendMessage("§a已重载"); break;
            case "status": s.sendMessage("§aWebBridge 2.1.5 运行正常"); break;
            case "ban":
                if (a.length < 2) { s.sendMessage("§e/webbridge ban <玩家> [原因]"); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                String reason = a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : "违规";
                plugin.getDB().banPlayer(t.getUniqueId().toString(), reason, s.getName());
                if (t.isOnline()) ((org.bukkit.entity.Player)t).kickPlayer("§c封禁\n原因: " + reason);
                s.sendMessage("§a已封禁 " + a[1]); break;
            case "unban":
                if (a.length < 2) { s.sendMessage("§e/webbridge unban <玩家>"); return true; }
                OfflinePlayer t2 = Bukkit.getOfflinePlayer(a[1]);
                plugin.getDB().unbanPlayer(t2.getUniqueId().toString());
                s.sendMessage("§a已解封 " + a[1]); break;
        }
        return true;
    }
}