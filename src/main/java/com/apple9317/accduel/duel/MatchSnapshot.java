package com.apple9317.accduel.duel;

import com.apple9317.accduel.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 比赛开始前玩家的完整状态快照，比赛结束后原样恢复
 * （背包、生命、饥饿、经验、模式、飞行、药水、属性基础值、位置）。
 *
 * <p>物品使用 {@code ItemStack#serializeItemsAsBytes} 的二进制格式（Base64 存储），
 * 以完整保留附魔、自定义名称、组件等数据；可落盘用于掉线恢复。</p>
 */
public class MatchSnapshot {

    public ItemStack[] contents;
    public ItemStack[] armor;
    public ItemStack[] extra;
    public double health;
    public int food;
    public float saturation;
    public int level;
    public float exp;
    public GameMode gameMode;
    public boolean allowFlight;
    public boolean flying;
    public Location location;
    public List<PotionEffect> effects = new ArrayList<>();
    /** 方案属性覆盖前的基础值：key(规范属性名) -> 原基础值。 */
    public Map<String, Double> attrBase = new LinkedHashMap<>();
    /** 比赛结束时玩家仍处于死亡界面，待重生后恢复。 */
    public boolean pendingRestore;

    public static MatchSnapshot capture(Player p) {
        MatchSnapshot s = new MatchSnapshot();
        PlayerInventory inv = p.getInventory();
        s.contents = cloneItems(inv.getContents());
        s.armor = cloneItems(inv.getArmorContents());
        s.extra = cloneItems(inv.getExtraContents());
        s.health = p.getHealth();
        s.food = p.getFoodLevel();
        s.saturation = p.getSaturation();
        s.level = p.getLevel();
        s.exp = p.getExp();
        s.gameMode = p.getGameMode();
        s.allowFlight = p.getAllowFlight();
        s.flying = p.isFlying();
        s.location = p.getLocation().clone();
        s.effects = new ArrayList<>(p.getActivePotionEffects());
        return s;
    }

    private static ItemStack[] cloneItems(ItemStack[] src) {
        if (src == null) return new ItemStack[0];
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i] == null ? null : src[i].clone();
        }
        return out;
    }

    /** 恢复玩家状态。任何单项失败都不应阻断其余项，因此整体包在 try/finally 中。 */
    public void restore(Player p) {
        try {
            p.clearActivePotionEffects();
            // 先恢复属性基础值（可能影响最大生命，必须先于 setHealth）
            for (Map.Entry<String, Double> e : attrBase.entrySet()) {
                org.bukkit.attribute.Attribute attribute = Compat.attribute(e.getKey());
                if (attribute != null && p.getAttribute(attribute) != null) {
                    p.getAttribute(attribute).setBaseValue(e.getValue());
                }
            }
            double maxHealth = Compat.maxHealth(p);
            p.setHealth(Math.min(health, maxHealth));
            p.setFoodLevel(food);
            p.setSaturation(saturation);
            p.setLevel(level);
            p.setExp(exp);
            p.setGameMode(gameMode == null ? GameMode.SURVIVAL : gameMode);
            p.setAllowFlight(allowFlight);
            p.setFlying(allowFlight && flying);

            PlayerInventory inv = p.getInventory();
            applyContents(inv, contents);
            applyFixed(inv::setArmorContents, armor, 4);
            applyFixed(inv::setExtraContents, extra, 1);

            for (PotionEffect effect : effects) {
                p.addPotionEffect(effect);
            }
            if (location != null && location.getWorld() != null) {
                p.teleport(location);
            }
        } finally {
            pendingRestore = false;
        }
    }

    /** 逐槽写入，避免数组长度与背包容量不一致时抛异常。 */
    private static void applyContents(PlayerInventory inv, ItemStack[] items) {
        if (items == null) return;
        int size = inv.getSize();
        int limit = Math.min(items.length, size);
        for (int i = 0; i < limit; i++) {
            inv.setItem(i, items[i] == null ? null : items[i].clone());
        }
    }

    private static void applyFixed(java.util.function.Consumer<ItemStack[]> setter, ItemStack[] items, int expected) {
        if (items == null) return;
        ItemStack[] copy = new ItemStack[expected];
        for (int i = 0; i < expected && i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        setter.accept(copy);
    }

    // ---------------- 落盘（掉线恢复） ----------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contents", encodeItems(contents));
        m.put("armor", encodeItems(armor));
        m.put("extra", encodeItems(extra));
        m.put("health", health);
        m.put("food", food);
        m.put("saturation", (double) saturation);
        m.put("level", level);
        m.put("exp", (double) exp);
        m.put("game-mode", (gameMode == null ? GameMode.SURVIVAL : gameMode).name());
        m.put("allow-flight", allowFlight);
        m.put("flying", flying);
        if (location != null && location.getWorld() != null) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("world", location.getWorld().getName());
            lm.put("x", location.getX());
            lm.put("y", location.getY());
            lm.put("z", location.getZ());
            lm.put("yaw", (double) location.getYaw());
            lm.put("pitch", (double) location.getPitch());
            m.put("location", lm);
        }
        List<Map<String, Object>> eff = new ArrayList<>();
        for (PotionEffect e : effects) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("type", e.getType().getName());
            em.put("duration", e.getDuration());
            em.put("amplifier", e.getAmplifier());
            em.put("ambient", e.isAmbient());
            em.put("particles", e.hasParticles());
            em.put("icon", e.hasIcon());
            eff.add(em);
        }
        m.put("effects", eff);
        m.put("attr-base", new LinkedHashMap<>(attrBase));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static MatchSnapshot fromMap(Map<?, ?> raw) {
        if (raw == null) return null;
        MatchSnapshot s = new MatchSnapshot();
        s.contents = decodeItems(asString(raw.get("contents")));
        s.armor = decodeItems(asString(raw.get("armor")));
        s.extra = decodeItems(asString(raw.get("extra")));
        s.health = asDouble(raw.get("health"), 20.0);
        s.food = (int) asDouble(raw.get("food"), 20);
        s.saturation = (float) asDouble(raw.get("saturation"), 5.0);
        s.level = (int) asDouble(raw.get("level"), 0);
        s.exp = (float) asDouble(raw.get("exp"), 0.0);
        String gm = asString(raw.get("game-mode"));
        try {
            s.gameMode = GameMode.valueOf(gm.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            s.gameMode = GameMode.SURVIVAL;
        }
        s.allowFlight = Boolean.TRUE.equals(raw.get("allow-flight"));
        s.flying = Boolean.TRUE.equals(raw.get("flying"));
        Object locObj = raw.get("location");
        if (locObj instanceof Map<?, ?>) {
            Map<?, ?> lm = (Map<?, ?>) locObj;
            World w = Bukkit.getWorld(asString(lm.get("world")));
            if (w != null) {
                s.location = new Location(w, asDouble(lm.get("x"), 0), asDouble(lm.get("y"), 64),
                        asDouble(lm.get("z"), 0), (float) asDouble(lm.get("yaw"), 0),
                        (float) asDouble(lm.get("pitch"), 0));
            }
        }
        Object effObj = raw.get("effects");
        if (effObj instanceof List<?>) {
            for (Object o : (List<?>) effObj) {
                if (!(o instanceof Map<?, ?>)) continue;
                Map<?, ?> em = (Map<?, ?>) o;
                PotionEffectType type = Compat.potionType(asString(em.get("type")));
                if (type == null) continue;
                try {
                    s.effects.add(new PotionEffect(type,
                            (int) asDouble(em.get("duration"), 200),
                            (int) asDouble(em.get("amplifier"), 0),
                            Boolean.TRUE.equals(em.get("ambient")),
                            !Boolean.FALSE.equals(em.get("particles")),
                            !Boolean.FALSE.equals(em.get("icon"))));
                } catch (Throwable ignored) {
                }
            }
        }
        Object attrObj = raw.get("attr-base");
        if (attrObj instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) attrObj).entrySet()) {
                s.attrBase.put(String.valueOf(e.getKey()), asDouble(e.getValue(), 20.0));
            }
        }
        return s;
    }

    private static String encodeItems(ItemStack[] items) {
        if (items == null) return "";
        try {
            return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
        } catch (Throwable t) {
            return "";
        }
    }

    private static ItemStack[] decodeItems(String encoded) {
        if (encoded == null || encoded.isEmpty()) return new ItemStack[0];
        try {
            ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
            return items == null ? new ItemStack[0] : items;
        } catch (Throwable t) {
            return new ItemStack[0];
        }
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
