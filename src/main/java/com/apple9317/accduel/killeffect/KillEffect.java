package com.apple9317.accduel.killeffect;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 击杀特效（KillEffect）：致命一击时在被击杀者位置播放的视觉/音效组合。
 *
 * <p>通过 config.yml 的 {@code match.kill-effect} 选择特效类型，
 * 默认 {@code lightning}（假闪电 + 雷击音 + 死亡粒子）。</p>
 */
public final class KillEffect {

    public enum Type {
        /** 假闪电（不造成伤害）+ 雷击音 + 大量死亡烟雾。 */
        LIGHTNING,
        /** 爆炸粒子 + 爆炸音（不破坏方块、不造成伤害）。 */
        EXPLOSION,
        /** 大量爱心粒子 + 升级音（反差/搞笑效果）。 */
        HEART,
        /** 灵魂粒子 + 诡异音效。 */
        SOUL,
        /** 火焰粒子环 + 火焰音。 */
        FIRE,
        /** 仅原版死亡烟雾 + 死亡音。 */
        DEATH,
        /** 不播放特效。 */
        NONE;

        /** 未设置/default/无法识别都视为不播放特效。 */
        public static Type fromString(String raw) {
            if (raw == null || raw.isEmpty()) return NONE;
            String trimmed = raw.trim();
            if ("default".equalsIgnoreCase(trimmed)) return NONE;
            try {
                return Type.valueOf(trimmed.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
    }

    /** 无特效的 id。 */
    public static final String NONE_ID = "none";

    private KillEffect() {
    }

    /** 在被击杀者位置播放指定特效。 */
    public static void play(Type type, Player victim, Player killer) {
        if (type == null || type == Type.NONE || victim == null) return;
        Location loc = victim.getLocation();
        World world = loc.getWorld();
        if (world == null) return;
        Location at = loc.clone().add(0, 1.0, 0);

        switch (type) {
            case LIGHTNING -> {
                // strikeLightningEffect 只生成闪电实体与视觉，不造成伤害、不引燃
                world.strikeLightningEffect(loc);
                world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
                world.playSound(loc, Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
                deathSmoke(world, at);
            }
            case EXPLOSION -> {
                world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 3, 0.4, 0.4, 0.4, 0.0);
                world.spawnParticle(Particle.LARGE_SMOKE, at, 30, 0.6, 0.6, 0.6, 0.05);
                world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
                world.playSound(loc, Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
            }
            case HEART -> {
                world.spawnParticle(Particle.HEART, at, 40, 0.6, 0.8, 0.6, 0.0);
                world.spawnParticle(Particle.HAPPY_VILLAGER, at, 20, 0.5, 0.6, 0.5, 0.0);
                world.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            }
            case SOUL -> {
                world.spawnParticle(Particle.SOUL, at, 50, 0.5, 0.8, 0.5, 0.02);
                world.spawnParticle(Particle.SMOKE, at, 20, 0.4, 0.5, 0.4, 0.02);
                world.playSound(loc, Sound.PARTICLE_SOUL_ESCAPE, 1f, 0.8f);
                world.playSound(loc, Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
            }
            case FIRE -> {
                world.spawnParticle(Particle.FLAME, at, 60, 0.5, 0.6, 0.5, 0.05);
                world.spawnParticle(Particle.LAVA, at, 15, 0.3, 0.4, 0.3, 0.0);
                world.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.7f);
                world.playSound(loc, Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
            }
            case DEATH -> {
                world.playSound(loc, Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
                deathSmoke(world, at);
            }
            default -> {
            }
        }
    }

    /** 模拟玩家死亡时的大片烟雾粒子。 */
    private static void deathSmoke(World world, Location at) {
        world.spawnParticle(Particle.LARGE_SMOKE, at, 40, 0.5, 0.7, 0.5, 0.02);
        world.spawnParticle(Particle.CLOUD, at, 15, 0.3, 0.4, 0.3, 0.0);
        world.spawnParticle(Particle.SMOKE, at, 25, 0.4, 0.6, 0.4, 0.02);
    }
}
