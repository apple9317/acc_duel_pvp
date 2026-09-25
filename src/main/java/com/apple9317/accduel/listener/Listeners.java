package com.apple9317.accduel.listener;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.config.ConfigManager;
import com.apple9317.accduel.duel.DuelManager;
import com.apple9317.accduel.duel.Match;
import com.apple9317.accduel.duel.MatchSnapshot;
import com.apple9317.accduel.gui.GuiManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.List;
import java.util.Locale;

/**
 * 全局事件监听：比赛规则、竞技场保护、GUI、状态恢复、Geyser 欢迎等。
 */
public class Listeners implements Listener {

    private final ACCDuelPlugin plugin;
    private final ConfigManager config;
    private final DuelManager manager;
    private final GuiManager gui;
    private final com.apple9317.accduel.arena.ArenaManager arenas;

    public Listeners(ACCDuelPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.manager = plugin.getDuelManager();
        this.gui = plugin.getGuiManager();
        this.arenas = plugin.getArenaManager();
    }



    // ---------------- 加入 / 退出 ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        plugin.getStatsManager().touch(p);
        plugin.getGeyserManager().sendWelcome(p);
        // 上次比赛未完成的赛前状态（比赛中掉线 / 关服）在此恢复
        manager.tryRestore(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        Match match = manager.matchOf(p);
        if (match != null && !match.ended()) {
            // 无论是否判负，都先把赛前状态登记落盘，重连后原样恢复
            MatchSnapshot snapshot = match.snapshot(p.getUniqueId());
            if (snapshot != null) {
                manager.registerPendingRestore(p.getUniqueId(), snapshot);
            }
            if (config.getBoolean("match.forfeit-on-quit", true)) {
                manager.handleForfeit(p);
            } else {
                match.abort(null);
            }
        }
        manager.removeSpectator(p.getUniqueId());
        manager.onQuitCleanup(p);
        gui.closeAll(p);
    }

    // ---------------- 死亡 / 重生 ----------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        Match match = manager.matchOf(p);
        if (match == null) return;
        // 比赛内死亡不广播、不掉物品、不掉经验
        event.deathMessage(null);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        match.handleDeath(p);
        // 自动重生，不让玩家卡在死亡界面等手动点按钮；
        // 延迟 1 tick 是因为死亡事件尚未走完，立即 respawn 会被服务端忽略
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.isDead()) {
                try {
                    p.getClass().getMethod("respawn").invoke(p);
                } catch (Throwable ignored) {
                    try { p.spigot().respawn(); } catch (Throwable ignored2) {}
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();

        // 情况一：比赛已结束，玩家死在结算瞬间。
        // 此时 matchOf 已查不到这场比赛（只返回进行中的），必须走独立的待恢复登记表，
        // 否则赛前背包、位置、属性将永远无法还原。
        if (manager.hasPendingRestore(p.getUniqueId())) {
            Location target = manager.respawnLocationFor(p);
            if (target != null) event.setRespawnLocation(target);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) manager.tryRestore(p);
            }, 1L);
            return;
        }

        // 情况二：比赛仍在进行，本回合阵亡者转为旁观视角等待结果
        Match match = manager.matchOf(p);
        if (match == null || match.ended()) return;
        Location spec = match.arena().spectatorPoint();
        if (spec != null) event.setRespawnLocation(spec);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) match.makeSpectator(p);
        }, 1L);
    }

    // ---------------- 伤害 ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Match victimMatch = manager.matchOf(victim);
        Player attacker = resolveAttacker(event.getDamager());

        if (attacker != null) {
            Match attackerMatch = manager.matchOf(attacker);
            if (victimMatch != null) {
                // 同场对战且处于战斗阶段才允许伤害
                if (attackerMatch == victimMatch && attackerMatch.state() == Match.State.FIGHTING) {
                    // 预判致命一击：取消伤害让玩家不真正死亡（避免比赛结束时卡在死亡界面），直接走击杀结算
                    double finalDamage = event.getFinalDamage();
                    if (finalDamage >= victim.getHealth()) {
                        event.setCancelled(true);
                        attackerMatch.handleSimulatedKill(victim, attacker);
                    }
                    return;
                }
                event.setCancelled(true);
            } else if (attackerMatch != null) {
                event.setCancelled(true); // 比赛选手不能攻击场外玩家/观战者
            }
            return;
        }

        // 非玩家伤害源（TNT、环境等）
        if (victimMatch != null && config.getBoolean("match.cancel-outside-damage", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Match match = manager.matchOf(victim);
        EntityDamageEvent.DamageCause cause = event.getCause();

        if (match == null) {
            // 外部观战者不应受到任何伤害
            if (manager.involves(victim.getUniqueId())) event.setCancelled(true);
            return;
        }

        // 虚空必须能致命，且要早于「非战斗阶段取消伤害」处理，
        // 否则倒计时期间掉进虚空的玩家会一直下落却死不掉。
        if (cause == EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(false);
            event.setDamage(Math.max(event.getDamage(), 400));
            return;
        }

        if (match.state() != Match.State.FIGHTING) {
            event.setCancelled(true);
            return;
        }
        if (cause == EntityDamageEvent.DamageCause.FALL && !config.getBoolean("match.fall-damage", false)) {
            event.setCancelled(true);
            return;
        }
        if (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE) {
            return; // 是否允许由 onDamageByEntity 决定
        }
        if (config.getBoolean("match.cancel-outside-damage", true)) {
            event.setCancelled(true);
        }
    }

    private Player resolveAttacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // ---------------- 回血 / 饥饿 ----------------

    @EventHandler
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        Match match = manager.matchOf(p);
        if (match == null) return;
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (reason != EntityRegainHealthEvent.RegainReason.REGEN
                && reason != EntityRegainHealthEvent.RegainReason.SATIATED
                && reason != EntityRegainHealthEvent.RegainReason.EATING) {
            return;
        }
        boolean regen = match.kit() != null && match.kit().rules.naturalRegen != null
                ? match.kit().rules.naturalRegen
                : config.getBoolean("match.natural-regen", false);
        if (!regen) event.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        Match match = manager.matchOf(p);
        if (match == null) return;
        boolean hunger = match.kit() != null && match.kit().rules.hunger != null
                ? match.kit().rules.hunger
                : config.getBoolean("match.hunger", false);
        if (!hunger) event.setCancelled(true);
    }

    // ---------------- 竞技场保护 ----------------

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        Match match = manager.matchOf(p);
        if (match == null) return;
        Block block = event.getBlock();
        // bedfight：允许挖掉对方的床并标记床毁
        if (match.isOpponentBedBlock(block, p)) {
            match.notifyBedBroken(block, p);
            return;
        }
        // 其余方块仅允许破坏玩家自己放置的
        if (!match.isPlayerPlaced(block.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        Match match = manager.matchOf(p);
        // 比赛中允许放置方块，但记录位置（仅这些方块可被破坏，赛后随模板统一复原）
        if (match != null) match.trackPlacedBlock(event.getBlock().getLocation());
    }

    // 注：水桶/岩浆桶可自由倒、接，不做限制。

    /** 实体爆炸（TNT/末影水晶/苦力怕等）不破坏竞技场区域内的方块。 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> arenas.isProtected(b.getLocation()));
    }

    /** 方块爆炸（床/重生锚）不破坏竞技场区域内的方块。 */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> arenas.isProtected(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!config.getBoolean("match.no-item-drop", true)) return;
        if (manager.inMatch(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!config.getBoolean("match.no-item-pickup", true)) return;
        if (event.getEntity() instanceof Player p && manager.inMatch(p)) event.setCancelled(true);
    }

    /**
     * 比赛中禁止与方块交互（开箱、拉杆、工作台等），但<b>必须保留手中物品的使用权</b>。
     *
     * <p>这里不能调用 {@code event.setCancelled(true)}：该方法会同时把
     * {@code useItemInHand} 置为 DENY，导致玩家对着方块右键时喝不了、也扔不出药水，
     * 而默认装备方案恰恰依赖满背包的治疗药水。因此只拒绝方块交互。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!manager.inMatch(p)) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Material type = block.getType();
        // 只拦截真正可交互的方块；对着普通石头右键不该影响手中物品的使用
        if (type.isInteractable()) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    /** 比赛选手在战斗阶段不允许切出旁观/创造模式（防作弊与防卡状态）。 */
    @EventHandler(ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player p = event.getPlayer();
        Match match = manager.matchOf(p);
        if (match == null || match.state() != Match.State.FIGHTING) return;
        if (event.getNewGameMode() != GameMode.SURVIVAL) {
            event.setCancelled(true);
        }
    }

    // ---------------- 移动 / 传送 ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Match match = manager.matchOf(p);
        if (match != null && match.freezeDuringCountdown(p)) {
            event.setCancelled(true);
        }
    }

    /** 比赛中禁止选手被传送出场地（末影珍珠、传送门、/spawn 等）；插件自身的传送不受影响。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        // RESPAWN 枚举是较新版本才加入的，这里用名称比较以兼容 1.21.1 与 26.2
        if (cause == PlayerTeleportEvent.TeleportCause.PLUGIN
                || cause == PlayerTeleportEvent.TeleportCause.UNKNOWN
                || "RESPAWN".equals(cause.name())) {
            return;
        }
        Player p = event.getPlayer();
        Match match = manager.matchOf(p);
        // 观战者需要自由移动视角，不做限制
        if (match == null) return;
        Location to = event.getTo();
        if (to != null && match.arena().contains(to, 0)) return;
        event.setCancelled(true);
        config.send(p, "teleport-blocked");
    }

    // ---------------- 命令限制 ----------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!manager.inMatch(p)) return;
        if (!config.getBoolean("match.block-commands", true)) return;
        String message = event.getMessage();
        if (message == null || message.length() < 2) return;
        String body = message.substring(1).trim();
        if (body.isEmpty()) return;
        String first = body.split(" ")[0].toLowerCase(Locale.ROOT);
        // 兼容带命名空间的写法（minecraft:tp、accduel:duel）
        int colon = first.indexOf(':');
        String simple = colon >= 0 ? first.substring(colon + 1) : first;
        List<String> allowed = config.getConfig().getStringList("match.allowed-commands");
        for (String s : allowed) {
            if (s == null) continue;
            String norm = s.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) continue;
            if (norm.equals(first) || norm.equals(simple)) return;
        }
        event.setCancelled(true);
        config.send(p, "commands-blocked");
    }

    // ---------------- GUI ----------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        GuiManager.GuiSession session = gui.session(p.getUniqueId());
        if (session == null) return;
        if (!session.matches(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw >= 0 && raw < session.inventory.getSize()) {
            session.handler.accept(event);
        }
    }

    /**
     * 拖拽同样必须拦截：只监听点击的话，玩家可以把物品拖进界面格子，
     * 造成界面污染甚至物品复制。
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        GuiManager.GuiSession session = gui.session(p.getUniqueId());
        if (session == null) return;
        if (!session.matches(event.getView().getTopInventory())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        gui.onClose(p, event.getInventory());
    }

    // ---------------- 世界加载 ----------------

    /** 竞技场所在世界晚于本插件加载时，世界就绪后重新标记竞技场可用。 */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.getArenaManager().onWorldLoaded(event.getWorld());
    }
}
