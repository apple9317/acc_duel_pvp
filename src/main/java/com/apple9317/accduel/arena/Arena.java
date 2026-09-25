package com.apple9317.accduel.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 决斗竞技场。
 * <ul>
 *   <li>pos1/pos2：两名选手的战斗出生点（倒计时与战斗都在这里）</li>
 *   <li>spawnRed/spawnBlue：进入地图时的红/蓝方出生点（匹配成功后被传送进地图的落点）</li>
 *   <li>minY：低于该 Y 判负（可选，未设置时使用世界最低高度）</li>
 *   <li>type：竞技类型 id（对应 kits/&lt;type&gt;.yml 装备配置）</li>
 * </ul>
 *
 * <p>坐标以「世界名 + 数值」形式保存，取用时才解析成 {@link Location}；
 * 这样即使竞技场所在世界在插件之后才加载，点位也不会丢失。</p>
 */
public class Arena {

    /** 一个可延迟解析的坐标点。 */
    public static class Point {
        public String world;
        public double x, y, z;
        public float yaw, pitch;

        public boolean isSet() {
            return world != null && !world.isEmpty();
        }

        /** 解析为 Location；世界未加载时返回 null。 */
        public Location resolve() {
            if (!isSet()) return null;
            World w = Bukkit.getWorld(world);
            if (w == null) return null;
            return new Location(w, x, y, z, yaw, pitch);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!isSet()) return m;
            m.put("world", world);
            m.put("x", x);
            m.put("y", y);
            m.put("z", z);
            m.put("yaw", (double) yaw);
            m.put("pitch", (double) pitch);
            return m;
        }

        /** 反序列化；旧版存档的点位 Map 没有 world 键（world 存在竞技场顶层），用 fallbackWorld 兜底。 */
        public static Point fromMap(Object raw, String fallbackWorld) {
            if (!(raw instanceof Map<?, ?>)) return null;
            Map<?, ?> m = (Map<?, ?>) raw;
            Object worldObj = m.get("world");
            String world = worldObj != null ? String.valueOf(worldObj) : fallbackWorld;
            if (world == null || world.isEmpty()) return null;
            Point p = new Point();
            p.world = world;
            p.x = num(m.get("x"));
            p.y = num(m.get("y"));
            p.z = num(m.get("z"));
            p.yaw = (float) num(m.get("yaw"));
            p.pitch = (float) num(m.get("pitch"));
            return p;
        }

        public static Point of(Location loc) {
            if (loc == null || loc.getWorld() == null) return null;
            Point p = new Point();
            p.world = loc.getWorld().getName();
            p.x = loc.getX();
            p.y = loc.getY();
            p.z = loc.getZ();
            p.yaw = loc.getYaw();
            p.pitch = loc.getPitch();
            return p;
        }

        private static double num(Object v) {
            return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
        }
    }

    public String id;
    /** 竞技类型 id（对应 kits/<type>.yml）。 */
    public String type;
    /** 竞技场主世界名（由最后一次点位设置决定，仅用于展示与兜底）。 */
    public String world;
    /** 选手 1 战斗出生点。 */
    public Point pos1;
    /** 选手 2 战斗出生点。 */
    public Point pos2;
    /** 红方进入地图出生点。 */
    public Point spawnRed;
    /** 蓝方进入地图出生点。 */
    public Point spawnBlue;
    /** 红方床（bedfight 专用，记录床头位置）。 */
    public Point bedRed;
    /** 蓝方床（bedfight 专用）。 */
    public Point bedBlue;
    /** 低于该 Y 判负；null 表示未设置（使用世界最低高度）。 */
    public Double minY;
    public boolean enabled = true;

    public boolean isComplete() {
        boolean base = id != null && enabled
                && ready(pos1) && ready(pos2) && ready(spawnRed) && ready(spawnBlue);
        if (!base || !isBedFight()) return base;
        return ready(bedRed) && ready(bedBlue);
    }

    /** 点位已配置且所在世界已加载。 */
    private static boolean ready(Point p) {
        return p != null && p.isSet() && p.resolve() != null;
    }

    /** 点位已配置（不要求世界已加载），用于区分「没配」和「世界没加载」。 */
    public boolean isConfigured() {
        boolean base = id != null && enabled
                && pos1 != null && pos1.isSet() && pos2 != null && pos2.isSet()
                && spawnRed != null && spawnRed.isSet() && spawnBlue != null && spawnBlue.isSet();
        if (!base || !isBedFight()) return base;
        return bedRed != null && bedRed.isSet() && bedBlue != null && bedBlue.isSet();
    }

    /** 是否为起床战争单挑类型。 */
    public boolean isBedFight() {
        return com.apple9317.accduel.kit.special_kit.BedFight.TYPE.equals(type);
    }

    /** 选手进入地图 / 每回合开局的出生点（red = 选手 1）。 */
    public Location entryPoint(boolean red) {
        return red ? (spawnRed == null ? null : spawnRed.resolve()) : (spawnBlue == null ? null : spawnBlue.resolve());
    }

    /** 观战者落点（无红方出生点时回退到 pos1）。 */
    public Location spectatorPoint() {
        Location red = spawnRed == null ? null : spawnRed.resolve();
        if (red != null) return red;
        return pos1 == null ? null : pos1.resolve();
    }

    /** 虚空判负线；未设置时取世界最低高度，再兜底 -64。 */
    public double effectiveMinY() {
        if (minY != null) return minY;
        World w = primaryWorld();
        return w != null ? w.getMinHeight() : -64;
    }

    public World primaryWorld() {
        if (world != null) {
            World w = Bukkit.getWorld(world);
            if (w != null) return w;
        }
        for (Point p : new Point[]{pos1, pos2, spawnRed, spawnBlue}) {
            if (p != null && p.isSet()) {
                World w = Bukkit.getWorld(p.world);
                if (w != null) return w;
            }
        }
        return null;
    }

    /**
     * 判断坐标是否落在竞技场范围内（以四个点位的最远距离为半径，外加余量）。
     * 用于阻止比赛中被外部插件传送逃跑。
     */
    public boolean contains(Location loc, double margin) {
        if (loc == null || loc.getWorld() == null) return false;
        World w = primaryWorld();
        if (w == null || !w.equals(loc.getWorld())) return false;

        double cx = 0, cy = 0, cz = 0;
        int n = 0;
        for (Point p : new Point[]{pos1, pos2, spawnRed, spawnBlue}) {
            if (p == null || !p.isSet() || !p.world.equals(loc.getWorld().getName())) continue;
            cx += p.x;
            cy += p.y;
            cz += p.z;
            n++;
        }
        if (n == 0) return false;
        cx /= n;
        cy /= n;
        cz /= n;

        double radius = 0;
        for (Point p : new Point[]{pos1, pos2, spawnRed, spawnBlue}) {
            if (p == null || !p.isSet() || !p.world.equals(loc.getWorld().getName())) continue;
            double d = Math.sqrt(sq(p.x - cx) + sq(p.y - cy) + sq(p.z - cz));
            radius = Math.max(radius, d);
        }
        radius = radius * 2 + Math.max(16, margin);
        double dist = Math.sqrt(sq(loc.getX() - cx) + sq(loc.getY() - cy) + sq(loc.getZ() - cz));
        return dist <= radius;
    }

    /**
     * pos1-pos2 区域的整数包围范围：[minX,minY,minZ,maxX,maxY,maxZ]；点位不足返回 null。
     * 模板保存、复原、区域保护共用此范围。
     */
    public int[] blockBounds() {
        if (pos1 == null || pos2 == null || !pos1.isSet() || !pos2.isSet()) return null;
        int minX = (int) Math.floor(Math.min(pos1.x, pos2.x));
        int maxX = (int) Math.floor(Math.max(pos1.x, pos2.x));
        int minZ = (int) Math.floor(Math.min(pos1.z, pos2.z));
        int maxZ = (int) Math.floor(Math.max(pos1.z, pos2.z));
        int minY = (int) Math.floor(Math.min(pos1.y, pos2.y)) - 1;
        int maxY = (int) Math.floor(Math.max(pos1.y, pos2.y)) + 6;
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * 判断位置是否在 pos1-pos2 围成的轴对齐包围盒内（用于爆炸方块保护）。
     */
    public boolean inBounds(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        int[] b = blockBounds();
        if (b == null || !loc.getWorld().getName().equals(pos1.world)) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    /** 判断方块是否为指定队伍（red/blue）的床（点床头或床尾都算）。 */
    public boolean isTeamBed(org.bukkit.block.Block block, boolean red) {
        Point bed = red ? bedRed : bedBlue;
        if (bed == null || !bed.isSet()) return false;
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Bed bedData)) return false;
        Location head;
        if (bedData.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD) {
            head = block.getLocation();
        } else {
            head = block.getRelative(bedData.getFacing()).getLocation();
        }
        return head.getWorld().getName().equals(bed.world)
                && head.getBlockX() == (int) Math.floor(bed.x)
                && head.getBlockY() == (int) Math.floor(bed.y)
                && head.getBlockZ() == (int) Math.floor(bed.z);
    }

    private static double sq(double v) {
        return v * v;
    }
}
