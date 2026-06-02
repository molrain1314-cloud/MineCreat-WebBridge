package com.mcplugin;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.util.*;

public class LanguageManager {
    private final WebBridgePlugin plugin;
    private Map<String, String> translations = new HashMap<>();
    private String currentLang;

    public LanguageManager(WebBridgePlugin plugin) {
        this.plugin = plugin;
        this.currentLang = plugin.getConfig().getString("web.language", "zh_CN");
        loadLanguage(currentLang);
    }

    public void loadLanguage(String lang) {
        translations.clear();
        currentLang = lang;
        File langFile = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");

        // 如果不存在，从jar中提取
        if (!langFile.exists()) {
            langFile.getParentFile().mkdirs();
            try {
                InputStream is = plugin.getResource("lang/" + lang + ".yml");
                if (is != null) {
                    java.nio.file.Files.copy(is, langFile.toPath());
                    is.close();
                }
            } catch (IOException e) {
                plugin.getLogger().warning("无法加载语言文件: " + lang);
            }
        }

        if (langFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(langFile);
            flattenMap(yaml.getValues(true), "");
        }
    }

    private void flattenMap(Map<String, Object> map, String prefix) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map) {
                flattenMap((Map<String, Object>) entry.getValue(), key);
            } else {
                translations.put(key, String.valueOf(entry.getValue()));
            }
        }
    }

    public String get(String key) {
        return translations.getOrDefault(key, key);
    }

    public String getCurrentLang() {
        return currentLang;
    }

    public Map<String, String> getAllTranslations() {
        return new HashMap<>(translations);
    }
}