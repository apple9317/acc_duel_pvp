package com.apple9317.accduel.duel;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.arena.Arena;
import com.apple9317.accduel.arena.ArenaManager;
import com.apple9317.accduel.config.ConfigManager;
import com.apple9317.accduel.dialog.DialogManager;
import com.apple9317.accduel.geyser.GeyserManager;
import com.apple9317.accduel.version.ViaManager;
import com.apple9317.accduel.gui.GuiManager;
import com.apple9317.accduel.kit.Kit;
import com.apple9317.accduel.kit.KitManager;
import com.apple9317.accduel.setting.PlayerSettings;
import com.apple9317.accduel.setting.SettingsManager;
import com.apple9317.accduel.stats.StatsManager;
import com.apple9317.accduel.util.Txt;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 决斗管理中枢：类型选择界面（Java 箱子 / 基岩表单）、请求、匹配队列、比赛生命周期、冷却、统计接入。
 *
 * <p>另外负责赛前状态快照的落盘与恢复登记：玩家比赛中掉线或关服时，
 * 快照会写入 {@code data/recovery.yml}，重连后自动还原背包、位置与属性。</p>
 */
public class DuelManager {

    private final ACCDuelPlugin plugin;
    private final ConfigManager config;
    private final ArenaManager arenas;
    private final KitManager kits;
    private final StatsManager stats;
    private final SettingsManager settings;
    private final GeyserManager geyser;
    private final GuiManager gui;
    private final DialogManager dialogs;
    /** 服务端是否安装 ViaVersion（用于判断客户端真实版本）。 */
    private final boolean viaPresent;

    /** 目标玩家 -> (发起者 -> 请求)。同一目标可同时收到多人的邀请，不再互相覆盖。 */
    private final Map<UUID, Map<UUID, DuelRequest>> requests = new LinkedHashMap<>();
    /** 排队玩家 */
    private final Map<UUID, QueueEntry> queue = new LinkedHashMap<>();
    private final List<Match> matches = new ArrayList<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** 比赛已结束但玩家仍在死亡界面/已离线，等待恢复的快照。 */
    private final Map<UUID, MatchSnapshot> pendingRestores = new HashMap<>();
    /** 最近一场比赛的积分变化，仅用于赛后消息，读取后即清除。 */
    private final Map<UUID, int[]> lastRatingDelta = new HashMap<>();

    private int nextId = 1;
    private BukkitTask queueTask;
    private BukkitTask expireTask;
    private BukkitTask pruneTask;
    private File recoveryFile;

    public DuelManager(ACCDuelPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.arenas = plugin.getArenaManager();
        this.kits = plugin.getKitManager();
        this.stats = plugin.getStatsManager();
        this.settings = plugin.getSettingsManager();
        this.geyser = plugin.getGeyserManager();
        this.gui = plugin.getGuiManager();
        this.dialogs = plugin.getDialogManager();
        this.viaPresent = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
    }

    public void start() {
        long interval = Math.max(1, config.getInt("queue.check-interval", 2)) * 20L;
        queueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::matchQueue, interval, interval);
        expireTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expireRequests, 40L, 20L);
        // 定期清理离线玩家遗留的队列项与过期冷却
        pruneTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pruneStale, 600L, 600L);
        loadRecovery();
        recoverOnlinePlayers();
    }

    public void shutdown() {
        cancel(queueTask);
        cancel(expireTask);
        cancel(pruneTask);
        queueTask = null;
        expireTask = null;
        pruneTask = null;
        // 关服时世界可能已卸载，先落盘快照再取消比赛，重连后恢复
        for (Match match : new ArrayList<>(matches)) {
            for (UUID u : new UUID[]{match.fighter1(), match.fighter2()}) {
                MatchSnapshot s = match.snapshot(u);
                if (s != null) persistSnapshot(u, s);
            }
            match.abort(null);
        }
        matches.clear();
        queue.clear();
        requests.clear();
        saveRecovery();
    }

    private static void cancel(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    public int nextMatchId() {
        return nextId++;
    }

    // ---------------- 冷却 ----------------

    public boolean onCooldown(Player p) {
        if (p == null) return false;
        Long until = cooldowns.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public long cooldownRemaining(Player p) {
        if (p == null) return 0;
        Long until = cooldowns.get(p.getUniqueId());
        if (until == null) return 0;
        return Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    public void applyCooldown(Player p) {
        int seconds = config.getInt("duel-cooldown", 5);
        if (seconds > 0 && p != null) {
            cooldowns.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        }
    }

    private void pruneStale() {
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(e -> e.getValue() <= now);
        queue.entrySet().removeIf(e -> Bukkit.getPlayer(e.getKey()) == null);
        lastRatingDelta.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    // ---------------- 类型界面（/duel 与 /duelplayer） ----------------

    /** /duel 主菜单：选择类型加入匹配。 */
    public void openMainMenu(Player p) {
        openTypeMenu(p, config.raw("menu-main-title"), type -> {
            Player current = Bukkit.getPlayer(p.getUniqueId());
            if (current != null) joinQueue(current, type);
        });
    }

    /** /duelplayer <玩家>：选择类型后向对方发起该类型的决斗。 */
    public void openInviteMenu(Player p, Player target) {
        openTypeMenu(p, config.raw("menu-invite-title", Map.of("player", target.getName())), type -> {
            Player current = Bukkit.getPlayer(p.getUniqueId());
            Player currentTarget = Bukkit.getPlayer(target.getUniqueId());
            if (current != null && currentTarget != null) requestDuel(current, currentTarget, type);
        });
    }

    /** 当前玩家可选的类型（已过滤停用项与无权限项）。 */
    private List<Kit> selectableTypes(Player player) {
        List<Kit> out = new ArrayList<>();
        for (Kit kit : kits.getTypes()) {
            if (!kit.enabled) continue;
            if (!kits.hasKitPermission(player, kit)) continue;
            out.add(kit);
        }
        return out;
    }

    /** 该玩家客户端是否支持 Dialog：无 ViaVersion 时客户端版本等同服务端（已支持）；有则按真实版本判断。 */
    private boolean clientSupportsDialog(Player player) {
        if (!viaPresent) return true;
        return ViaManager.clientAtLeast1216(player);
    }

    /**
     * 类型选择界面：Java 版打开箱子界面；基岩版优先发送原生表单（失败自动回退箱子界面，
     * Geyser 会把箱子界面翻译成基岩 UI）。
     */
    private void openTypeMenu(Player player, String titleRaw, Consumer<String> onPick) {
        List<Kit> types = selectableTypes(player);
        if (types.isEmpty()) {
            config.send(player, kits.getTypes().isEmpty() ? "no-types" : "no-kit-permission");
            return;
        }
        if (geyser.isBedrock(player)) {
            List<String> ids = new ArrayList<>();
            for (Kit kit : types) ids.add(kit.id);
            // 表单回调可能延迟到玩家已切换状态之后，需重新校验
            if (geyser.sendTypeForm(player, titleRaw, ids, picked -> {
                Player current = Bukkit.getPlayer(player.getUniqueId());
                if (current != null) onPick.accept(picked);
            })) {
                return;
            }
        }
        // Java 玩家：服务端支持 Dialog 且客户端为 1.21.6+ 时，用原生屏幕对话框
        if (!geyser.isBedrock(player) && dialogs.isSupported() && clientSupportsDialog(player)
                && settings.get(player).modernUi) {
            List<Component> labels = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (Kit kit : types) {
                labels.add(Txt.mm(kit.displayNameRaw()));
                ids.add(kit.id);
            }
            if (dialogs.showButtonMenu(player, Txt.mm(titleRaw), labels, ids, picked -> {
                Player current = Bukkit.getPlayer(player.getUniqueId());
                if (current != null) onPick.accept(picked);
            })) {
                return;
            }
        }
        int rows = Math.min(6, Math.max(1, (types.size() + 8) / 9));
        int capacity = rows * 9;
        if (types.size() > capacity) {
            plugin.getLogger().warning("竞技类型数量（" + types.size() + "）超过界面容量（" + capacity
                    + "），仅显示前 " + capacity + " 个");
        }
        List<Kit> shown = types.size() > capacity ? new ArrayList<>(types.subList(0, capacity)) : types;
        gui.open(player, Txt.mm(titleRaw), rows, event -> {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot >= 0 && slot < shown.size()) {
                gui.closeAll(player);
                onPick.accept(shown.get(slot).id);
            }
        });
        GuiManager.GuiSession session = gui.session(player.getUniqueId());
        if (session == null) return;
        var inv = session.inventory;
        inv.clear();
        for (int i = 0; i < shown.size(); i++) {
            inv.setItem(i, typeIcon(shown.get(i)));
        }
        GuiManager.decorate(inv, rows, Txt.mm("<dark_gray> </dark_gray>"));
    }

    private ItemStack typeIcon(Kit kit) {
        Material material = Material.matchMaterial(kit.icon == null ? "" : kit.icon.toUpperCase(Locale.ROOT));
        if (material == null) material = Material.DIAMOND_SWORD;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Txt.parse(kit.displayNameRaw()));
            List<Component> lore = new ArrayList<>(Txt.parseAll(kit.description));
            lore.add(Txt.mm("<green>点击选择</green>"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ---------------- 请求 ----------------

    /**
     * 发起决斗请求。
     *
     * <p>双方状态都会校验：任何一方正在比赛、观战、排队或冷却中都拒绝，
     * 避免同一玩家同时进入两场对局。</p>
     */
    public boolean requestDuel(Player requester, Player target, String type) {
        if (requester == null || target == null) return false;
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            config.send(requester, "cannot-self");
            return false;
        }
        Kit kit = kits.getType(type);
        if (kit == null || !kit.enabled) {
            config.send(requester, "type-not-found", Map.of("type", type == null ? "?" : type));
            return false;
        }
        if (!kits.hasKitPermission(requester, kit)) {
            config.send(requester, "no-kit-permission-type", Map.of("type", kit.displayNameRaw()));
            return false;
        }
        if (!kits.hasKitPermission(target, kit)) {
            config.send(requester, "target-no-kit-permission", Map.of(
                    "player", target.getName(), "type", kit.displayNameRaw()));
            return false;
        }
        if (!isFree(requester)) {
            config.send(requester, onCooldown(requester) ? "duel-cooldown" : "you-in-match",
                    Map.of("time", String.valueOf(cooldownRemaining(requester))));
            return false;
        }
        if (!isFree(target)) {
            config.send(requester, "target-in-match", Map.of("player", target.getName()));
            return false;
        }
        if (!settings.acceptsRequests(target.getUniqueId())) {
            config.send(requester, "target-requests-off", Map.of("player", target.getName()));
            return false;
        }
        if (onCooldown(requester)) {
            config.send(requester, "duel-cooldown", Map.of("time", String.valueOf(cooldownRemaining(requester))));
            return false;
        }
        int timeout = Math.max(5, config.getInt("request-timeout", 60));
        requests.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>())
                .put(requester.getUniqueId(),
                        new DuelRequest(requester.getUniqueId(), target.getUniqueId(), kit.id, timeout));

        String typeName = typeName(kit.id);
        config.send(requester, "request-sent", Map.of("player", target.getName(), "type", typeName));
        config.send(target, "request-received", Map.of("player", requester.getName(), "type", typeName));
        // Java：request-received 消息自带可点击 [接受]/[拒绝]，不再弹箱子；基岩：发原生请求表单
        sendBedrockRequestForm(target, requester, typeName);
        return true;
    }

    /** 玩家是否空闲（在线、未在比赛、未观战、未排队）。 */
    private boolean isFree(Player p) {
        if (p == null || !p.isOnline()) return false;
        UUID uuid = p.getUniqueId();
        return matchOf(uuid) == null && !involves(uuid) && !queue.containsKey(uuid);
    }

    /**
     * 玩家是否可被撮合进比赛：在线、没在比赛、没被请求纠缠。
     * 不检查队列（排队玩家本身就在队列中），供 matchOne 使用。
     */
    private boolean available(Player p) {
        if (p == null || !p.isOnline()) return false;
        UUID uuid = p.getUniqueId();
        return matchOf(uuid) == null && !involves(uuid);
    }

    /** 取出并移除指定请求。 */
    private DuelRequest takeRequest(Player target, Player requester) {
        if (target == null || requester == null) return null;
        Map<UUID, DuelRequest> incoming = requests.get(target.getUniqueId());
        if (incoming == null) return null;
        DuelRequest removed = incoming.remove(requester.getUniqueId());
        if (incoming.isEmpty()) requests.remove(target.getUniqueId());
        return removed;
    }

    public boolean acceptRequest(Player target, Player requester) {
        if (target == null || requester == null) return false;
        Map<UUID, DuelRequest> incoming = requests.get(target.getUniqueId());
        DuelRequest request = incoming == null ? null : incoming.get(requester.getUniqueId());
        if (request == null) {
            config.send(target, "no-request", Map.of("player", requester.getName()));
            return false;
        }
        if (request.expired()) {
            takeRequest(target, requester);
            config.send(target, "request-timeout", Map.of("player", requester.getName()));
            return false;
        }
        // 请求发出后双方状态可能已变化（比如已被别人拉进比赛），这里重新校验
        if (!isFree(target)) {
            takeRequest(target, requester);
            config.send(target, "you-in-match");
            return false;
        }
        if (!isFree(requester)) {
            takeRequest(target, requester);
            config.send(target, "target-in-match", Map.of("player", requester.getName()));
            return false;
        }
        if (onCooldown(target)) {
            config.send(target, "duel-cooldown", Map.of("time", String.valueOf(cooldownRemaining(target))));
            return false;
        }
        takeRequest(target, requester);
        gui.closeAll(target);
        config.send(target, "request-accepted");
        config.send(requester, "request-accepted");
        startMatch(requester, target, request.type);
        return true;
    }

    public boolean denyRequest(Player target, Player requester) {
        DuelRequest request = takeRequest(target, requester);
        if (request == null) {
            config.send(target, "no-request", Map.of("player", requester.getName()));
            return false;
        }
        config.send(target, "request-denied", Map.of("player", requester.getName()));
        Player from = Bukkit.getPlayer(request.requester);
        if (from != null) {
            config.send(from, "request-was-denied", Map.of("player", target.getName()));
        }
        return true;
    }

    /** 列出该玩家收到的所有待处理邀请发起者名字。 */
    public List<String> pendingRequesters(Player target) {
        List<String> out = new ArrayList<>();
        if (target == null) return out;
        Map<UUID, DuelRequest> incoming = requests.get(target.getUniqueId());
        if (incoming == null) return out;
        for (DuelRequest r : incoming.values()) {
            if (r.expired()) continue;
            out.add(nameOf(r.requester));
        }
        return out;
    }

    private void expireRequests() {
        Iterator<Map.Entry<UUID, Map<UUID, DuelRequest>>> outer = requests.entrySet().iterator();
        while (outer.hasNext()) {
            Map.Entry<UUID, Map<UUID, DuelRequest>> entry = outer.next();
            Map<UUID, DuelRequest> incoming = entry.getValue();
            Iterator<Map.Entry<UUID, DuelRequest>> inner = incoming.entrySet().iterator();
            while (inner.hasNext()) {
                DuelRequest request = inner.next().getValue();
                if (!request.expired()) continue;
                inner.remove();
                Player requester = Bukkit.getPlayer(request.requester);
                if (requester != null) {
                    config.send(requester, "request-timeout", Map.of("player", nameOf(request.target)));
                }
            }
            if (incoming.isEmpty()) outer.remove();
        }
    }

    /** 类型显示名（无则返回原始 id）。 */
    public String typeName(String type) {
        Kit kit = kits.getType(type);
        return kit == null ? (type == null ? "?" : type) : kit.displayNameRaw();
    }

    // ---------------- 匹配队列 ----------------

    public static class QueueEntry {
        public final UUID uuid;
        public final String type;
        public final long since;

        public QueueEntry(UUID uuid, String type) {
            this.uuid = uuid;
            this.type = type;
            this.since = System.currentTimeMillis();
        }
    }

    public boolean joinQueue(Player p, String type) {
        if (p == null) return false;
        Kit kit = kits.getType(type);
        if (kit == null || !kit.enabled) {
            config.send(p, "type-not-found", Map.of("type", type == null ? "?" : type));
            return false;
        }
        if (!kits.hasKitPermission(p, kit)) {
            config.send(p, "no-kit-permission-type", Map.of("type", kit.displayNameRaw()));
            return false;
        }
        if (matchOf(p.getUniqueId()) != null || involves(p.getUniqueId())) {
            config.send(p, "you-in-match");
            return false;
        }
        if (queue.containsKey(p.getUniqueId())) {
            config.send(p, "you-in-queue");
            return false;
        }
        if (!config.getBoolean("queue.enabled", true)) {
            config.send(p, "queue-disabled");
            return false;
        }
        if (onCooldown(p)) {
            config.send(p, "duel-cooldown", Map.of("time", String.valueOf(cooldownRemaining(p))));
            return false;
        }
        queue.put(p.getUniqueId(), new QueueEntry(p.getUniqueId(), kit.id));
        config.send(p, "queue-join", Map.of("type", typeName(kit.id)));
        // 立即尝试撮合一次，两人先后入队时不必干等轮询
        matchQueue();
        return true;
    }

    public boolean leaveQueue(Player p) {
        if (p != null && queue.remove(p.getUniqueId()) != null) {
            config.send(p, "queue-leave");
            return true;
        }
        return false;
    }

    public boolean inQueue(Player p) {
        return p != null && queue.containsKey(p.getUniqueId());
    }

    public int queueSize() {
        return queue.size();
    }

    /** 单次轮询内尽量多撮合几对，避免人多时每 2 秒只能开一场。 */
    private void matchQueue() {
        if (queue.size() < 2) return;
        int budget = Math.max(1, config.getInt("queue.max-matches-per-tick", 4));
        while (budget-- > 0 && queue.size() >= 2) {
            if (!matchOne()) return;
        }
    }

    private boolean matchOne() {
        List<QueueEntry> list = new ArrayList<>(queue.values());
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                QueueEntry a = list.get(i);
                QueueEntry b = list.get(j);
                if (!a.type.equals(b.type)) continue;
                Player pa = Bukkit.getPlayer(a.uuid);
                Player pb = Bukkit.getPlayer(b.uuid);
                if (pa == null || pb == null) {
                    // 离线玩家直接剔除，不影响其他配对
                    if (pa == null) queue.remove(a.uuid);
                    if (pb == null) queue.remove(b.uuid);
                    continue;
                }
                // 撮合用 available（不检查队列，两人本就排队中）；isFree 含队列判断会误踢
                if (!available(pa) || !available(pb)) {
                    queue.remove(a.uuid);
                    queue.remove(b.uuid);
                    continue;
                }
                queue.remove(a.uuid);
                queue.remove(b.uuid);
                config.send(pa, "queue-found", Map.of("player", pb.getName(), "type", typeName(a.type)));
                config.send(pb, "queue-found", Map.of("player", pa.getName(), "type", typeName(a.type)));
                startMatch(pa, pb, a.type);
                return true;
            }
        }
        return false;
    }

    // ---------------- 比赛 ----------------

    /**
     * 开赛。失败时给出可操作的原因提示，且不消耗冷却。
     */
    public void startMatch(Player a, Player b, String type) {
        if (a == null || b == null || !a.isOnline() || !b.isOnline()) return;
        Kit kit = kits.getType(type);
        if (kit == null || !kit.enabled) {
            String shown = type == null ? "?" : type;
            config.send(a, "type-not-found", Map.of("type", shown));
            config.send(b, "type-not-found", Map.of("type", shown));
            return;
        }
        if (!isFree(a) || !isFree(b)) {
            config.send(a, "you-in-match");
            config.send(b, "you-in-match");
            return;
        }
        ArenaManager.ArenaSearch search = arenas.findAvailable(kit.id, this::arenaOccupied);
        if (!search.found()) {
            String key = !search.typeExists ? "no-arena-for-type"
                    : search.worldMissing ? "arena-world-missing"
                    : search.allBusy ? "arena-all-busy" : "no-arena-for-type";
            Map<String, String> ph = Map.of("type", typeName(kit.id));
            config.send(a, key, ph);
            config.send(b, key, ph);
            return;
        }
        int rounds = config.getInt("match.rounds", 3);
        if (rounds < 1) rounds = 1;
        // 偶数回合数会让「先赢一半以上」永远无法达成，向上取整为奇数
        if (rounds % 2 == 0) rounds++;
        Match match = new Match(plugin, this, search.arena, kit, a, b, rounds);
        matches.add(match);
        applyCooldown(a);
        applyCooldown(b);
        match.start();
    }

    private boolean arenaOccupied(Arena arena) {
        for (Match match : matches) {
            if (!match.ended() && match.arena() != null && match.arena().id.equals(arena.id)) return true;
        }
        return false;
    }

    public Match matchOf(Player p) {
        return p == null ? null : matchOf(p.getUniqueId());
    }

    /** 查找玩家所在的<b>进行中</b>比赛（已结束的比赛不返回）。 */
    public Match matchOf(UUID uuid) {
        if (uuid == null) return null;
        for (Match match : matches) {
            if (!match.ended() && match.isFighter(uuid)) return match;
        }
        return null;
    }

    public boolean inMatch(Player p) {
        return p != null && matchOf(p.getUniqueId()) != null;
    }

    /** 选手或观战者。 */
    public boolean involves(UUID uuid) {
        if (uuid == null) return false;
        for (Match match : matches) {
            if (!match.ended() && match.involves(uuid)) return true;
        }
        return false;
    }

    public void handleForfeit(Player p) {
        Match match = matchOf(p);
        if (match != null) {
            match.handleForfeit(p);
        }
    }

    public void onMatchEnd(Match match) {
        matches.remove(match);
    }

    /** 玩家退出时的清理：请求、队列；比赛本身由监听器处理弃权。 */
    public void onQuitCleanup(Player p) {
        if (p == null) return;
        UUID uuid = p.getUniqueId();
        for (Map<UUID, DuelRequest> incoming : requests.values()) {
            incoming.keySet().removeIf(requester -> requester.equals(uuid));
        }
        requests.entrySet().removeIf(e -> e.getKey().equals(uuid) || e.getValue().isEmpty());
        queue.remove(uuid);
    }

    // ---------------- 观战 ----------------

    public boolean spectate(Player watcher, Player target) {
        if (watcher == null || target == null) return false;
        if (!config.getBoolean("spectate.enabled", true)) {
            config.send(watcher, "spectate-disabled");
            return false;
        }
        if (!watcher.hasPermission("accduel.spectate")) {
            config.send(watcher, "no-permission");
            return false;
        }
        if (matchOf(watcher.getUniqueId()) != null || involves(watcher.getUniqueId())) {
            config.send(watcher, "you-in-match");
            return false;
        }
        Match match = matchOf(target);
        if (match == null) {
            config.send(watcher, "spectate-none", Map.of("player", target.getName()));
            return false;
        }
        if (watcher.getUniqueId().equals(target.getUniqueId())) {
            config.send(watcher, "cannot-self");
            return false;
        }
        int max = config.getInt("spectate.max-per-match", 0);
        if (max > 0 && match.spectatorCount() >= max) {
            config.send(watcher, "spectate-full", Map.of("max", String.valueOf(max)));
            return false;
        }
        if (match.addSpectator(watcher)) {
            config.send(watcher, "spectate-join", Map.of("player", target.getName()));
            if (config.getBoolean("spectate.announce-join", true)) {
                config.broadcast("spectate-announce", Map.of("player", watcher.getName()));
            }
            return true;
        }
        return false;
    }

    /** 主动离开观战并恢复赛前状态。 */
    public boolean leaveSpectate(Player watcher) {
        if (watcher == null) return false;
        UUID uuid = watcher.getUniqueId();
        for (Match match : new ArrayList<>(matches)) {
            if (match.ended() || !match.isSpectator(uuid)) continue;
            match.removeSpectator(uuid, true);
            config.send(watcher, "spectate-left");
            return true;
        }
        return false;
    }

    public void removeSpectator(UUID uuid) {
        if (uuid == null) return;
        for (Match match : new ArrayList<>(matches)) {
            if (!match.ended() && match.isSpectator(uuid)) {
                match.removeSpectator(uuid, true);
            }
        }
    }

    // ---------------- 统计 ----------------

    public void recordMatch(UUID winnerU, UUID loserU, int winnerKills, int loserKills,
                            int winnerRounds, int loserRounds, boolean draw) {
        int beforeW = stats.get(winnerU).rating;
        int beforeL = stats.get(loserU).rating;
        stats.recordMatch(winnerU, loserU, winnerKills, loserKills, winnerRounds, loserRounds, draw);
        int afterW = stats.get(winnerU).rating;
        int afterL = stats.get(loserU).rating;
        lastRatingDelta.put(winnerU, new int[]{afterW - beforeW, beforeW, afterW});
        lastRatingDelta.put(loserU, new int[]{afterL - beforeL, beforeL, afterL});
    }

    /** 读取后清除，避免玩家在之后的比赛里看到上一次的积分变化。 */
    public Map<String, String> ratingChangePlaceholders(UUID uuid) {
        int[] d = uuid == null ? null : lastRatingDelta.remove(uuid);
        if (d == null) return Map.of("delta", "0", "from", "?", "to", "?");
        int delta = d[0];
        return Map.of("delta", (delta >= 0 ? "+" : "") + delta, "from", String.valueOf(d[1]), "to", String.valueOf(d[2]));
    }

    public List<Match> activeMatches() {
        List<Match> out = new ArrayList<>();
        for (Match match : matches) {
            if (!match.ended()) out.add(match);
        }
        return out;
    }

    public String nameOf(UUID uuid) {
        if (uuid == null) return "?";
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        String saved = stats.nameOf(uuid);
        return saved != null ? saved : uuid.toString().substring(0, 8);
    }

    // ---------------- 快照落盘与恢复 ----------------

    /** 比赛结束时玩家仍在死亡界面，登记待恢复快照。 */
    public void registerPendingRestore(UUID uuid, MatchSnapshot snapshot) {
        if (uuid == null || snapshot == null) return;
        pendingRestores.put(uuid, snapshot);
        persistSnapshot(uuid, snapshot);
    }

    /** 快照落盘，掉线/关服后可恢复。 */
    public void persistSnapshot(UUID uuid, MatchSnapshot snapshot) {
        if (uuid == null || snapshot == null || !recoveryEnabled()) return;
        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists() && !dataFolder.mkdirs()) return;
        recoveryFile = new File(dataFolder, "recovery.yml");
        YamlConfiguration yaml = recoveryFile.exists()
                ? YamlConfiguration.loadConfiguration(recoveryFile) : new YamlConfiguration();
        yaml.set("pending." + uuid, snapshot.toMap());
        try {
            yaml.save(recoveryFile);
        } catch (IOException e) {
            plugin.getLogger().warning("写入掉线恢复数据失败: " + e.getMessage());
        }
    }

    /** 玩家已成功恢复，清理落盘与内存记录。 */
    public void discardSnapshot(UUID uuid) {
        if (uuid == null) return;
        pendingRestores.remove(uuid);
        if (!recoveryEnabled()) return;
        if (recoveryFile == null) recoveryFile = new File(plugin.getDataFolder(), "data/recovery.yml");
        if (!recoveryFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(recoveryFile);
        if (!yaml.contains("pending." + uuid)) return;
        yaml.set("pending." + uuid, null);
        try {
            yaml.save(recoveryFile);
        } catch (IOException e) {
            plugin.getLogger().warning("清理掉线恢复数据失败: " + e.getMessage());
        }
    }

    private boolean recoveryEnabled() {
        return config.getBoolean("match.recover-on-reconnect", true);
    }

    private void loadRecovery() {
        if (!recoveryEnabled()) return;
        recoveryFile = new File(plugin.getDataFolder(), "data/recovery.yml");
        if (!recoveryFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(recoveryFile);
        var section = yaml.getConfigurationSection("pending");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            MatchSnapshot snapshot = MatchSnapshot.fromMap(section.getConfigurationSection(key).getValues(false));
            if (snapshot == null) continue;
            snapshot.pendingRestore = true;
            pendingRestores.put(uuid, snapshot);
        }
        if (!pendingRestores.isEmpty()) {
            plugin.getLogger().info("检测到 " + pendingRestores.size() + " 份未完成的赛前状态，将在玩家重连时恢复");
        }
    }

    /** 把内存中所有待恢复快照写回磁盘（关服兜底）。 */
    private void saveRecovery() {
        if (!recoveryEnabled() || recoveryFile == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, MatchSnapshot> e : pendingRestores.entrySet()) {
            yaml.set("pending." + e.getKey(), e.getValue().toMap());
        }
        try {
            if (recoveryFile.getParentFile() != null && !recoveryFile.getParentFile().exists()) {
                recoveryFile.getParentFile().mkdirs();
            }
            yaml.save(recoveryFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存掉线恢复数据失败: " + ex.getMessage());
        }
    }

    /** 启动时把已在线玩家（例如插件热重载）的遗留快照恢复掉。 */
    private void recoverOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            tryRestore(p);
        }
    }

    /** 玩家加入/重生时尝试恢复上次未完成的赛前状态。 */
    public boolean tryRestore(Player p) {
        if (p == null) return false;
        UUID uuid = p.getUniqueId();
        MatchSnapshot snapshot = pendingRestores.get(uuid);
        if (snapshot == null) return false;
        if (matchOf(uuid) != null) return false;
        // 仍在死亡界面：等重生事件再恢复，别把快照丢掉
        if (p.isDead()) return false;
        pendingRestores.remove(uuid);
        snapshot.restore(p);
        discardSnapshot(uuid);
        config.send(p, "state-restored");
        return true;
    }

    /** 重生完成后调用：若该玩家有待恢复快照，按快照位置重生并恢复。 */
    public org.bukkit.Location respawnLocationFor(Player p) {
        if (p == null) return null;
        MatchSnapshot snapshot = pendingRestores.get(p.getUniqueId());
        if (snapshot == null || snapshot.location == null) return null;
        if (snapshot.location.getWorld() == null) return null;
        return snapshot.location;
    }

    /** 是否存在待恢复快照。 */
    public boolean hasPendingRestore(UUID uuid) {
        return uuid != null && pendingRestores.containsKey(uuid);
    }

    // ---------------- 请求界面 ----------------

    /** 基岩版玩家：发送原生决斗请求表单（接受/拒绝）；Java 玩家不做（聊天消息按钮即可）。 */
    private void sendBedrockRequestForm(Player target, Player requester, String typeName) {
        if (!geyser.isBedrock(target)) return;
        Player to = target;
        Player from = requester;
        String plainType = Txt.plain(Txt.parse(typeName));
        boolean sent = geyser.sendRequestForm(to, "决斗请求",
                from.getName() + " 邀请你决斗（类型：" + plainType + "）",
                "接受", "拒绝", accepted -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (accepted) acceptRequest(to, from);
                    else denyRequest(to, from);
                }));
        if (!sent) {
            gui.closeAll(to);
        }
    }

    /** 打开请求界面（目标玩家视角）。 */
    private void openRequestGui(Player target, Player requester, Kit kit) {
        UUID targetId = target.getUniqueId();
        UUID requesterId = requester.getUniqueId();
        String requesterName = requester.getName();
        gui.open(target, Txt.mm("<gold>决斗请求</gold>"), 3, event -> {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot != 11 && slot != 15) return;
            gui.closeAll(target);
            // 回调可能延迟触发，必须确认该请求仍然存在且未过期
            Map<UUID, DuelRequest> incoming = requests.get(targetId);
            DuelRequest current = incoming == null ? null : incoming.get(requesterId);
            if (current == null || current.expired()) {
                Player now = Bukkit.getPlayer(targetId);
                if (now != null) config.send(now, "no-request", Map.of("player", requesterName));
                return;
            }
            Player from = Bukkit.getPlayer(requesterId);
            Player to = Bukkit.getPlayer(targetId);
            if (from == null || to == null) {
                if (to != null) config.send(to, "player-not-found", Map.of("player", requesterName));
                return;
            }
            if (slot == 11) {
                acceptRequest(to, from);
            } else {
                denyRequest(to, from);
            }
        });
        GuiManager.GuiSession session = gui.session(target.getUniqueId());
        if (session == null) return;
        var inv = session.inventory;

        var accept = new ItemStack(Material.GREEN_WOOL);
        ItemMeta acceptMeta = accept.getItemMeta();
        if (acceptMeta != null) {
            acceptMeta.displayName(Txt.mm("<green>接受决斗</green>"));
            List<Component> lore = new ArrayList<>();
            lore.add(Txt.mm("<gray>对手：</gray>").append(Component.text(requesterName)));
            lore.add(Txt.mm("<gray>类型：</gray>").append(Txt.parse(kit.displayNameRaw())));
            acceptMeta.lore(lore);
            accept.setItemMeta(acceptMeta);
        }

        var deny = new ItemStack(Material.RED_WOOL);
        ItemMeta denyMeta = deny.getItemMeta();
        if (denyMeta != null) {
            denyMeta.displayName(Txt.mm("<red>拒绝</red>"));
            deny.setItemMeta(denyMeta);
        }

        inv.setItem(11, accept);
        inv.setItem(15, deny);
        GuiManager.decorate(inv, 3, Txt.mm("<dark_gray> </dark_gray>"));
    }
}
