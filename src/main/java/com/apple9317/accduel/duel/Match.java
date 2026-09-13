package com.apple9317.accduel.duel;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.arena.Arena;
import com.apple9317.accduel.config.ConfigManager;
import com.apple9317.accduel.kit.Kit;
import com.apple9317.accduel.kit.KitManager;
import com.apple9317.accduel.util.Compat;
import com.apple9317.accduel.util.Txt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单场比赛引擎：倒计时 -> 回合战 -> 判胜 -> 恢复玩家状态。
 *
 * <p>支持多回合制（best-of-N，默认 3 局 2 胜）、观战、虚空判负、弃权判负、回合限时判定。</p>
 *
 * <p>状态恢复是这套流程里最容易出错的一环，因此明确区分三类人：
 * <ul>
 *   <li>选手（u1/u2）：始终通过 {@link #restoreFighter} 恢复，即使比赛结束时正处于死亡界面；</li>
 *   <li>本回合已阵亡的选手（{@link #eliminated}）：只是临时切到旁观视角等待下一回合或最终结果，
 *       <b>不进入 spectators 集合</b>，否则会被当成外部观战者而跳过状态恢复；</li>
 *   <li>外部观战者（{@link #spectators}）：通过 {@link #restoreSpectator} 恢复。</li>
 * </ul>
 * </p>
 */
public class Match {

    public enum State { COUNTDOWN, FIGHTING, ROUND_OVER, ENDED }

    private final ACCDuelPlugin plugin;
    private final ConfigManager config;
    private final DuelManager manager;
    private final int id;
    private final Arena arena;
    private final Kit kit;
    private final UUID u1;
    private final UUID u2;
    private final String name1;
    private final String name2;
    private final int totalRounds;

    private State state = State.COUNTDOWN;
    private int score1;
    private int score2;
    private int round = 1;
    private long startedAt;
    private int roundSecondsLeft;

    private final Map<UUID, MatchSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, Location> fightPos = new HashMap<>();
    private final Map<UUID, Integer> matchKills = new HashMap<>();
    /** 外部观战者（不含选手）。 */
    private final Set<UUID> spectators = new HashSet<>();
    /** 本回合已阵亡、正在等待下一回合或最终结果的选手。 */
    private final Set<UUID> eliminated = new HashSet<>();

    private BukkitTask countdownTask;
    private BukkitTask roundTask;
    private BukkitTask voidTask;
    private BukkitTask roundTimerTask;

    public Match(ACCDuelPlugin plugin, DuelManager manager, Arena arena, Kit kit, Player p1, Player p2, int totalRounds) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = plugin.getConfigManager();
        this.id = manager.nextMatchId();
        this.arena = arena;
        this.kit = kit;
        this.u1 = p1.getUniqueId();
        this.u2 = p2.getUniqueId();
        this.name1 = p1.getName();
        this.name2 = p2.getName();
        this.totalRounds = Math.max(1, totalRounds);
        this.matchKills.put(u1, 0);
        this.matchKills.put(u2, 0);
    }

    // ---------------- 对外查询 ----------------

    public int id() {
        return id;
    }

    public Arena arena() {
        return arena;
    }

    public Kit kit() {
        return kit;
    }

    public State state() {
        return state;
    }

    public boolean ended() {
        return state == State.ENDED;
    }

    public UUID fighter1() {
        return u1;
    }

    public UUID fighter2() {
        return u2;
    }

    public int score1() {
        return score1;
    }

    public int score2() {
        return score2;
    }

    public int round() {
        return round;
    }

    public int totalRounds() {
        return totalRounds;
    }

    public Player player(UUID uuid) {
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    /** 选手、外部观战者或已阵亡待恢复的选手。 */
    public boolean involves(UUID uuid) {
        return uuid != null && (isFighter(uuid) || spectators.contains(uuid) || eliminated.contains(uuid));
    }

    public boolean isFighter(UUID uuid) {
        return uuid != null && (uuid.equals(u1) || uuid.equals(u2));
    }

    /** 是否为外部观战者（选手即使处于旁观视角也不算）。 */
    public boolean isSpectator(UUID uuid) {
        return uuid != null && spectators.contains(uuid);
    }

    /** 当前外部观战人数。 */
    public int spectatorCount() {
        return spectators.size();
    }

    public MatchSnapshot snapshot(UUID uuid) {
        return uuid == null ? null : snapshots.get(uuid);
    }

    public String kitName() {
        return kit == null ? "?" : kit.displayNameRaw();
    }

    public long startedAt() {
        return startedAt;
    }

    public String scoreText() {
        return score1 + ":" + score2;
    }

    /** 选手名（不依赖在线状态，离线也能正确显示）。 */
    public String nameOf(UUID uuid) {
        if (u1.equals(uuid)) return name1;
        if (u2.equals(uuid)) return name2;
        return manager.nameOf(uuid);
    }

    // ---------------- 流程 ----------------

    public void start() {
        startedAt = System.currentTimeMillis();
        Player p1 = player(u1);
        Player p2 = player(u2);
        if (p1 == null || p2 == null) {
            abort("match-aborted-offline");
            return;
        }

        MatchSnapshot s1 = MatchSnapshot.capture(p1);
        MatchSnapshot s2 = MatchSnapshot.capture(p2);
        snapshots.put(u1, s1);
        snapshots.put(u2, s2);
        // 记录方案会覆盖的属性原基础值（applyKit 前）
        recordAttributeBase(p1, s1);
        recordAttributeBase(p2, s2);
        // 落盘：比赛中掉线/关服也能恢复原状态
        manager.persistSnapshot(u1, s1);
        manager.persistSnapshot(u2, s2);

        Location entry1 = arena.entryPoint(true);
        Location entry2 = arena.entryPoint(false);
        if (entry1 == null || entry2 == null) {
            // 世界在开赛瞬间被卸载：交还快照，别把玩家留在错误状态
            abort("match-aborted-arena");
            return;
        }
        // 进场阶段也算倒计时：先锁定在入场点，避免这 2 秒内乱跑
        fightPos.put(u1, entry1.clone());
        fightPos.put(u2, entry2.clone());
        p1.teleport(entry1);
        p2.teleport(entry2);

        scheduleVoidCheck();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state != State.ENDED) beginRound(1);
        }, 40L);
    }

    private void recordAttributeBase(Player p, MatchSnapshot snapshot) {
        if (kit == null) return;
        for (String key : kit.attributes.keySet()) {
            org.bukkit.attribute.Attribute attribute = Compat.attribute(key);
            if (attribute == null || p.getAttribute(attribute) == null) continue;
            snapshot.attrBase.put(key, p.getAttribute(attribute).getBaseValue());
        }
    }

    private void beginRound(int n) {
        if (state == State.ENDED) return;
        round = n;
        state = State.COUNTDOWN;
        Player p1 = player(u1);
        Player p2 = player(u2);
        if (p1 == null || p2 == null) {
            abort("match-aborted-offline");
            return;
        }
        // 上一回合败者可能还停在死亡界面：先强制重生，稍后重入本回合准备流程
        if (p1.isDead() || p2.isDead()) {
            if (p1.isDead()) safeRespawn(p1);
            if (p2.isDead()) safeRespawn(p2);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (state != State.ENDED) beginRound(n);
            }, 3L);
            return;
        }
        eliminated.clear();
        Location pos1 = arena.fightPos(true);
        Location pos2 = arena.fightPos(false);
        if (pos1 == null || pos2 == null) {
            abort("match-aborted-arena");
            return;
        }
        prepareFighter(p1, pos1);
        prepareFighter(p2, pos2);
        broadcastKey("match-round", Map.of(
                "round", String.valueOf(round),
                "total", String.valueOf(totalRounds)));
        startCountdown(config.getInt("match.countdown", 5));
    }

    /** 每回合重置选手：装备、生命、饥饿、效果、位置、模式。 */
    private void prepareFighter(Player p, Location pos) {
        if (p == null) return;
        KitManager kitManager = plugin.getKitManager();
        kitManager.applyKit(p, kit);
        double health = kit.rules.health != null ? kit.rules.health : config.getDouble("match.health", 20.0);
        int food = kit.rules.foodLevel != null ? kit.rules.foodLevel : config.getInt("match.food-level", 20);
        // applyKit 可能抬高最大生命值，必须在其之后再写当前生命，否则会被旧上限截断
        p.setHealth(Math.min(health, Compat.maxHealth(p)));
        p.setFoodLevel(food);
        p.setSaturation(food);
        p.setGameMode(GameMode.SURVIVAL);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.clearActivePotionEffects();
        for (PotionEffect effect : kitManager.toPotionEffectsPublic(kit.effects)) {
            p.addPotionEffect(effect);
        }
        if (plugin.getGeyserManager().wantsNightVision(p)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 60 * 60 * 20, 0, false, false));
        }
        fightPos.put(p.getUniqueId(), pos.clone());
        p.teleport(pos);
    }

    private void startCountdown(int seconds) {
        cancelTask(countdownTask);
        int[] remaining = {Math.max(0, seconds)};
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state == State.ENDED) {
                cancelTask(countdownTask);
                return;
            }
            Player p1 = player(u1);
            Player p2 = player(u2);
            if (remaining[0] > 0) {
                String text = config.raw("match-countdown", Map.of("time", String.valueOf(remaining[0])));
                Title title = Title.title(Txt.mm(text), Component.empty(),
                        Title.Times.times(Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofMillis(200)));
                showTitle(p1, title);
                showTitle(p2, title);
                playSound(p1, Sound.UI_BUTTON_CLICK, 1f, 1f);
                playSound(p2, Sound.UI_BUTTON_CLICK, 1f, 1f);
                remaining[0]--;
            } else {
                cancelTask(countdownTask);
                state = State.FIGHTING;
                String text = config.raw("match-fight", null);
                Title title = Title.title(Txt.mm(text), Component.empty(),
                        Title.Times.times(Duration.ofMillis(50), Duration.ofMillis(700), Duration.ofMillis(300)));
                showTitle(p1, title);
                showTitle(p2, title);
                playSound(p1, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                playSound(p2, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                startRoundTimer();
            }
        }, 0L, 20L);
    }

    /** 回合限时 + 比分 actionbar；限时为 0 表示不限时（仅显示比分）。 */
    private void startRoundTimer() {
        cancelTask(roundTimerTask);
        int limit = Math.max(0, config.getInt("match.round-time", 0));
        roundSecondsLeft = limit;
        roundTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state == State.ENDED) {
                cancelTask(roundTimerTask);
                return;
            }
            if (state == State.FIGHTING && limit > 0) {
                roundSecondsLeft--;
                if (roundSecondsLeft <= 0) {
                    cancelTask(roundTimerTask);
                    judgeByTime();
                    return;
                }
            }
            sendScoreBar();
        }, 20L, 20L);
    }

    private void sendScoreBar() {
        if (state == State.ENDED) return;
        String key = limitEnabled() ? "match-actionbar-timed" : "match-actionbar";
        String raw = config.raw(key, Map.of(
                "score", scoreText(),
                "round", String.valueOf(round),
                "total", String.valueOf(totalRounds),
                "time", String.valueOf(Math.max(0, roundSecondsLeft))));
        Component bar = Txt.mm(raw);
        Player p1 = player(u1);
        Player p2 = player(u2);
        if (p1 != null) p1.sendActionBar(bar);
        if (p2 != null) p2.sendActionBar(bar);
    }

    private boolean limitEnabled() {
        return config.getInt("match.round-time", 0) > 0;
    }

    /** 回合超时：按剩余生命百分比判定，完全相同则本回合判平（双方都不得分）。 */
    private void judgeByTime() {
        if (state == State.ENDED) return;
        double h1 = healthRatio(player(u1));
        double h2 = healthRatio(player(u2));
        if (Math.abs(h1 - h2) < 0.001) {
            drawRound();
        } else {
            scoreRound(h1 > h2 ? u1 : u2);
        }
    }

    private double healthRatio(Player p) {
        if (p == null || p.isDead()) return -1;
        double max = Compat.maxHealth(p);
        return max <= 0 ? 0 : p.getHealth() / max;
    }

    /** 虚空检测：仅战斗阶段生效，低于竞技场 min-y 判负。 */
    private void scheduleVoidCheck() {
        cancelTask(voidTask);
        voidTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != State.FIGHTING) return;
            double minY = arena.effectiveMinY();
            for (UUID u : new UUID[]{u1, u2}) {
                if (eliminated.contains(u)) continue;
                Player p = player(u);
                if (p == null || !p.isOnline() || p.isDead()) continue;
                Location loc = p.getLocation();
                if (loc.getWorld() == null || loc.getY() >= minY) continue;
                // 直接把生命归零以触发正常的死亡流程；
                // 不能用 damage()，那会被「取消非对手伤害」规则拦掉。
                p.setHealth(0);
            }
        }, 40L, 10L);
    }

    // ---------------- 判胜 ----------------

    /** 玩家死亡（由监听器调用）。 */
    public void handleDeath(Player dead) {
        if (dead == null || state != State.FIGHTING) return;
        UUID deadU = dead.getUniqueId();
        if (!isFighter(deadU)) return;
        UUID winnerU = deadU.equals(u1) ? u2 : u1;
        Player killer = dead.getKiller();
        if (killer != null && killer.getUniqueId().equals(winnerU)) {
            matchKills.put(winnerU, matchKills.getOrDefault(winnerU, 0) + 1);
        }
        scoreRound(winnerU);
    }

    /** 弃权（退出/命令），直接结束比赛。 */
    public void handleForfeit(Player quitter) {
        if (state == State.ENDED || quitter == null) return;
        UUID quitterU = quitter.getUniqueId();
        if (!isFighter(quitterU)) return;
        UUID winnerU = quitterU.equals(u1) ? u2 : u1;
        // 弃权方本回合视为落败，胜者得一分后走正常结算流程
        if (winnerU.equals(u1)) score1++; else score2++;
        config.broadcast("forfeit", Map.of(
                "player", nameOf(quitterU), "other", nameOf(winnerU)));
        finish(winnerU, true);
    }

    private void scoreRound(UUID winnerU) {
        if (state == State.ENDED) return;
        if (winnerU.equals(u1)) score1++; else score2++;
        int needed = totalRounds / 2 + 1;
        if (score1 >= needed || score2 >= needed) {
            finish(winnerU, false);
            return;
        }
        state = State.ROUND_OVER;
        cancelTask(roundTimerTask);
        broadcastKey("round-winner", Map.of(
                "round", String.valueOf(round),
                "player", nameOf(winnerU),
                "score", scoreText()));
        scheduleNextRound();
    }

    /** 本回合判平：双方均不得分，进入下一回合或直接结算平局。 */
    private void drawRound() {
        if (state == State.ENDED) return;
        broadcastKey("round-draw", Map.of("round", String.valueOf(round), "score", scoreText()));
        if (round >= totalRounds) {
            finishDraw();
            return;
        }
        state = State.ROUND_OVER;
        cancelTask(roundTimerTask);
        scheduleNextRound();
    }

    private void scheduleNextRound() {
        long delay = Math.max(1, config.getInt("match.round-restart-delay", 5)) * 20L;
        cancelTask(roundTask);
        roundTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state == State.ENDED) return;
            beginRound(round + 1);
        }, delay);
    }

    private void finish(UUID winnerU, boolean forfeit) {
        if (state == State.ENDED) return;
        state = State.ENDED;
        cancelTasks();
        try {
            UUID loserU = winnerU.equals(u1) ? u2 : u1;
            String winnerName = nameOf(winnerU);
            String loserName = nameOf(loserU);
            String score = scoreText();

            int winnerScore = winnerU.equals(u1) ? score1 : score2;
            int loserScore = winnerU.equals(u1) ? score2 : score1;
            manager.recordMatch(winnerU, loserU,
                    matchKills.getOrDefault(winnerU, 0), matchKills.getOrDefault(loserU, 0),
                    winnerScore, loserScore, false);

            Player winner = player(winnerU);
            Player loser = player(loserU);
            if (winner != null) {
                config.send(winner, "match-win", Map.of("score", score, "player", loserName));
                if (plugin.getStatsManager().ratingEnabled()) {
                    config.send(winner, "rating-win", manager.ratingChangePlaceholders(winnerU));
                }
            }
            if (loser != null) {
                config.send(loser, "match-lose", Map.of("score", score, "player", winnerName));
                if (plugin.getStatsManager().ratingEnabled()) {
                    config.send(loser, "rating-lose", manager.ratingChangePlaceholders(loserU));
                }
            }
            if (!forfeit && config.getBoolean("match.broadcast-result", true)) {
                config.broadcast("match-end-broadcast", Map.of(
                        "winner", winnerName, "loser", loserName, "score", score));
            }
        } finally {
            // 结算过程中任何异常都不能让比赛对象滞留在活动列表里
            cleanup();
        }
    }

    /** 平局结算：双方积分不变，仅累计场次与回合数据。 */
    private void finishDraw() {
        if (state == State.ENDED) return;
        state = State.ENDED;
        cancelTasks();
        try {
            String score = scoreText();
            manager.recordMatch(u1, u2,
                    matchKills.getOrDefault(u1, 0), matchKills.getOrDefault(u2, 0),
                    score1, score2, true);
            Player p1 = player(u1);
            Player p2 = player(u2);
            if (p1 != null) config.send(p1, "match-draw", Map.of("score", score));
            if (p2 != null) config.send(p2, "match-draw", Map.of("score", score));
            if (config.getBoolean("match.broadcast-result", true)) {
                config.broadcast("match-draw-broadcast", Map.of(
                        "player1", name1, "player2", name2, "score", score));
            }
        } finally {
            cleanup();
        }
    }

    /** 统一收尾：恢复所有人、注销比赛。放在 finally 中确保不会泄漏比赛对象。 */
    private void cleanup() {
        try {
            restoreFighter(u1);
            restoreFighter(u2);
            for (UUID su : new HashSet<>(spectators)) {
                restoreSpectator(su);
            }
        } finally {
            spectators.clear();
            eliminated.clear();
            manager.onMatchEnd(this);
        }
    }

    private void restoreFighter(UUID u) {
        Player p = player(u);
        MatchSnapshot s = snapshots.get(u);
        // 玩家已离线：保留落盘快照，等重连时恢复
        if (p == null || s == null || !p.isOnline()) return;
        if (p.isDead()) {
            // 还在死亡界面：登记待恢复，由重生事件完成。
            // 此时比赛已结束且已从活动列表移除，matchOf 查不到，必须走独立登记表。
            s.pendingRestore = true;
            manager.registerPendingRestore(u, s);
            safeRespawn(p);
            return;
        }
        s.restore(p);
        manager.discardSnapshot(u);
    }

    private void restoreSpectator(UUID u) {
        Player p = player(u);
        MatchSnapshot s = snapshots.get(u);
        spectators.remove(u);
        snapshots.remove(u);
        if (p == null || s == null || !p.isOnline()) return;
        if (p.isDead()) {
            s.pendingRestore = true;
            manager.registerPendingRestore(u, s);
            safeRespawn(p);
            return;
        }
        s.restore(p);
    }

    /** 重生接口在不同服务端分支上可能不可用，失败时退回等待玩家自行重生。 */
    private static void safeRespawn(Player p) {
        try {
            p.spigot().respawn();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 观战 ----------------

    public boolean addSpectator(Player p) {
        if (state == State.ENDED || p == null) return false;
        UUID uuid = p.getUniqueId();
        if (spectators.contains(uuid)) return true;
        if (isFighter(uuid)) return false;
        snapshots.put(uuid, MatchSnapshot.capture(p));
        spectators.add(uuid);
        p.setGameMode(GameMode.SPECTATOR);
        Location point = arena.spectatorPoint();
        if (point != null) p.teleport(point);
        return true;
    }

    /**
     * 移除观战者。
     *
     * @param restore true = 恢复其赛前状态；false = 直接丢弃快照（例如已在别处恢复过）
     */
    public void removeSpectator(UUID uuid, boolean restore) {
        if (uuid == null || !spectators.remove(uuid)) return;
        MatchSnapshot s = snapshots.remove(uuid);
        if (!restore || s == null) return;
        Player p = player(uuid);
        if (p == null || !p.isOnline()) return;
        if (p.isDead()) {
            s.pendingRestore = true;
            manager.registerPendingRestore(uuid, s);
            safeRespawn(p);
            return;
        }
        s.restore(p);
    }

    /**
     * 本回合阵亡的选手转为旁观视角等待结果。
     *
     * <p>注意：不加入 {@link #spectators}，否则结算时会被当成外部观战者，
     * 导致选手的赛前状态不被恢复。</p>
     */
    public void makeSpectator(Player p) {
        if (p == null) return;
        UUID uuid = p.getUniqueId();
        if (!isFighter(uuid)) return;
        eliminated.add(uuid);
        p.setGameMode(GameMode.SPECTATOR);
        Location point = arena.spectatorPoint();
        if (point != null) p.teleport(point);
    }

    /** 服务器关闭等场景：取消比赛并恢复玩家（不计统计）。 */
    public void abort(String messageKey) {
        if (state == State.ENDED) return;
        state = State.ENDED;
        cancelTasks();
        try {
            for (UUID u : new UUID[]{u1, u2}) {
                Player p = player(u);
                MatchSnapshot s = snapshots.get(u);
                if (p == null || s == null || !p.isOnline()) continue;
                if (p.isDead()) {
                    s.pendingRestore = true;
                    manager.registerPendingRestore(u, s);
                    safeRespawn(p);
                } else {
                    s.restore(p);
                    manager.discardSnapshot(u);
                }
            }
            for (UUID su : new HashSet<>(spectators)) {
                restoreSpectator(su);
            }
            if (messageKey != null) {
                config.broadcast(messageKey);
            }
        } finally {
            spectators.clear();
            eliminated.clear();
            manager.onMatchEnd(this);
        }
    }

    private void cancelTasks() {
        cancelTask(countdownTask);
        cancelTask(roundTask);
        cancelTask(voidTask);
        cancelTask(roundTimerTask);
        countdownTask = null;
        roundTask = null;
        voidTask = null;
        roundTimerTask = null;
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // 插件禁用过程中取消任务会抛异常，忽略即可
            }
        }
    }

    private void broadcastKey(String key, Map<String, String> placeholders) {
        if (!config.getBoolean("match.broadcast-rounds", true)) return;
        config.broadcast(key, placeholders);
    }

    private static void showTitle(Player p, Title title) {
        if (p != null) p.showTitle(title);
    }

    private static void playSound(Player p, Sound sound, float volume, float pitch) {
        if (p != null) p.playSound(p.getLocation(), sound, volume, pitch);
    }

    /** 倒计时期间是否应冻结该玩家（离开指定点位即视为移动）。 */
    public boolean freezeDuringCountdown(Player p) {
        if (state != State.COUNTDOWN || p == null) return false;
        Location pos = fightPos.get(p.getUniqueId());
        if (pos == null) return false;
        Location current = p.getLocation();
        if (current.getWorld() == null || pos.getWorld() == null) return false;
        if (!current.getWorld().equals(pos.getWorld())) return true;
        return current.distanceSquared(pos) > 0.01;
    }
}
