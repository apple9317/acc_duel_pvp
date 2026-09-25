package com.apple9317.accduel.kit;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一件装备物品的完整描述（高度自定义：附魔/属性/药水/皮革染色/模型数据等）。
 * 由 {@link KitParser} 从配置（kits.yml 或 Gson 自定义方案）解析而来。
 */
public class KitItem {

    /** 背包槽位：0-8 快捷栏，9-35 主背包，36 头盔/37 胸甲/38 护腿/39 靴子，40 副手。 */
    public int slot = -1;
    /** 物品材质名（如 DIAMOND_SWORD），仅在解析失败时为空。 */
    public String materialName;
    public int amount = 1;
    /** 显示名（MiniMessage 或 & 代码）。 */
    public String name;
    public List<String> lore = new ArrayList<>();
    public Map<String, Integer> enchants = new LinkedHashMap<>();
    public boolean unbreakable;
    public int customModelData;
    public List<String> flags = new ArrayList<>();
    public List<ItemAttribute> attributes = new ArrayList<>();
    /** 皮革染色，如 22AA55（仅对皮革装备生效）。 */
    public String leatherColor;
    /** 药水效果（仅对药水物品生效）。 */
    public List<EffectData> potionEffects = new ArrayList<>();
    /**
     * 基础药水类型（仅对药水物品生效），决定药水外观/名称/自带效果，
     * 如 healing（治疗药水 I）、strong_healing（治疗药水 II）、swiftness 等；
     * 不设置则为普通水瓶外观。
     */
    public String basePotion;

    /** 物品级属性修饰符。 */
    public static class ItemAttribute {
        public Attribute attribute;
        public double amount;
        public AttributeModifier.Operation operation = AttributeModifier.Operation.ADD_NUMBER;
        public EquipmentSlot slot; // 可为 null

        public ItemAttribute(Attribute attribute, double amount, AttributeModifier.Operation operation, EquipmentSlot slot) {
            this.attribute = attribute;
            this.amount = amount;
            this.operation = operation;
            this.slot = slot;
        }
    }

    /** 药水效果数据。 */
    public static class EffectData {
        public String type;      // 效果 ID，如 speed
        public int duration;     // 秒
        public int amplifier;

        public EffectData(String type, int duration, int amplifier) {
            this.type = type;
            this.duration = duration;
            this.amplifier = amplifier;
        }
    }
}
