package com.apple9317.accduel.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 轻量 GUI 框架：每个玩家同时只维护一个会话，点击回调消费事件。
 *
 * <p>会话在以下时机清理，避免内存泄漏与「幽灵界面」继续响应点击：
 * <ul>
 *   <li>玩家关闭界面（{@link #onClose}）；</li>
 *   <li>玩家退出服务器（{@link #clear}）；</li>
 *   <li>同一玩家打开新界面时自动覆盖旧会话（{@link #open}）。</li>
 * </ul>
 * </p>
 */
public class GuiManager {

    public static class GuiSession {
        public final Inventory inventory;
        public final Consumer<InventoryClickEvent> handler;

        public GuiSession(Inventory inventory, Consumer<InventoryClickEvent> handler) {
            this.inventory = inventory;
            this.handler = handler;
        }

        /** 判断某个界面是否属于本会话。 */
        public boolean matches(Inventory other) {
            return other != null && inventory.equals(other);
        }
    }

    private final Map<UUID, GuiSession> sessions = new HashMap<>();

    public void open(Player player, Component title, int rows, Consumer<InventoryClickEvent> handler) {
        if (player == null || handler == null) return;
        int size = Math.max(1, Math.min(6, rows)) * 9;
        Inventory inv = Bukkit.createInventory(null, size, title == null ? Component.empty() : title);
        // 覆盖旧会话：先关掉正在显示的旧界面，避免两个界面同时响应点击
        GuiSession old = sessions.put(player.getUniqueId(), new GuiSession(inv, handler));
        if (old != null && player.getOpenInventory().getTopInventory().equals(old.inventory)) {
            player.closeInventory();
        }
        player.openInventory(inv);
    }

    public GuiSession session(UUID uuid) {
        return uuid == null ? null : sessions.get(uuid);
    }

    /** 关闭事件触发时清理（只清理与该界面匹配的会话，避免影响连续开界面）。 */
    public void onClose(Player player, Inventory closed) {
        if (player == null) return;
        GuiSession s = sessions.get(player.getUniqueId());
        if (s != null && s.matches(closed)) {
            sessions.remove(player.getUniqueId());
        }
    }

    public void closeAll(Player player) {
        if (player == null) return;
        GuiSession s = sessions.remove(player.getUniqueId());
        if (s != null && player.getOpenInventory().getTopInventory().equals(s.inventory)) {
            player.closeInventory();
        }
    }

    /** 玩家退出时清理会话，防止会话表随在线时长无限增长。 */
    public void clear(Player player) {
        if (player != null) sessions.remove(player.getUniqueId());
    }

    /** 填充装饰底栏（最后一行灰色玻璃）。 */
    public static void decorate(Inventory inv, int rows, Component glassName) {
        if (inv == null) return;
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.displayName(glassName == null ? Component.empty() : glassName);
            glass.setItemMeta(meta);
        }
        int size = inv.getSize();
        int start = Math.max(0, (rows - 1) * 9);
        for (int i = start; i < size && i < rows * 9; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass.clone());
        }
    }
}
