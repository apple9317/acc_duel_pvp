package com.apple9317.accduel.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 跨版本兼容层。
 *
 * 插件以 Paper 1.21.1 API 编译，但需运行在 1.21.x 至 26.2。
 * 已用 javap 对比过两个端点（1.21.1 与 26.2.build.121-stable）的 API：
 *  - Attribute 常量在 26.2 被重命名（GENERIC_MAX_HEALTH -> MAX_HEALTH），必须反射查找；
 *  - AttributeModifier(UUID, String, double, Operation[, EquipmentSlot]) 在两个版本都存在，可直接调用；
 *  - Registry.ENCHANTMENT / Registry.POTION_EFFECT_TYPE 在 1.21.1 与 26.2 都存在。
 */
public final class Compat {

    private static final Map<String, Attribute> ATTR_CACHE = new HashMap<>();

    private Compat() {
    }

    /** 按属性 ID 获取 Attribute（支持 max_health、movement_speed、attack_damage 等）。 */
    public static Attribute attribute(String key) {
        if (key == null) return null;
        String norm = key.trim().toLowerCase(Locale.ROOT);
        Attribute cached = ATTR_CACHE.get(norm);
        if (cached != null) return cached;
        String upper = norm.toUpperCase(Locale.ROOT);
        // 1.21.x：GENERIC_MAX_HEALTH；26.2：MAX_HEALTH
        String[] candidates = {"GENERIC_" + upper, upper};
        for (String fieldName : candidates) {
            try {
                Field field = Attribute.class.getField(fieldName);
                Object value = field.get(null);
                if (value instanceof Attribute) {
                    Attribute attr = (Attribute) value;
                    ATTR_CACHE.put(norm, attr);
                    return attr;
                }
            } catch (Throwable ignored) {
                // 尝试下一个候选名
            }
        }
        return null;
    }

    /** 创建属性修饰符（ADD_NUMBER 默认），slot 可为 null。 */
    public static AttributeModifier modifier(double amount, String operation, EquipmentSlot slot) {
        AttributeModifier.Operation op = operation(operation);
        return newModifier(amount, op, slot);
    }

    public static AttributeModifier.Operation operation(String name) {
        if (name == null) return AttributeModifier.Operation.ADD_NUMBER;
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "add_scalar_multiplier":
            case "add_scalar":
            case "add_percent":
            case "percent":
                return scalarOperation();
            case "multiply_scalar_1":
            case "multiply":
                return AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            case "add_number":
            case "add":
            default:
                return AttributeModifier.Operation.ADD_NUMBER;
        }
    }

    /**
     * ADD_SCALAR 在 1.21.2+ 为 ADD_SCALAR，旧版本为 ADD_SCALAR_MULTIPLIER，
     * 用反射兼容两者。
     */
    private static AttributeModifier.Operation scalarOperation() {
        try {
            return (AttributeModifier.Operation) AttributeModifier.Operation.class.getField("ADD_SCALAR").get(null);
        } catch (Throwable t) {
            try {
                return (AttributeModifier.Operation) AttributeModifier.Operation.class.getField("ADD_SCALAR_MULTIPLIER").get(null);
            } catch (Throwable t2) {
                return AttributeModifier.Operation.ADD_NUMBER;
            }
        }
    }

    private static final Constructor<?>[] MODIFIER_CTORS = new Constructor<?>[3];

    private static AttributeModifier newModifier(double amount, AttributeModifier.Operation op, EquipmentSlot slot) {
        try {
            // 1.21.2+：NamespacedKey 构造器
            Constructor<?> c = MODIFIER_CTORS[0];
            if (c == null) {
                c = AttributeModifier.class.getConstructor(NamespacedKey.class, double.class, AttributeModifier.Operation.class);
                MODIFIER_CTORS[0] = c;
            }
            return (AttributeModifier) c.newInstance(new NamespacedKey("accduel", "mod" + UUID.randomUUID()), amount, op);
        } catch (Throwable t1) {
            try {
                // 1.21.0/1.21.1：UUID + EquipmentSlot 构造器（26.2 仍保留）
                Constructor<?> c = MODIFIER_CTORS[1];
                if (c == null) {
                    c = AttributeModifier.class.getConstructor(UUID.class, String.class, double.class,
                            AttributeModifier.Operation.class, EquipmentSlot.class);
                    MODIFIER_CTORS[1] = c;
                }
                return (AttributeModifier) c.newInstance(UUID.randomUUID(), "accduel", amount, op, slot);
            } catch (Throwable t2) {
                try {
                    // 最老版本：UUID 无槽位构造器
                    Constructor<?> c = MODIFIER_CTORS[2];
                    if (c == null) {
                        c = AttributeModifier.class.getConstructor(UUID.class, String.class, double.class,
                                AttributeModifier.Operation.class);
                        MODIFIER_CTORS[2] = c;
                    }
                    return (AttributeModifier) c.newInstance(UUID.randomUUID(), "accduel", amount, op);
                } catch (Throwable t3) {
                    throw new IllegalStateException("无法创建属性修饰符", t3);
                }
            }
        }
    }

    /** 按附魔 ID（如 sharpness）获取附魔，优先 Registry，失败回退常量反射。 */
    public static Enchantment enchantment(String key) {
        if (key == null) return null;
        String norm = key.trim().toLowerCase(Locale.ROOT);
        try {
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(norm));
            if (ench != null) return ench;
        } catch (Throwable ignored) {
        }
        try {
            Field field = Enchantment.class.getField(norm.toUpperCase(Locale.ROOT));
            return (Enchantment) field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按药水效果 ID（如 speed）获取 PotionEffectType。 */
    public static PotionEffectType potionType(String key) {
        if (key == null) return null;
        String norm = key.trim().toLowerCase(Locale.ROOT);
        try {
            PotionEffectType type = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(norm));
            if (type != null) return type;
        } catch (Throwable ignored) {
        }
        try {
            Field field = PotionEffectType.class.getField(norm.toUpperCase(Locale.ROOT));
            return (PotionEffectType) field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 跨版本读取玩家最大生命值。
     *
     * <p>1.21.x 的 {@code LivingEntity#getMaxHealth()} 在 26.2 已从该接口移除，
     * 因此优先读 MAX_HEALTH 属性，失败再回退到旧方法，避免运行期 NoSuchMethodError。</p>
     */
    public static double maxHealth(org.bukkit.entity.LivingEntity entity) {
        if (entity == null) return 20.0;
        try {
            Attribute attribute = attribute("max_health");
            if (attribute != null && entity.getAttribute(attribute) != null) {
                return entity.getAttribute(attribute).getValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            return entity.getMaxHealth();
        } catch (Throwable t) {
            return 20.0;
        }
    }
}
