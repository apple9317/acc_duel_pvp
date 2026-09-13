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

        public static Point fromMap(Object raw) {
            if (!(raw instanceof Map<?, ?>)) return null;
            Map<?, ?> m = (Map<?, ?>) raw;
            Object worldObj = m.get("world");
            if (worldObj == null) return null;
            Point p = new Point();
            p.world = String.valueOf(worldObj);
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
    /** 低于该 Y 判负；null 表示未设置（使用世界最低高度）。 */
    public Double minY;
    public boolean enabled = true;

    public boolean isComplete() {
        return id != null && enabled
                && ready(pos1) && ready(pos2) && ready(spawnRed) && ready(spawnBlue);
    }

    /** 点位已配置且所在世界已加载。 */
    private static boolean ready(Point p) {
        return p != null && p.isSet() && p.resolve() != null;
    }

    /** 点位已配置（不要求世界已加载），用于区分「没配」和「世界没加载」。 */
    public boolean isConfigured() {
        return id != null && enabled
                && pos1 != null && pos1.isSet() && pos2 != null && pos2.isSet()
                && spawnRed != null && spawnRed.isSet() && spawnBlue != null && spawnBlue.isSet();
    }

    /** 战斗出生点（red = 选手 1）。 */
    public Location fightPos(boolean red) {
        return red ? (pos1 == null ? null : pos1.resolve()) : (pos2 == null ? null : pos2.resolve());
    }

    /** 选手进入地图时的出生点（red = 选手 1）。 */
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

    private static double sq(double v) {
        return v * v;
    }
}
