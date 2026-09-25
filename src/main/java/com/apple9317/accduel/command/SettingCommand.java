package com.apple9317.accduel.command;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.config.ConfigManager;
import com.apple9317.accduel.dialog.DialogManager;
import com.apple9317.accduel.gui.GuiManager;
import com.apple9317.accduel.setting.PlayerSettings;
import com.apple9317.accduel.setting.SettingsManager;
import com.apple9317.accduel.util.Txt;
import com.apple9317.accduel.version.ViaManager;
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
 * /duelsetting 个人设置：决斗申请、新版本 UI、个人击杀特效。
 * 屏幕对话框（1.21.6+）/ 箱子界面 / 基岩表单。
 */
public class SettingCommand implements CommandExecutor, TabCompleter {

    private static final List<String> EFFECT_IDS = List.of(
            "lightning", "explosion", "heart", "soul", "fire", "death", "none");
    private static final List<Material> EFFECT_ICONS = List.of(
            Material.LIGHTNING_ROD, Material.TNT, Material.POPPY,
            Material.SOUL_LANTERN, Material.BLAZE_POWDER, Material.SKELETON_SKULL, Material.BARRIER);
    private static final List<Integer> EFFECT_SUB_SLOTS = List.of(10, 11, 12, 13, 14, 15, 16);
    private static final int ENTRY_SLOT = 22;
    private static final int BACK_SLOT = 22;

    private final ACCDuelPlugin plugin;
    private final ConfigManager config;
    private final SettingsManager settings;
    private final GuiManager gui;
    private final DialogManager dialogs;

    public SettingCommand(ACCDuelPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.settings = plugin.getSettingsManager();
        this.gui = plugin.getGuiManager();
        this.dialogs = plugin.getDialogManager();
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

    /** 打开个人设置：基岩表单 / 屏幕对话框 / 箱子界面。 */
    public void openSettings(Player player) {
        PlayerSettings ps = settings.get(player);

        if (plugin.getGeyserManager().isBedrock(player)) {
            List<String> labels = new ArrayList<>();
            for (String id : EFFECT_IDS) labels.add(displayLabel(id));
            int selected = Math.max(0, EFFECT_IDS.indexOf(currentEffect(ps)));
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

        if (!plugin.getGeyserManager().isBedrock(player) && dialogs.isSupported()
                && clientSupportsDialog(player) && ps.modernUi) {
            List<Component> displays = new ArrayList<>();
            for (String id : EFFECT_IDS) displays.add(Component.text(displayLabel(id)));
            int selected = Math.max(0, EFFECT_IDS.indexOf(currentEffect(ps)));
            boolean shown = dialogs.showSettings(player, Txt.mm("<gold>个人设置</gold>"),
                    ps.acceptRequests, ps.modernUi, displays, EFFECT_IDS, selected,
                    (save, accept, modern, effectId, audience) -> {
                        if (!save) return;
                        if (accept != null) ps.acceptRequests = accept;
                        if (effectId != null && EFFECT_IDS.contains(effectId)) ps.killEffect = effectId;
                        boolean oldModern = ps.modernUi;
                        if (modern != null) ps.modernUi = modern;
                        settings.save();
                        if (oldModern && !ps.modernUi) openChestGui(player);
                        else closeDialog(player);
                    });
            if (shown) return;
        }
        openChestGui(player);
    }

    // ---------------- 箱子界面（fallback） ----------------

    private void openChestGui(Player player) {
        gui.open(player, Txt.mm("<gold>个人设置</gold>"), 3, event -> {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            PlayerSettings ps = settings.get(player);
            if (slot == 2) {
                ps.modernUi = !ps.modernUi;
                settings.save();
                click(player);
                if (ps.modernUi && dialogs.isSupported() && clientSupportsDialog(player)) {
                    openSettings(player);
                    return;
                }
                updateMainInPlace(player);
                return;
            }
            if (slot == 4) {
                ps.acceptRequests = !ps.acceptRequests;
                settings.save();
                click(player);
                updateMainInPlace(player);
                return;
            }
            if (slot == ENTRY_SLOT) openEffectGui(player);
        });
        GuiManager.GuiSession session = gui.session(player.getUniqueId());
        if (session == null) return;
        renderMain(player, session.inventory);
    }

    private void updateMainInPlace(Player player) {
        GuiManager.GuiSession s = gui.session(player.getUniqueId());
        if (s != null) renderMain(player, s.inventory);
    }

    private void renderMain(Player player, Inventory inv) {
        PlayerSettings ps = settings.get(player);

        inv.setItem(2, simpleItem(Material.PAINTING, "<yellow>新版本UI</yellow>", Arrays.asList(
                Txt.mm("当前：" + onOff(ps.modernUi)),
                Txt.mm("<gray>仅 1.21.6+ 客户端有效，点击切换</gray>"))));

        inv.setItem(4, simpleItem(ps.acceptRequests ? Material.LIME_DYE : Material.GRAY_DYE,
                "<yellow>决斗申请</yellow>", Arrays.asList(
                        Txt.mm("当前：" + onOff(ps.acceptRequests)),
                        Txt.mm("<gray>点击切换</gray>"))));

        inv.setItem(ENTRY_SLOT, simpleItem(Material.NETHER_STAR, "<yellow>击杀特效</yellow>", Arrays.asList(
                Txt.mm("当前：<green>" + displayLabel(currentEffect(ps)) + "</green>"),
                Txt.mm("<gray>点击打开选择界面</gray>"))));

        GuiManager.decorate(inv, 3, Txt.mm("<dark_gray> </dark_gray>"));
    }

    private void openEffectGui(Player player) {
        gui.open(player, Txt.mm("<gold>选择击杀特效</gold>"), 3, event -> {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            int idx = EFFECT_SUB_SLOTS.indexOf(slot);
            if (idx >= 0) {
                settings.get(player).killEffect = EFFECT_IDS.get(idx);
                settings.save();
                click(player);
                openSettings(player);
                return;
            }
            if (slot == BACK_SLOT) openSettings(player);
        });
        GuiManager.GuiSession session = gui.session(player.getUniqueId());
        if (session == null) return;
        Inventory inv = session.inventory;

        String current = currentEffect(settings.get(player));
        for (int i = 0; i < EFFECT_IDS.size(); i++) {
            String id = EFFECT_IDS.get(i);
            boolean chosen = id.equals(current);
            ItemStack item = simpleItem(EFFECT_ICONS.get(i),
                    (chosen ? "<green>" : "<gray>") + displayLabel(id) + (chosen ? " ✓</green>" : "</gray>"),
                    List.of(Txt.mm(chosen ? "<green>已选中</green>" : "<gray>点击选择</gray>")));
            ItemMeta m = item.getItemMeta();
            if (chosen && m != null) {
                m.addEnchant(Enchantment.UNBREAKING, 1, true);
                m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(m);
            }
            inv.setItem(EFFECT_SUB_SLOTS.get(i), item);
        }
        inv.setItem(BACK_SLOT, simpleItem(Material.ARROW, "<yellow>返回</yellow>",
                List.of(Txt.mm("<gray>返回个人设置</gray>"))));
        GuiManager.decorate(inv, 3, Txt.mm("<dark_gray> </dark_gray>"));
    }

    // ---------------- 工具 ----------------

    private static String currentEffect(PlayerSettings ps) {
        return ps.killEffect == null ? "none" : ps.killEffect.toLowerCase(Locale.ROOT);
    }

    private static String onOff(boolean on) {
        return on ? "<green>开启</green>" : "<red>关闭</red>";
    }

    private static ItemStack simpleItem(Material material, String name, List<Component> lore) {
        var item = new ItemStack(material);
        ItemMeta m = item.getItemMeta();
        if (m != null) {
            m.displayName(Txt.mm(name));
            m.lore(lore);
            item.setItemMeta(m);
        }
        return item;
    }

    private static void click(Player p) {
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    private void closeDialog(Player p) {
        try {
            p.getClass().getMethod("closeDialog").invoke(p);
        } catch (Throwable t) {
            p.closeInventory();
        }
    }

    private boolean clientSupportsDialog(Player p) {
        if (!viaPresent()) return true;
        return ViaManager.clientAtLeast1216(p);
    }

    private static boolean viaPresent() {
        try {
            Class.forName("com.viaversion.viaversion.api.Via");
            return true;
        } catch (Throwable t) {
            return false;
        }
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

    private static String mapLabelToId(String label) {
        for (String id : EFFECT_IDS) {
            if (displayLabel(id).equals(label)) return id;
        }
        return EFFECT_IDS.contains(label) ? label : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}