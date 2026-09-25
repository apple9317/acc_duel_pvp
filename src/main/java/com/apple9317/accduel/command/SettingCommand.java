package com.apple9317.accduel.command;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.config.ConfigManager;
import com.apple9317.accduel.gui.GuiManager;
import com.apple9317.accduel.killeffect.KillEffect;
import com.apple9317.accduel.setting.PlayerSettings;
import com.apple9317.accduel.setting.SettingsManager;
import com.apple9317.accduel.util.Txt;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /duelsetting 个人设置：
 * <ul>
 *   <li>开关他人决斗申请；</li>
 *   <li>选择个人击杀特效（default = 跟随服务器全局）。</li>
 * </ul>
 * Java 版箱子界面，基岩版原生表单。
 */
public class SettingCommand implements CommandExecutor, TabCompleter {

    /** 特效选项 id（顺序即界面从左到右）；击杀特效纯个人设置，默认无。 */
    private static final List<String> EFFECT_IDS = List.of(
            "lightning", "explosion", "heart", "soul", "fire", "death", "none");
    /** 各选项图标。 */
    private static final List<Material> EFFECT_ICONS = List.of(
            Material.LIGHTNING_ROD, Material.TNT, Material.POPPY,
            Material.SOUL_LANTERN, Material.BLAZE_POWDER, Material.SKELETON_SKULL, Material.BARRIER);
    /** 第三行 slots 20-26（7 格居中）。 */
    private static final List<Integer> EFFECT_SLOTS = List.of(20, 21, 22, 23, 24, 25, 26);

    private final ACCDuelPlugin plugin;
    private final ConfigManager config;
    private final SettingsManager settings;
    private final GuiManager gui;

    public SettingCommand(ACCDuelPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.settings = plugin.getSettingsManager();
        this.gui = plugin.getGuiManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            config.sendRaw(sender, "only-player");
            return true;
        }
        openSettings(player);
        return true;
    }

    /** 打开个人设置：基岩表单优先，否则箱子界面。 */
    public void openSettings(Player player) {
        PlayerSettings ps = settings.get(player);
        List<String> labels = new ArrayList<>();
        for (String id : EFFECT_IDS) labels.add(displayLabel(id));
        int selected = Math.max(0, EFFECT_IDS.indexOf(
                ps.killEffect == null ? "none" : ps.killEffect.toLowerCase(Locale.ROOT)));

        boolean bedrock = plugin.getGeyserManager().isBedrock(player);
        if (bedrock) {
            boolean sent = plugin.getGeyserManager().sendSettingsForm(player, "个人设置",
                    ps.acceptRequests, ps.modernUi, labels, selected, (accept, modern, effect) -> {
                        if (accept != null) ps.acceptRequests = accept;
                        if (modern != null) ps.modernUi = modern;
                        if (effect != null) {
                            String id = mapLabelToId(effect);
                            if (id != null) ps.killEffect = id;
                        }
                        settings.save();
                    });
            if (sent) return;
        }
        openChestGui(player);
    }

    private void openChestGui(Player player) {
        gui.open(player, Txt.mm("<gold>个人设置</gold>"), 3, event -> {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            PlayerSettings ps = settings.get(player);
            if (slot == 2) {
                ps.modernUi = !ps.modernUi;
                settings.save();
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f);
                refresh(player);
                return;
            }
            if (slot == 4) {
                ps.acceptRequests = !ps.acceptRequests;
                settings.save();
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f);
                refresh(player);
                return;
            }
            int idx = EFFECT_SLOTS.indexOf(slot);
            if (idx >= 0) {
                ps.killEffect = EFFECT_IDS.get(idx);
                settings.save();
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1.2f);
                refresh(player);
            }
        });
        GuiManager.GuiSession session = gui.session(player.getUniqueId());
        if (session == null) return;
        render(player, session.inventory);
    }

    /** 刷新：重新打开当前界面（GUI 内容随设置变化）。 */
    private void refresh(Player player) {
        gui.closeAll(player);
        openChestGui(player);
    }

    private void render(Player player, Inventory inv) {
        PlayerSettings ps = settings.get(player);

        // slot 2：新版本UI 开关
        var uiItem = new ItemStack(Material.PAINTING);
        ItemMeta uim = uiItem.getItemMeta();
        if (uim != null) {
            uim.displayName(Txt.mm("<yellow>新版本UI</yellow>"));
            uim.lore(Arrays.asList(
                    Txt.mm("当前：" + (ps.modernUi ? "<green>开启</green>" : "<red>关闭</red>")),
                    Txt.mm("<gray>仅 1.21.6+ 客户端有效，点击切换</gray>")));
            uiItem.setItemMeta(uim);
        }
        inv.setItem(2, uiItem);

        // slot 4：申请开关
        var toggle = new ItemStack(ps.acceptRequests ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta tm = toggle.getItemMeta();
        if (tm != null) {
            tm.displayName(Txt.mm("<yellow>决斗申请</yellow>"));
            tm.lore(Arrays.asList(
                    Txt.mm("当前：" + (ps.acceptRequests ? "<green>开启</green>" : "<red>关闭</red>")),
                    Txt.mm("<gray>点击切换</gray>")));
            toggle.setItemMeta(tm);
        }
        inv.setItem(4, toggle);

        // 特效选项
        String current = ps.killEffect == null ? "none" : ps.killEffect.toLowerCase(Locale.ROOT);
        for (int i = 0; i < EFFECT_IDS.size(); i++) {
            String id = EFFECT_IDS.get(i);
            var item = new ItemStack(EFFECT_ICONS.get(i));
            ItemMeta m = item.getItemMeta();
            if (m != null) {
                boolean chosen = id.equals(current);
                m.displayName(Txt.mm((chosen ? "<green>" : "<gray>") + displayLabel(id) + (chosen ? " ✓</green>" : "</gray>")));
                List<Component> lore = new ArrayList<>();
                lore.add(Txt.mm(chosen ? "<green>已选中</green>" : "<gray>点击选择</gray>"));
                m.lore(lore);
                if (chosen) {
                    m.addEnchant(Enchantment.UNBREAKING, 1, true);
                    m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
                item.setItemMeta(m);
            }
            inv.setItem(EFFECT_SLOTS.get(i), item);
        }
        GuiManager.decorate(inv, 3, Txt.mm("<dark_gray> </dark_gray>"));
    }

    private static String displayLabel(String id) {
        return switch (id) {
            case "lightning" -> "闪电";
            case "explosion" -> "爆炸";
            case "heart" -> "爱心";
            case "soul" -> "灵魂";
            case "fire" -> "火焰";
            case "death" -> "死亡烟雾";
            case "none" -> "无";
            default -> id;
        };
    }

    /** 基岩表单返回的是中文标签，映射回 id。 */
    private static String mapLabelToId(String label) {
        for (String id : EFFECT_IDS) {
            if (displayLabel(id).equals(label)) return id;
        }
        // 兼容直接返回 id 的情况
        return EFFECT_IDS.contains(label) ? label : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
