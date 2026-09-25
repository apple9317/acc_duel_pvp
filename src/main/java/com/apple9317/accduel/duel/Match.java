package com.apple9317.accduel.duel;

import com.apple9317.accduel.ACCDuelPlugin;
import com.apple9317.accduel.arena.Arena;
import com.apple9317.accduel.config.ConfigManager;
import com.apple9317.accduel.killeffect.KillEffect;
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
    /** 本场比赛中玩家放置的方块坐标 key（这些方块允许被破坏）。 */
    private final Set<String> placedBlocks = new HashSet<>();
    // bedfight：双方床是否还在
    private boolean bedAlive1 = true;
    private boolean bedAlive2 = true;

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
        // 每回合开局站在 spawn red/blue（玩家设定的出生点）；pos1/pos2 仅用于划定区域
        Location pos1 = arena.entryPoint(true);
        Location pos2 = arena.entryPoint(false);
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
        if (isBedFight()) {
            bedFightResolve(dead, dead.getKiller());
            return;
        }
        UUID winnerU = deadU.equals(u1) ? u2 : u1;
        Player killer = dead.getKiller();
        if (killer != null && killer.getUniqueId().equals(winnerU)) {
            matchKills.put(winnerU, matchKills.getOrDefault(winnerU, 0) + 1);
        }
        scoreRound(winnerU);
    }

    /**
     * 预判到致命一击时由监听器调用：伤害已被取消，玩家没有真正死亡（不进死亡界面）。
     * 双方先切到旁观模式定格击杀瞬间，播放击杀特效，再按「victim 被 killer 击杀」结算本回合。
     */
    public void handleSimulatedKill(Player victim, Player killer) {
        if (victim == null || state != State.FIGHTING) return;
        UUID deadU = victim.getUniqueId();
        if (!isFighter(deadU)) return;
        if (isBedFight()) {
            toSpectator(victim);   // 定格死者，杀手保持活动
            bedFightResolve(victim, killer);
            return;
        }
        UUID winnerU = deadU.equals(u1) ? u2 : u1;
        if (killer != null && killer.getUniqueId().equals(winnerU)) {
            matchKills.put(winnerU, matchKills.getOrDefault(winnerU, 0) + 1);
        }
        // 双方切旁观（下一回合 prepareFighter / 结算 restoreFighter 会恢复）
        toSpectator(victim);
        if (killer != null && isFighter(killer.getUniqueId())) toSpectator(killer);
        playKillEffect(killer, victim);
        scoreRound(winnerU);
    }

    /** 切旁观模式（不加入 spectators/eliminated 集合，仅改游戏模式）。 */
    private void toSpectator(Player p) {
        if (p == null) return;
        p.setGameMode(GameMode.SPECTATOR);
    }

    /** 是否为起床战争单挑。 */
    public boolean isBedFight() {
        return kit != null && com.apple9317.accduel.kit.special_kit.BedFight.TYPE.equals(kit.id);
    }

    /** bedfight 一次死亡结算：床在则重生，床没则对方获胜。 */
    private void bedFightResolve(Player victim, Player killer) {
        UUID vu = victim.getUniqueId();
        UUID winnerU = vu.equals(u1) ? u2 : u1;
        if (killer != null && killer.getUniqueId().equals(winnerU)) {
            matchKills.merge(winnerU, 1, Integer::sum);
        }
        playKillEffect(killer, victim);
        broadcastTaunt(winnerU, vu);
        if (bedAlive(vu)) {
            scheduleBedRespawn(vu);
        } else {
            finish(winnerU, false);
        }
    }

    private boolean bedAlive(UUID u) {
        return u.equals(u1) ? bedAlive1 : bedAlive2;
    }

    /** 床还在：提示，3 秒后把玩家重置到出生点并复原装备。 */
    private void scheduleBedRespawn(UUID vu) {
        Player victim = player(vu);
        if (victim != null) config.send(victim, "bed-respawn");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state == State.ENDED) return;
            Player p = player(vu);
            if (p == null) return;
            prepareFighter(p, arena.entryPoint(vu.equals(u1)));
            eliminated.remove(vu);
        }, com.apple9317.accduel.kit.special_kit.BedFight.RESPAWN_DELAY);
    }

    /** 击杀后播报一句温和有趣的嘲讽。 */
    private void broadcastTaunt(UUID winnerU, UUID loserU) {
        net.kyori.adventure.text.Component t =
                config.randomTaunt(nameOf(winnerU), nameOf(loserU));
        if (t != null) plugin.getServer().broadcast(t);
    }
    /** 按击杀者个人设置播放击杀特效。 */
    private void playKillEffect(Player killer, Player victim) {
        String effectId = KillEffect.NONE_ID;
        if (killer != null && isFighter(killer.getUniqueId())) {
            effectId = plugin.getSettingsManager().get(killer.getUniqueId()).killEffect;
        }
        KillEffect.play(KillEffect.Type.fromString(effectId), victim, killer);
    }

    /** 该方块是否为破坏者对方的床。 */
    public boolean isOpponentBedBlock(org.bukkit.block.Block block, Player breaker) {
        if (!isBedFight() || block == null || breaker == null) return false;
        if (breaker.getUniqueId().equals(u1)) return arena.isTeamBed(block, false);
        if (breaker.getUniqueId().equals(u2)) return arena.isTeamBed(block, true);
        return false;
    }

    /** 床被破坏：标记该队床毁并播报。 */
    public void notifyBedBroken(org.bukkit.block.Block block, Player breaker) {
        boolean redBed;
        if (arena.isTeamBed(block, true)) redBed = true;
        else if (arena.isTeamBed(block, false)) redBed = false;
        else return;
        if (redBed) bedAlive1 = false; else bedAlive2 = false;
        UUID teamU = redBed ? u1 : u2;
        config.broadcast("bed-broken", Map.of(
                "player", nameOf(breaker.getUniqueId()),
                "team", redBed ? "红队" : "蓝队",
                "victim", nameOf(teamU)));
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
        broadcastTaunt(winnerU, winnerU.equals(u1) ? u2 : u1);
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
            // 先复原竞技场地形（清除玩家放置的方块、恢复被破坏的方块）
            plugin.getArenaManager().restoreTemplate(arena);
            placedBlocks.clear();
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

    /** 重生接口在不同服务端分支上可能不可用：优先反射调 Paper 的 Player.respawn()，失败退回 Spigot 代理。 */
    private static void safeRespawn(Player p) {
        try {
            p.getClass().getMethod("respawn").invoke(p);
            return;
        } catch (Throwable ignored) {
        }
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

    /** 记录玩家放置的方块。 */
    public void trackPlacedBlock(Location loc) {
        if (loc != null) placedBlocks.add(blockKey(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }

    /** 该位置是否为玩家在本场比赛中放置的方块。 */
    public boolean isPlayerPlaced(Location loc) {
        return loc != null && placedBlocks.contains(blockKey(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
    }

    private static String blockKey(int x, int y, int z) {
        return x + "," + y + "," + z;
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
