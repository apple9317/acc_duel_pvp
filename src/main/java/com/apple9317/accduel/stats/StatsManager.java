package com.apple9317.accduel.stats;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家统计数据管理：data/stats.json（Gson），含段位积分（ELO）。
 */
public class StatsManager {

    private final ACCDuelPlugin plugin;
    private final ConfigManager config;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Map<UUID, PlayerStats> stats = new LinkedHashMap<>();
    private Path file;

    public StatsManager(ACCDuelPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void load() throws IOException {
        file = plugin.getDataFolder().toPath().resolve("data").resolve("stats.json");
        stats.clear();
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<UUID, PlayerStats>>() {
            }.getType();
            Map<UUID, PlayerStats> loaded = gson.fromJson(reader, type);
            if (loaded != null) stats.putAll(loaded);
            plugin.getLogger().info("已加载玩家统计数据（Gson）: " + stats.size() + " 条");
        }
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(stats, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("保存统计数据失败: " + e.getMessage());
        }
    }

    public PlayerStats get(UUID uuid) {
        PlayerStats ps = stats.get(uuid);
        if (ps == null) {
            ps = new PlayerStats();
            ps.firstSeen = System.currentTimeMillis();
            ps.lastSeen = ps.firstSeen;
            stats.put(uuid, ps);
        }
        return ps;
    }

    public PlayerStats get(Player player) {
        PlayerStats ps = get(player.getUniqueId());
        ps.lastSeen = System.currentTimeMillis();
        return ps;
    }

    public void touch(Player player) {
        get(player);
    }

    // ---------------- 段位积分（ELO） ----------------

    public boolean ratingEnabled() {
        return config.getBoolean("rating.enabled", true);
    }

    public int startingRating() {
        return config.getInt("rating.start", 1000);
    }

    /** 计算双方积分变化：返回 [winnerDelta, loserDelta]。 */
    public int[] computeRatingDelta(int winnerRating, int loserRating) {
        double k = config.getInt("rating.k-factor", 32);
        double expected = 1.0 / (1.0 + Math.pow(10, (loserRating - winnerRating) / 400.0));
        int winnerDelta = (int) Math.round(k * (1 - expected));
        int loserDelta = (int) Math.round(k * (0 - expected));
        if (winnerDelta < 1) winnerDelta = 1;
        if (loserDelta > -1) loserDelta = -1;
        return new int[]{winnerDelta, loserDelta};
    }

    // ---------------- 记录 ----------------

    /**
     * 记录一场完整比赛的结果。
     * @param winnerRounds / loserRounds 为总比分（局数）
     */
    public void recordMatch(UUID winnerU, UUID loserU, int winnerKills, int loserKills,
                            int winnerRounds, int loserRounds, boolean draw) {
        PlayerStats ws = get(winnerU);
        PlayerStats ls = get(loserU);
        ws.matches++;
        ls.matches++;
        ws.kills += winnerKills;
        ls.kills += loserKills;
        ws.deaths += loserKills;
        ls.deaths += winnerKills;
        ws.roundsWon += winnerRounds;
        ws.roundsLost += loserRounds;
        ls.roundsWon += loserRounds;
        ls.roundsLost += winnerRounds;

        int[] delta = {0, 0};
        if (ratingEnabled()) {
            delta = computeRatingDelta(ws.rating, ls.rating);
        }
        if (draw) {
            ws.draws++;
            ls.draws++;
            ws.streak = 0;
            ls.streak = 0;
        } else {
            ws.wins++;
            ls.losses++;
            ws.streak = Math.max(1, ws.streak + 1);
            ls.streak = Math.min(-1, ls.streak - 1);
            ws.bestStreak = Math.max(ws.bestStreak, ws.streak);
            ls.bestStreak = Math.max(ls.bestStreak, -ls.streak);
            if (ratingEnabled()) {
                ws.rating = Math.max(0, ws.rating + delta[0]);
                ls.rating = Math.max(0, ls.rating + delta[1]);
            }
        }
        save();
    }

    // ---------------- 查询 ----------------

    public List<Map.Entry<UUID, PlayerStats>> top(int count) {
        List<Map.Entry<UUID, PlayerStats>> list = new ArrayList<>(stats.entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<UUID, PlayerStats> e) -> e.getValue().rating).reversed());
        return list.subList(0, Math.min(count, list.size()));
    }

    public String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        // 不联网查询离线玩家名，保持轻量
        return uuid.toString().substring(0, 8);
    }
}
