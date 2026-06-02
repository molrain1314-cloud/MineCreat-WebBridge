package com.mcplugin;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.util.*;

public class EconomyConfig {

    private final WebBridgePlugin plugin;
    private File configFile;
    private YamlConfiguration config;
    private List<Map<String, String>> fields;

    public EconomyConfig(WebBridgePlugin plugin) {
        this.plugin = plugin;
        this.fields = new ArrayList<Map<String, String>>();
        load();
    }

    public void load() {
        configFile = new File(plugin.getDataFolder(), "economy.yml");
        if (!configFile.exists()) { createDefault(); }
        config = YamlConfiguration.loadConfiguration(configFile);
        parseFields();
    }

    private void createDefault() {
        try {
            configFile.getParentFile().mkdirs();
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(configFile), "UTF-8"));
            writer.println("# 经济变量配置文件");
            writer.println("# 修改此文件后使用 /webbridge reload 重载");
            writer.println("");
            writer.println("fields:");
            writer.println("  - name: \"金币\"");
            writer.println("    key: \"balance\"");
            writer.println("    icon: \"💰\"");
            writer.println("    type: \"double\"");
            writer.println("    editable: false");
            writer.println("");
            writer.println("  - name: \"点券\"");
            writer.println("    key: \"points\"");
            writer.println("    icon: \"💎\"");
            writer.println("    type: \"integer\"");
            writer.println("    editable: false");
            writer.println("");
            writer.println("  - name: \"权限组\"");
            writer.println("    key: \"group\"");
            writer.println("    icon: \"🛡\"");
            writer.println("    type: \"string\"");
            writer.println("    editable: false");
            writer.close();
        } catch (IOException e) {
            plugin.getLogger().warning("无法创建 economy.yml: " + e.getMessage());
        }
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    @SuppressWarnings("unchecked")
    private void parseFields() {
        fields.clear();
        List<?> rawFields = config.getList("fields");
        if (rawFields == null) return;
        for (Object obj : rawFields) {
            if (obj instanceof Map) {
                Map<?, ?> raw = (Map<?, ?>) obj;
                Map<String, String> field = new LinkedHashMap<String, String>();
                Object nameObj = raw.get("name");
                Object keyObj = raw.get("key");
                Object iconObj = raw.get("icon");
                Object typeObj = raw.get("type");
                Object editableObj = raw.get("editable");
                field.put("name", nameObj != null ? nameObj.toString() : "");
                field.put("key", keyObj != null ? keyObj.toString() : "");
                field.put("icon", iconObj != null ? iconObj.toString() : "📌");
                field.put("type", typeObj != null ? typeObj.toString() : "string");
                field.put("editable", editableObj != null ? editableObj.toString() : "false");
                fields.add(field);
            }
        }
    }

    public List<Map<String, String>> getFields() { return fields; }
    public void reload() { load(); }

    public String renderHTML() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> field : fields) {
            sb.append("<p>").append(field.get("icon")).append(" ").append(field.get("name")).append(": <span id='econ_").append(field.get("key")).append("'>-</span></p>");
        }
        return sb.toString();
    }

    public String renderJS() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> field : fields) {
            sb.append("document.getElementById('econ_").append(field.get("key")).append("').textContent=data.").append(field.get("key")).append("||'-';");
        }
        return sb.toString();
    }
}