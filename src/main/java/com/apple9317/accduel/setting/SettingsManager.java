package com.apple9317.accduel.setting;

import com.apple9317.accduel.ACCDuelPlugin;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家个人设置管理：data/settings.json（Gson，原子写入）。
 */
public class SettingsManager {

    private final ACCDuelPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<UUID, PlayerSettings> settings = new LinkedHashMap<>();
    private Path file;

    public SettingsManager(ACCDuelPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() throws IOException {
        file = plugin.getDataFolder().toPath().resolve("data").resolve("settings.json");
        settings.clear();
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<UUID, PlayerSettings>>() {
            }.getType();
            Map<UUID, PlayerSettings> loaded = gson.fromJson(reader, type);
            if (loaded != null) settings.putAll(loaded);
        }
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(settings, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("保存个人设置失败: " + e.getMessage());
        }
    }

    /** 取玩家设置（无则创建默认）。 */
    public PlayerSettings get(UUID uuid) {
        return settings.computeIfAbsent(uuid, k -> new PlayerSettings());
    }

    public PlayerSettings get(Player player) {
        return get(player.getUniqueId());
    }

    /** 是否接受决斗申请。 */
    public boolean acceptsRequests(UUID uuid) {
        PlayerSettings s = settings.get(uuid);
        return s == null || s.acceptRequests;
    }
}
