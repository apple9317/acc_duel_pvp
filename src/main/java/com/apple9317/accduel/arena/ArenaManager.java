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
    /** load 时保留每个竞技场在 yml 里的原始点位数据，save 时用于兜底，避免内存解析失败把已有点位擦掉。 */
    private final Map<String, ConfigurationSection> rawSections = new java.util.HashMap<>();
    private File file;
    private boolean dirty;

    public ArenaManager(ACCDuelPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        arenas.clear();
        rawSections.clear();
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
            rawSections.put(arena.id, s);
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
        for (Arena.Point p : new Arena.Point[]{arena.pos1, arena.pos2, arena.spawnRed, arena.spawnBlue, arena.bedRed, arena.bedBlue}) {
            if (p != null && worldName.equals(p.world)) return true;
        }
        return false;
    }

    public synchronized void save() {
        if (file == null) return;
        YamlConfiguration config = new YamlConfiguration();
        for (Arena arena : arenas.values()) {
            String base = "arenas." + arena.id;
            ConfigurationSection raw = rawSections.get(arena.id);
            config.set(base + ".type", arena.type);
            config.set(base + ".world", arena.world);
            // 内存点位为 null 时，保留 yml 里原有的点位数据，避免解析失败静默擦除
            config.set(base + ".pos1", arena.pos1 == null ? (raw == null ? null : sectionMap(raw, "pos1")) : arena.pos1.toMap());
            config.set(base + ".pos2", arena.pos2 == null ? (raw == null ? null : sectionMap(raw, "pos2")) : arena.pos2.toMap());
            config.set(base + ".spawn-red", arena.spawnRed == null ? (raw == null ? null : sectionMap(raw, "spawn-red")) : arena.spawnRed.toMap());
            config.set(base + ".spawn-blue", arena.spawnBlue == null ? (raw == null ? null : sectionMap(raw, "spawn-blue")) : arena.spawnBlue.toMap());
            config.set(base + ".bed-red", arena.bedRed == null ? (raw == null ? null : sectionMap(raw, "bed-red")) : arena.bedRed.toMap());
            config.set(base + ".bed-blue", arena.bedBlue == null ? (raw == null ? null : sectionMap(raw, "bed-blue")) : arena.bedBlue.toMap());
            if (arena.minY != null) config.set(base + ".miny", arena.minY);
            else if (raw != null && raw.contains("miny")) config.set(base + ".miny", raw.get("miny"));
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
        arena.pos1 = Arena.Point.fromMap(sectionMap(s, "pos1"), arena.world);
        arena.pos2 = Arena.Point.fromMap(sectionMap(s, "pos2"), arena.world);
        arena.spawnRed = Arena.Point.fromMap(sectionMap(s, "spawn-red"), arena.world);
        arena.spawnBlue = Arena.Point.fromMap(sectionMap(s, "spawn-blue"), arena.world);
        arena.bedRed = Arena.Point.fromMap(sectionMap(s, "bed-red"), arena.world);
        arena.bedBlue = Arena.Point.fromMap(sectionMap(s, "bed-blue"), arena.world);
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

    /** ConfigurationSection.get(key) 返回的是 MemorySection 而非 Map，转成纯 Map 供 Point.fromMap 使用。 */
    private static Map<String, Object> sectionMap(ConfigurationSection s, String key) {
        ConfigurationSection cs = s == null ? null : s.getConfigurationSection(key);
        return cs == null ? null : cs.getValues(false);
    }

    private static String firstWorld(Arena arena) {
        for (Arena.Point p : new Arena.Point[]{arena.pos1, arena.pos2, arena.spawnRed, arena.spawnBlue, arena.bedRed, arena.bedBlue}) {
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

    /** 设置床点位：找玩家附近最近的床头；没找到或竞技场不存在返回 false。 */
    public boolean setBedNearPlayer(String id, String color, org.bukkit.entity.Player player) {
        Arena arena = get(id);
        if (arena == null || player == null) return false;
        Location head = findNearestBedHead(player.getLocation(),
                com.apple9317.accduel.kit.special_kit.BedFight.SEARCH_RADIUS);
        if (head == null) return false;
        Arena.Point p = Arena.Point.of(head);
        if ("red".equalsIgnoreCase(color)) arena.bedRed = p;
        else if ("blue".equalsIgnoreCase(color)) arena.bedBlue = p;
        else return false;
        arena.world = head.getWorld().getName();
        save();
        return true;
    }

    /** 搜索 origin 附近最近的床头方块。 */
    private static Location findNearestBedHead(Location origin, int radius) {
        World w = origin.getWorld();
        Location best = null;
        double bestD = Double.MAX_VALUE;
        int bx = origin.getBlockX(), by = origin.getBlockY(), bz = origin.getBlockZ();
        for (int x = bx - radius; x <= bx + radius; x++) {
            for (int y = by - radius; y <= by + radius; y++) {
                for (int z = bz - radius; z <= bz + radius; z++) {
                    org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                    if (!(b.getBlockData() instanceof org.bukkit.block.data.type.Bed bed)) continue;
                    if (bed.getPart() != org.bukkit.block.data.type.Bed.Part.HEAD) continue;
                    Location l = b.getLocation();
                    double d = l.distanceSquared(origin);
                    if (d < bestD) { bestD = d; best = l; }
                }
            }
        }
        return best;
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

    /**
     * 判断位置是否处于任意竞技场的 pos1-pos2 保护区域内。
     * 用于禁止破坏/放置方块、桶、爆炸等。
     */
    public boolean isProtected(Location loc) {
        if (loc == null) return false;
        for (Arena arena : arenas.values()) {
            if (arena.inBounds(loc)) return true;
        }
        return false;
    }

    // ---------------- 方块模板（世界快照） ----------------

    private Path templateDir() {
        return plugin.getDataFolder().toPath().resolve("data").resolve("templates");
    }

    private Path templateFile(String id) {
        return templateDir().resolve(id + ".json");
    }

    /**
     * /duel arena set <id> save：抓取区域方块并保存模板。
     *
     * @return 保存成功的模板（含方块数）；竞技场不存在 / 点位不足 / 世界未加载 / 写盘失败均返回 null。
     */
    public ArenaTemplate saveTemplate(String id) {
        Arena arena = get(id);
        if (arena == null) return null;
        ArenaTemplate template = ArenaTemplate.capture(arena);
        if (template == null) return null;
        try {
            template.save(templateFile(id));
            return template;
        } catch (IOException e) {
            plugin.getLogger().warning("保存竞技场模板失败: " + e.getMessage());
            return null;
        }
    }

    /** 该竞技场是否已保存过模板。 */
    public boolean hasTemplate(String id) {
        return Files.exists(templateFile(id));
    }

    /**
     * 比赛结束后用模板复原竞技场区域；无模板或世界未加载则跳过。
     */
    public void restoreTemplate(Arena arena) {
        if (arena == null) return;
        try {
            ArenaTemplate template = ArenaTemplate.load(templateFile(arena.id));
            if (template == null) return;
            int changed = template.restore(plugin);
            if (changed > 0) {
                plugin.getLogger().info("竞技场 " + arena.id + " 已复原 " + changed + " 个方块");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("复原竞技场模板失败: " + e.getMessage());
        }
    }
}
