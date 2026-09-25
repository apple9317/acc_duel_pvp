package com.apple9317.accduel.config;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.util.Txt;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 配置管理：config.yml（带默认值自动生成）+ 可切换语言文件 Language/&lt;lang&gt;.yml。
 */
public class ConfigManager {

    private final ACCDuelPlugin plugin;
    private FileConfiguration config;
    /** 当前语言文件（plugins/ACCDuel/Language/&lt;lang&gt;.yml）。 */
    private FileConfiguration lang;

    public ConfigManager(ACCDuelPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();
        loadLanguage();
    }

    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        loadLanguage();
    }

    /** 首次运行释放默认语言文件（已存在不覆盖），并按 config.language 加载；缺失回退 zh_cn。 */
    private void loadLanguage() {
        File dir = new File(plugin.getDataFolder(), "Language");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建 Language 文件夹，语言将回退到 config.yml");
        }
        plugin.saveResource("Language/zh_cn.yml", false);
        plugin.saveResource("Language/en.yml", false);

        String langName = config.getString("language", "zh_cn");
        File file = new File(dir, langName + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("语言文件 " + langName + ".yml 不存在，回退 zh_cn.yml");
            file = new File(dir, "zh_cn.yml");
        }
        lang = file.exists() ? YamlConfiguration.loadConfiguration(file) : null;
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
        String p = lang == null ? null : lang.getString("prefix");
        if (p == null) p = getString("prefix", "<gold>[竞技决斗场]</gold> ");
        return p;
    }

    /** 这些占位符的值是插件自身生成的 MiniMessage 富文本（类型显示名/列表拼接），不转义；其余占位符（玩家名等）仍 escape 防注入。 */
    private static final java.util.Set<String> RICH_PLACEHOLDERS = java.util.Set.of("type", "list");

    /** 从语言文件取消息原文（含前缀）；语言文件缺键时回退 config.yml 的 messages 块。 */
    public String raw(String key, Map<String, String> placeholders) {
        String msg = lang == null ? null : lang.getString(key);
        if (msg == null) msg = config.getString("messages." + key, key);
        if (msg == null) return key;
        String prefix = key.startsWith("help") || key.startsWith("top") || key.startsWith("stats")
                || key.startsWith("menu") || key.startsWith("arena-help")
                ? "" : prefix();
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                String value = e.getValue() == null ? "" : e.getValue();
                String v = RICH_PLACEHOLDERS.contains(e.getKey()) ? value : Txt.escape(value);
                msg = msg.replace("{" + e.getKey() + "}", v);
            }
        }
        return prefix + msg;
    }

    public String raw(String key) {
        return raw(key, null);
    }

    /** 发送多行消息（语言文件中为字符串列表；逐行 MiniMessage 渲染，无前缀）。 */
    public void sendLines(org.bukkit.command.CommandSender sender, String key, Map<String, String> placeholders) {
        List<String> lines = lang == null ? null : lang.getStringList(key);
        if (lines == null || lines.isEmpty()) lines = config.getStringList("messages." + key);
        if (lines.isEmpty()) {
            sendRaw(sender, key, placeholders);
            return;
        }
        for (String line : lines) {
            if (placeholders != null) {
                for (Map.Entry<String, String> e : placeholders.entrySet()) {
                    String v = RICH_PLACEHOLDERS.contains(e.getKey()) ? (e.getValue() == null ? "" : e.getValue()) : Txt.escape(e.getValue());
                    line = line.replace("{" + e.getKey() + "}", v);
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

    /** 随机取一句击杀嘲讽并渲染（无前缀，全服趣味播报）；语言文件无列表时返回 null。 */
    public Component randomTaunt(String winner, String loser) {
        List<String> taunts = lang == null ? null : lang.getStringList("kill-taunts");
        if (taunts == null || taunts.isEmpty()) return null;
        String t = taunts.get(java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(taunts.size()));
        t = t.replace("{winner}", Txt.escape(winner == null ? "" : winner))
                .replace("{loser}", Txt.escape(loser == null ? "" : loser));
        return Txt.mm(t);
    }
}
