package dev.logos.dimension.ultimate;

import dev.logos.dimension.companion.CompanionEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 还没做专属演出的同伴先用的奥义：站定、看着目标，第 {@link #IMPACT} 刻打一下（她的攻击 × {@link #SCALE}，再乘连携倍率），
 * 冒一团横扫和光点。只为了让连携从头到尾走得通、测得了；每位同伴的正式奥义做好以后换掉她档案里的这一条（docs/ULTIMATES.md）。
 */
public final class StandInUltimate implements UltimateScript {
    static final int LENGTH = 30, IMPACT = 14;
    static final float SCALE = 4;

    @Override
    public int length() {
        return LENGTH;
    }

    @Override
    public int impact() {
        return IMPACT;
    }

    @Override
    public void tick(UltimateCast cast, int t) {
        CompanionEntity c = cast.companion();
        LivingEntity target = cast.target();
        if (c != null) {
            c.getNavigation().stop();
            if (target != null) c.getLookControl().setLookAt(target, 30, 30);
        }
        if (t != IMPACT) return;
        Vec3 at = target != null ? target.position().add(0, target.getBbHeight() / 2, 0) : cast.aim().add(0, .5, 0);
        var level = cast.level();
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 3, .4, .3, .4, 0);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 24, .2, .2, .2, .25);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, 1, .8f);
        if (target != null && c != null) cast.hit(target, c.attack() * SCALE);
    }
}
