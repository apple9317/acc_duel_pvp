package com.apple9317.accduel.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 竞技类型装备方案（Kit）。每个类型对应 plugins/ACCDuel/kits/&lt;类型&gt;.yml 一个文件。
 */
public class Kit {

    public String id;
    /** 显示名（MiniMessage 或 & 代码）。 */
    public String displayName;
    /** 选择界面图标（材质名）。 */
    public String icon;
    /** 图标药水的基础类型（icon 为药水时生效），如 healing / strong_healing。 */
    public String basePotion;
    public List<String> description = new ArrayList<>();
    /** 使用所需权限，空 = 所有玩家可用。 */
    public String permission = "";
    public boolean enabled = true;
    /**
     * 对玩家本体生效的属性（覆盖基础值）。
     * key 统一为规范 ID：max_health / movement_speed / attack_damage / attack_speed / armor / armor_toughness / knockback_resistance
     */
    public Map<String, Double> attributes = new LinkedHashMap<>();
    /** 进场附加到玩家的药水效果。 */
    public List<KitItem.EffectData> effects = new ArrayList<>();
    /** 对战规则（null 项继承全局 match 配置）。 */
    public KitRules rules = new KitRules();
    /** 装备物品列表。 */
    public List<KitItem> items = new ArrayList<>();

    /** 比赛规则（可继承全局）。 */
    public static class KitRules {
        /** null = 继承全局 match.natural-regen。 */
        public Boolean naturalRegen;
        /** null = 继承全局 match.hunger。 */
        public Boolean hunger;
        /** null = 继承全局 match.health。 */
        public Double health;
        /** null = 继承全局 match.food-level。 */
        public Integer foodLevel;
    }

    public String displayNameRaw() {
        return displayName == null || displayName.isEmpty() ? id : displayName;
    }
}
