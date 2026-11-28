package dev.logos.dimension.ultimate;

import dev.logos.dimension.companion.CompanionStats;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * 玩家最后一击的占位：第 {@link #IMPACT} 刻打一下，冒一团光点。伤害照规范 5.2：
 * {@code max(近战攻击, 法术强度 × 6)}，再乘最后一击的倍率和全队留下的馈赠。正式的「万象共鸣·誓约星穹破」做好以后换掉 {@link HeroUltimate#PLAYER} 里的这一条。
 */
public final class StandInFinisher implements UltimateScript {
    static final int LENGTH = 30, IMPACT = 10;

    @Override
    public int length() {
        return LENGTH;
    }

    @Override
    public int impact() {
        return IMPACT;
    }

    /** 最后一击的基础威力：近战和法术取大的（战法双修折算，docs/COMPANIONS.md 第 3.5 节）。 */
    public static float base(net.minecraft.server.level.ServerPlayer owner) {
        double melee = owner.getAttributeValue(Attributes.ATTACK_DAMAGE);
        double spell = CompanionStats.spellPower(owner, "spell_power") * 6;
        return (float) Math.max(melee, spell);
    }

    @Override
    public void tick(UltimateCast cast, int t) {
        if (t != IMPACT) return;
        LivingEntity target = cast.target();
        Vec3 at = target != null ? target.position().add(0, target.getBbHeight() / 2, 0) : cast.aim().add(0, .5, 0);
        var level = cast.level();
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 60, .3, .6, .3, .35);
        level.sendParticles(ParticleTypes.FIREWORK, at.x, at.y, at.z, 30, .2, .2, .2, .3);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.2f, 1.4f);
        if (target != null) cast.hit(target, base(cast.owner()));
    }
}
