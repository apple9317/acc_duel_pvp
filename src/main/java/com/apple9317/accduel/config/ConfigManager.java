package com.apple9317.accduel.config;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.util.Txt;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Map;

/**
 * 配置管理：config.yml（带默认值自动生成）。
 */
public class ConfigManager {

    private final ACCDuelPlugin plugin;
    private FileConfiguration config;

    public ConfigManager(ACCDuelPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    public FileConfiguration getConfig() {
        return config;
    }

    // ---------------- 通用取值 ----------------

    public String getString(String path, String def) {
        return config.getString(path, def);
    }

    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }

    public double getDouble(String path, double def) {
        return config.getDouble(path, def);
    }

    public boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    // ---------------- 消息 ----------------

    public String prefix() {
        return getString("prefix", "<gold>[竞技决斗场]</gold> ");
    }

    /** 取消息原文（含前缀）。 */
    public String raw(String key, Map<String, String> placeholders) {
        String msg = config.getString("messages." + key, key);
        if (msg == null) return key;
        String prefix = key.startsWith("help") || key.startsWith("top") || key.startsWith("stats")
                || key.startsWith("menu") || key.startsWith("arena-help")
                ? "" : prefix();
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                String value = e.getValue() == null ? "" : e.getValue();
                msg = msg.replace("{" + e.getKey() + "}", Txt.escape(value));
            }
        }
        return prefix + msg;
    }

    public String raw(String key) {
        return raw(key, null);
    }

    /** 发送多行消息（messages.<key> 为字符串列表；逐行 MiniMessage 渲染，无前缀）。 */
    public void sendLines(org.bukkit.command.CommandSender sender, String key, Map<String, String> placeholders) {
        List<String> lines = config.getStringList("messages." + key);
        if (lines.isEmpty()) {
            sendRaw(sender, key, placeholders);
            return;
        }
        for (String line : lines) {
            if (placeholders != null) {
                for (Map.Entry<String, String> e : placeholders.entrySet()) {
                    line = line.replace("{" + e.getKey() + "}", Txt.escape(e.getValue()));
                }
            }
            sender.sendMessage(Txt.mm(line));
        }
    }

    public void sendLines(org.bukkit.command.CommandSender sender, String key) {
        sendLines(sender, key, null);
    }

    /** 发送消息到玩家（自动前缀 + MiniMessage）。 */
    public void send(org.bukkit.entity.Player player, String key, Map<String, String> placeholders) {
        player.sendMessage(Txt.mm(raw(key, placeholders)));
    }

    public void send(org.bukkit.entity.Player player, String key) {
        send(player, key, null);
    }

    /** 发送消息到全服。 */
    public void broadcast(String key, Map<String, String> placeholders) {
        plugin.getServer().broadcast(Txt.mm(raw(key, placeholders)));
    }

    public void broadcast(String key) {
        broadcast(key, null);
    }

    /** 发送消息到任意命令发送者（控制台/玩家）。 */
    public void sendRaw(org.bukkit.command.CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(Txt.mm(raw(key, placeholders)));
    }

    public void sendRaw(org.bukkit.command.CommandSender sender, String key) {
        sendRaw(sender, key, null);
    }

    public Component component(String key, Map<String, String> placeholders) {
        return Txt.mm(raw(key, placeholders));
    }
}
