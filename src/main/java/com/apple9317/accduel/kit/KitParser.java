package com.apple9317.accduel.kit;

import com.apple9317.accduel.util.Compat;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把配置 Map（kits.yml 的 YamlConfiguration 或 Gson 反序列化的自定义方案）解析为 {@link Kit}。
 * 容错：单个字段/物品解析失败只跳过并告警，不影响整份方案。
 */
public final class KitParser {

    private final Plugin plugin;

    public KitParser(Plugin plugin) {
        this.plugin = plugin;
    }

    public Kit parse(String id, Map<String, Object> m) {
        Kit kit = new Kit();
        kit.id = id;
        kit.displayName = str(m, "display-name", id);
        kit.icon = str(m, "icon", "DIAMOND_SWORD");
        kit.basePotion = str(m, "base-potion", null);
        kit.description = strList(m, "description");
        kit.permission = str(m, "permission", "");
        kit.enabled = bool(m, "enabled", true);

        Map<String, Object> attrs = map(m, "attributes");
        if (attrs != null) {
            for (Map.Entry<String, Object> e : attrs.entrySet()) {
                Double v = asDouble(e.getValue());
                if (v != null) {
                    kit.attributes.put(normalizeAttrKey(e.getKey()), v);
                }
            }
        }

        List<Object> effectList = list(m, "effects");
        if (effectList != null) {
            for (Object o : effectList) {
                if (o instanceof Map<?, ?>) {
                    EffectDataWrap w = parseEffect(asStringMap(o));
                    if (w != null) kit.effects.add(w.effect);
                }
            }
        }

        Map<String, Object> rulesMap = map(m, "rules");
        if (rulesMap != null) {
            Kit.KitRules rules = new Kit.KitRules();
            if (rulesMap.containsKey("natural-regen")) rules.naturalRegen = asBoolean(rulesMap.get("natural-regen"));
            if (rulesMap.containsKey("hunger")) rules.hunger = asBoolean(rulesMap.get("hunger"));
            if (rulesMap.containsKey("health")) rules.health = asDouble(rulesMap.get("health"));
            if (rulesMap.containsKey("food-level")) rules.foodLevel = asInt(rulesMap.get("food-level"));
            kit.rules = rules;
        }

        List<Object> itemList = list(m, "items");
        if (itemList != null) {
            for (Object o : itemList) {
                if (o instanceof Map<?, ?>) {
                    KitItem item = parseItem(asStringMap(o));
                    if (item != null) kit.items.add(item);
                }
            }
        }
        return kit;
    }

    private KitItem parseItem(Map<String, Object> m) {
        KitItem item = new KitItem();
        item.slot = intOr(m, "slot", -1);
        item.materialName = str(m, "material", "");
        item.amount = intOr(m, "amount", 1);
        item.name = str(m, "name", null);
        item.lore = strList(m, "lore");
        item.unbreakable = bool(m, "unbreakable", false);
        item.customModelData = intOr(m, "custom-model-data", 0);
        item.flags = strList(m, "flags");
        item.leatherColor = str(m, "leather-color", null);

        Map<String, Object> enchants = map(m, "enchants");
        if (enchants != null) {
            for (Map.Entry<String, Object> e : enchants.entrySet()) {
                Integer level = asInt(e.getValue());
                if (level == null) continue;
                Enchantment ench = Compat.enchantment(e.getKey());
                if (ench == null) {
                    plugin.getLogger().warning("装备方案物品 '" + item.materialName + "' 的附魔 ID 无效: " + e.getKey());
                    continue;
                }
                item.enchants.put(e.getKey().toLowerCase(Locale.ROOT), level);
            }
        }

        List<Object> attrList = list(m, "attributes");
        if (attrList != null) {
            for (Object o : attrList) {
                if (!(o instanceof Map<?, ?>)) continue;
                Map<String, Object> am = asStringMap(o);
                String attrName = str(am, "attribute", "");
                Double amount = asDouble(am.get("amount"));
                if (attrName.isEmpty() || amount == null) continue;
                Attribute attribute = Compat.attribute(attrName);
                if (attribute == null) {
                    plugin.getLogger().warning("装备方案物品 '" + item.materialName + "' 的属性 ID 无效: " + attrName);
                    continue;
                }
                AttributeModifier.Operation operation = Compat.operation(str(am, "operation", "add_number"));
                EquipmentSlot slot = null;
                String slotName = str(am, "slot", "");
                if (!slotName.isEmpty()) {
                    try {
                        slot = EquipmentSlot.valueOf(slotName.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        plugin.getLogger().warning("装备方案物品属性作用部位无效: " + slotName);
                    }
                }
                item.attributes.add(new KitItem.ItemAttribute(attribute, amount, operation, slot));
            }
        }

        List<Object> potionList = list(m, "potion-effects");
        if (potionList != null) {
            for (Object o : potionList) {
                if (!(o instanceof Map<?, ?>)) continue;
                EffectDataWrap w = parseEffect(asStringMap(o));
                if (w != null) item.potionEffects.add(w.effect);
            }
        }

        String basePotion = str(m, "base-potion", null);
        if (basePotion == null) basePotion = str(m, "potion-type", null);
        item.basePotion = basePotion;

        if (item.slot < 0 || item.slot > 40) {
            plugin.getLogger().warning("装备方案物品槽位无效（0-40）: " + item.slot + "，已跳过");
            return null;
        }
        if (item.materialName.isEmpty()) {
            plugin.getLogger().warning("装备方案物品缺少 material 字段，已跳过");
            return null;
        }
        return item;
    }

    private static class EffectDataWrap {
        final KitItem.EffectData effect;

        EffectDataWrap(KitItem.EffectData effect) {
            this.effect = effect;
        }
    }

    private EffectDataWrap parseEffect(Map<String, Object> m) {
        String type = str(m, "type", "");
        if (type.isEmpty() || Compat.potionType(type) == null) {
            plugin.getLogger().warning("药水效果 ID 无效: " + type);
            return null;
        }
        int duration = intOr(m, "duration", 200);
        int amplifier = intOr(m, "amplifier", 0);
        return new EffectDataWrap(new KitItem.EffectData(type.toLowerCase(Locale.ROOT), duration, amplifier));
    }

    // ---------- 通用取数工具（兼容 YAML 与 Gson 的类型差异） ----------

    private static String normalizeAttrKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Boolean b = asBoolean(m.get(key));
        return b == null ? def : b;
    }

    private static int intOr(Map<String, Object> m, String key, int def) {
        Integer i = asInt(m.get(key));
        return i == null ? def : i;
    }

    private static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List<?>)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }

    private static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List<?> ? new ArrayList<>((List<?>) v) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object o) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static Boolean asBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.valueOf((String) v);
        return null;
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
