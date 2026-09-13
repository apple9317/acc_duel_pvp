package com.apple9317.accduel.arena;

import com.apple9317.accduel.ACCDuelPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 竞技场管理：持久化到 arenas.yml。
 *
 * <p>坐标以「世界名 + 数值」原样保存与读取，不在加载时解析成 Location，
 * 因此竞技场所在世界晚于本插件加载时点位不会丢失，保存也不会把坐标清空。</p>
 */
public class ArenaManager {

    private final ACCDuelPlugin plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private File file;
    private boolean dirty;

    public ArenaManager(ACCDuelPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        arenas.clear();
        file = new File(plugin.getDataFolder(), "arenas.yml");
        if (!file.exists()) {
            try {
                plugin.saveResource("arenas.yml", false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("无法生成默认 arenas.yml: " + e.getMessage());
            }
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("arenas");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;
            Arena arena = parse(id, s);
            if (arena == null) continue;
            arenas.put(arena.id, arena);
            plugin.getLogger().info("已加载竞技场: " + arena.id + statusSuffix(arena));
        }
    }

    public void reload() {
        load();
    }

    private String statusSuffix(Arena arena) {
        if (arena.isComplete()) return "";
        if (arena.isConfigured()) return "（所在世界未加载，暂不可用）";
        return "（未配置完整）";
    }

    /** 世界加载后重新校验竞技场可用性。 */
    public void onWorldLoaded(World world) {
        if (world == null) return;
        String name = world.getName();
        for (Arena arena : arenas.values()) {
            if (arena.isComplete() || !arena.isConfigured()) continue;
            if (name.equals(arena.world) || usesWorld(arena, name)) {
                plugin.getLogger().info("竞技场 " + arena.id + " 所在世界已加载，现已可用");
            }
        }
    }

    private static boolean usesWorld(Arena arena, String worldName) {
        for (Arena.Point p : new Arena.Point[]{arena.pos1, arena.pos2, arena.spawnRed, arena.spawnBlue}) {
            if (p != null && worldName.equals(p.world)) return true;
        }
        return false;
    }

    public synchronized void save() {
        if (file == null) return;
        YamlConfiguration config = new YamlConfiguration();
        for (Arena arena : arenas.values()) {
            String base = "arenas." + arena.id;
            config.set(base + ".type", arena.type);
            config.set(base + ".world", arena.world);
            config.set(base + ".pos1", arena.pos1 == null ? null : arena.pos1.toMap());
            config.set(base + ".pos2", arena.pos2 == null ? null : arena.pos2.toMap());
            config.set(base + ".spawn-red", arena.spawnRed == null ? null : arena.spawnRed.toMap());
            config.set(base + ".spawn-blue", arena.spawnBlue == null ? null : arena.spawnBlue.toMap());
            if (arena.minY != null) config.set(base + ".miny", arena.minY);
            config.set(base + ".enabled", arena.enabled);
        }
        try {
            Path target = file.toPath();
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            config.save(tmp.toFile());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            dirty = true;
            plugin.getLogger().warning("保存竞技场配置失败: " + e.getMessage());
        }
    }

    /** 上次保存失败时补写一次（关服兜底）。 */
    public void flushIfDirty() {
        if (dirty) save();
    }

    private Arena parse(String id, ConfigurationSection s) {
        if (s == null || id == null || id.isEmpty()) return null;
        Arena arena = new Arena();
        arena.id = id.toLowerCase(Locale.ROOT);
        arena.type = s.getString("type", "no_debuff");
        arena.world = s.getString("world");
        arena.pos1 = Arena.Point.fromMap(s.get("pos1"));
        arena.pos2 = Arena.Point.fromMap(s.get("pos2"));
        arena.spawnRed = Arena.Point.fromMap(s.get("spawn-red"));
        arena.spawnBlue = Arena.Point.fromMap(s.get("spawn-blue"));
        // 旧版存档里 miny 用 Double.MIN_VALUE 表示「未设置」，这里一并兼容
        if (s.contains("miny")) {
            double y = s.getDouble("miny");
            arena.minY = y == Double.MIN_VALUE ? null : y;
        }
        arena.enabled = s.getBoolean("enabled", true);
        if (arena.world == null || arena.world.isEmpty()) {
            arena.world = firstWorld(arena);
        }
        return arena;
    }

    private static String firstWorld(Arena arena) {
        for (Arena.Point p : new Arena.Point[]{arena.pos1, arena.pos2, arena.spawnRed, arena.spawnBlue}) {
            if (p != null && p.isSet()) return p.world;
        }
        return null;
    }

    // ---------------- 操作 ----------------

    public Arena get(String id) {
        if (id == null) return null;
        return arenas.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Arena> all() {
        return new ArrayList<>(arenas.values());
    }

    public boolean contains(String id) {
        return id != null && arenas.containsKey(id.toLowerCase(Locale.ROOT));
    }

    /** 创建竞技场（绑定竞技类型，类型配置缺失时由 KitManager 自动生成）。 */
    public Arena create(String id, String type) {
        Arena arena = new Arena();
        arena.id = id.toLowerCase(Locale.ROOT);
        arena.type = (type == null || type.isEmpty()) ? "no_debuff" : type.toLowerCase(Locale.ROOT);
        arenas.put(arena.id, arena);
        save();
        return arena;
    }

    public boolean remove(String id) {
        if (id == null) return false;
        boolean removed = arenas.remove(id.toLowerCase(Locale.ROOT)) != null;
        if (removed) save();
        return removed;
    }

    public boolean setPos(String id, String key, Location loc) {
        Arena arena = get(id);
        if (arena == null || loc == null || loc.getWorld() == null) return false;
        Arena.Point point = Arena.Point.of(loc);
        switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
            case "pos1" -> arena.pos1 = point;
            case "pos2" -> arena.pos2 = point;
            default -> {
                return false;
            }
        }
        arena.world = loc.getWorld().getName();
        save();
        return true;
    }

    /** 设置进入地图出生点（红/蓝方）。 */
    public boolean setSpawn(String id, String color, Location loc) {
        Arena arena = get(id);
        if (arena == null || loc == null || loc.getWorld() == null) return false;
        Arena.Point point = Arena.Point.of(loc);
        if ("red".equalsIgnoreCase(color)) {
            arena.spawnRed = point;
        } else if ("blue".equalsIgnoreCase(color)) {
            arena.spawnBlue = point;
        } else {
            return false;
        }
        arena.world = loc.getWorld().getName();
        save();
        return true;
    }

    /** 设置虚空判负线；传 null 表示恢复为「世界最低高度」。 */
    public boolean setMinY(String id, Double y) {
        Arena arena = get(id);
        if (arena == null) return false;
        arena.minY = y;
        save();
        return true;
    }

    /**
     * 找该类型下配置完整且未被占用的竞技场。
     *
     * @return 查找结果，便于区分「没有该类型竞技场」/「都被占用」/「世界未加载」
     */
    public ArenaSearch findAvailable(String type, Predicate<Arena> occupied) {
        String t = (type == null || type.isEmpty()) ? "no_debuff" : type.toLowerCase(Locale.ROOT);
        boolean typeExists = false;
        boolean allBusy = false;
        boolean worldMissing = false;
        for (Arena arena : arenas.values()) {
            if (!arena.enabled) continue;
            if (arena.type != null && !arena.type.equalsIgnoreCase(t)) continue;
            typeExists = true;
            if (!arena.isConfigured()) continue;
            if (!arena.isComplete()) {
                worldMissing = true;
                continue;
            }
            if (occupied.test(arena)) {
                allBusy = true;
                continue;
            }
            return new ArenaSearch(arena, typeExists, allBusy, worldMissing);
        }
        return new ArenaSearch(null, typeExists, allBusy, worldMissing);
    }

    /** 竞技场查找结果。 */
    public static class ArenaSearch {
        public final Arena arena;
        /** 是否存在该类型的竞技场（含被占用/世界未加载的）。 */
        public final boolean typeExists;
        /** 该类型的竞技场是否全部被占用。 */
        public final boolean allBusy;
        /** 是否存在配置好了但世界未加载的竞技场。 */
        public final boolean worldMissing;

        ArenaSearch(Arena arena, boolean typeExists, boolean allBusy, boolean worldMissing) {
            this.arena = arena;
            this.typeExists = typeExists;
            this.allBusy = allBusy;
            this.worldMissing = worldMissing;
        }

        public boolean found() {
            return arena != null;
        }
    }
}
