package com.apple9317.accduel.kit;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.config.ConfigManager;
import com.apple9317.accduel.util.Compat;
import com.apple9317.accduel.util.Txt;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 竞技类型（装备方案）管理。
 *
 * 每个竞技类型对应 plugins/ACCDuel/kits/&lt;类型&gt;.yml 一个配置文件；
 * 默认模板 kits/no_debuff.yml 打包在 jar 资源里，服务端首次运行时自动复制生成
 * （保护 III 钻套 + 锋利 I 钻剑 + 满背包治疗药水），可自由编辑后 /duel reload 生效。
 */
public class KitManager {

    private final ACCDuelPlugin plugin;
    private final KitParser parser;
    /** 类型 id -> Kit */
    private final Map<String, Kit> types = new LinkedHashMap<>();

    private Path kitsFolder;

    public KitManager(ACCDuelPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.parser = new KitParser(plugin);
    }

    public void load() throws IOException {
        this.kitsFolder = plugin.getDataFolder().toPath().resolve("kits");
        Files.createDirectories(kitsFolder);
        // 服务端首次运行时默认生成 kits/no_debuff.yml（已存在则不覆盖）
        ensureTypeFile("no_debuff");
        loadTypes();
    }

    public void reload() {
        loadTypes();
    }

    // ---------------- 类型加载 ----------------

    private void loadTypes() {
        types.clear();
        if (kitsFolder == null || !Files.isDirectory(kitsFolder)) return;
        try (var stream = Files.list(kitsFolder)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted()
                    .forEach(p -> {
                        String id = p.getFileName().toString().replaceFirst("(?i)\\.yml$", "").toLowerCase(Locale.ROOT);
                        try {
                            YamlConfiguration yml = YamlConfiguration.loadConfiguration(p.toFile());
                            Kit kit = parser.parse(id, toMap(yml.getValues(false)));
                            types.put(id, kit);
                            plugin.getLogger().info("已加载竞技类型: " + id + "（" + kit.items.size() + " 件装备）");
                        } catch (Exception e) {
                            plugin.getLogger().warning("加载竞技类型 " + id + " 失败: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            plugin.getLogger().warning("读取装备类型目录失败: " + e.getMessage());
        }
    }

    public Kit getType(String id) {
        if (id == null) return null;
        return types.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Kit> getTypes() {
        return new ArrayList<>(types.values());
    }

    public Path typeFile(String type) {
        return kitsFolder.resolve(type.toLowerCase(Locale.ROOT) + ".yml");
    }

    public boolean hasTypeFile(String type) {
        try {
            return type != null && Files.exists(typeFile(type));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 确保某类型存在：文件缺失时从 jar 资源复制模板生成并加载。
     * 资源里有 kits/&lt;id&gt;.yml 则用该模板，否则回退到内置的 no_debuff 模板。
     */
    public Kit ensureType(String type) {
        String id = type == null ? "no_debuff" : type.toLowerCase(Locale.ROOT);
        ensureTypeFile(id);
        if (getType(id) == null) reload();
        return getType(id);
    }

    /** 类型文件缺失时从资源复制生成（已存在则不覆盖）。 */
    private void ensureTypeFile(String id) {
        try {
            if (hasTypeFile(id)) return;
            Files.createDirectories(kitsFolder);
            if (!extractFromResources(id)) {
                plugin.getLogger().warning("未找到内置类型模板 " + id + "，请手动创建 kits/" + id + ".yml");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("生成竞技类型配置失败: " + e.getMessage());
        }
    }

    /** 从 jar 资源复制类型模板；资源里没有 &lt;id&gt;.yml 时回退到 no_debuff 模板。 */
    private boolean extractFromResources(String id) throws IOException {
        String path = "kits/" + id.toLowerCase(Locale.ROOT) + ".yml";
        InputStream in = plugin.getResource(path);
        if (in == null) {
            in = plugin.getResource("kits/no_debuff.yml");
        }
        if (in == null) return false;
        try (InputStream is = in) {
            Files.copy(is, typeFile(id));
        }
        plugin.getLogger().info("已生成竞技类型配置: kits/" + id + ".yml");
        return true;
    }

    // ---------------- 权限 ----------------

    public boolean hasKitPermission(Player player, Kit kit) {
        if (kit.permission == null || kit.permission.isEmpty()) return true;
        if (kit.permission.equals("accduel.kit.*")) return player.hasPermission("accduel.kit.*");
        return player.hasPermission(kit.permission) || player.hasPermission("accduel.kit.*");
    }

    // ---------------- 应用方案 ----------------

    /**
     * 清空背包并应用方案（装备、属性、药水效果）。
     * 属性会覆盖玩家基础值，调用方需先通过 {@link #recordAttributeBase} 记录原值。
     */
    public void applyKit(Player player, Kit kit) {
        player.getInventory().clear();
        for (KitItem item : kit.items) {
            ItemStack stack = buildItem(item);
            if (stack == null) continue;
            player.getInventory().setItem(item.slot, stack);
        }
        for (Map.Entry<String, Double> e : kit.attributes.entrySet()) {
            Attribute attribute = Compat.attribute(e.getKey());
            if (attribute == null) continue;
            if (player.getAttribute(attribute) != null) {
                player.getAttribute(attribute).setBaseValue(e.getValue());
            }
        }
        player.addPotionEffects(toPotionEffects(kit.effects));
    }

    /** 应用方案前记录玩家当前属性基础值（用于恢复）。 */
    public Map<String, Double> recordAttributeBase(Player player, Kit kit) {
        Map<String, Double> original = new LinkedHashMap<>();
        for (String key : kit.attributes.keySet()) {
            Attribute attribute = Compat.attribute(key);
            if (attribute == null || player.getAttribute(attribute) == null) continue;
            original.put(key, player.getAttribute(attribute).getBaseValue());
        }
        return original;
    }

    public void restoreAttributes(Player player, Map<String, Double> original) {
        for (Map.Entry<String, Double> e : original.entrySet()) {
            Attribute attribute = Compat.attribute(e.getKey());
            if (attribute == null || player.getAttribute(attribute) == null) continue;
            player.getAttribute(attribute).setBaseValue(e.getValue());
        }
    }

    private List<PotionEffect> toPotionEffects(List<KitItem.EffectData> effects) {
        List<PotionEffect> out = new ArrayList<>();
        for (KitItem.EffectData e : effects) {
            PotionEffectType type = Compat.potionType(e.type);
            if (type == null) continue;
            out.add(new PotionEffect(type, e.duration * 20, Math.max(0, e.amplifier), false, false));
        }
        return out;
    }

    /** 供比赛引擎使用的公开入口。 */
    public List<PotionEffect> toPotionEffectsPublic(List<KitItem.EffectData> effects) {
        return toPotionEffects(effects);
    }

    // ---------------- 物品构建 ----------------

    /** 按配置构建 ItemStack。 */
    public ItemStack buildItem(KitItem item) {
        Material material = Material.matchMaterial(item.materialName.toUpperCase(Locale.ROOT));
        if (material == null) {
            plugin.getLogger().warning("装备方案物品材质无效: " + item.materialName);
            return null;
        }
        ItemStack stack = new ItemStack(material, Math.max(1, item.amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        if (item.name != null && !item.name.isEmpty()) {
            meta.displayName(Txt.parse(item.name));
        }
        if (!item.lore.isEmpty()) {
            meta.lore(Txt.parseAll(item.lore));
        }
        for (Map.Entry<String, Integer> e : item.enchants.entrySet()) {
            Enchantment ench = Compat.enchantment(e.getKey());
            if (ench != null) meta.addEnchant(ench, Math.max(1, e.getValue()), true);
        }
        if (item.unbreakable) meta.setUnbreakable(true);
        if (item.customModelData != 0) meta.setCustomModelData(item.customModelData);
        for (String flag : item.flags) {
            try {
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.valueOf(flag.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (KitItem.ItemAttribute attr : item.attributes) {
            if (attr.attribute == null) continue;
            meta.addAttributeModifier(attr.attribute, Compat.modifier(attr.amount, attr.operation.name(), attr.slot));
        }
        if (item.leatherColor != null && !item.leatherColor.isEmpty() && meta instanceof LeatherArmorMeta) {
            Color color = parseColor(item.leatherColor);
            if (color != null) ((LeatherArmorMeta) meta).setColor(color);
        }
        if (meta instanceof PotionMeta) {
            PotionMeta potionMeta = (PotionMeta) meta;
            // 基础药水类型：决定药水外观/名称/自带效果（不设置则为普通水瓶外观）
            if (item.basePotion != null && !item.basePotion.isEmpty()) {
                org.bukkit.potion.PotionType baseType = matchPotionType(item.basePotion);
                if (baseType != null) {
                    potionMeta.setBasePotionType(baseType);
                } else {
                    plugin.getLogger().warning("药水基础类型无效: " + item.basePotion);
                }
            }
            for (KitItem.EffectData e : item.potionEffects) {
                PotionEffectType type = Compat.potionType(e.type);
                if (type == null) continue;
                potionMeta.addCustomEffect(new PotionEffect(type, e.duration * 20, Math.max(0, e.amplifier), false, false), true);
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** 解析基础药水类型（支持新版枚举名与旧版/效果 id 别名）。 */
    private static org.bukkit.potion.PotionType matchPotionType(String raw) {
        String norm = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return org.bukkit.potion.PotionType.valueOf(norm);
        } catch (IllegalArgumentException e) {
            return switch (norm) {
                case "INSTANT_HEALTH", "HEAL" -> org.bukkit.potion.PotionType.HEALING;
                case "INSTANT_HEALTH_2", "STRONG_HEAL" -> org.bukkit.potion.PotionType.STRONG_HEALING;
                case "INSTANT_DAMAGE", "HARM" -> org.bukkit.potion.PotionType.HARMING;
                case "INSTANT_DAMAGE_2", "STRONG_HARM" -> org.bukkit.potion.PotionType.STRONG_HARMING;
                case "JUMP" -> org.bukkit.potion.PotionType.LEAPING;
                default -> null;
            };
        }
    }

    private static Color parseColor(String s) {
        String hex = s.trim().replace("#", "");
        try {
            if (hex.length() == 6) {
                return Color.fromRGB(Integer.parseInt(hex, 16));
            }
        } catch (NumberFormatException ignored) {
        }
        try {
            FieldColor f = FieldColor.valueOf(s.trim().toUpperCase(Locale.ROOT));
            return Color.fromRGB(f.rgb);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private enum FieldColor {
        WHITE(0xFFFFFF), RED(0xFF5555), ORANGE(0xFFAA00), YELLOW(0xFFFF55),
        GREEN(0x55FF55), AQUA(0x55FFFF), BLUE(0x5555FF), PURPLE(0xAA00AA),
        BLACK(0x000000), GRAY(0xAAAAAA);
        final int rgb;

        FieldColor(int rgb) {
            this.rgb = rgb;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object o) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
