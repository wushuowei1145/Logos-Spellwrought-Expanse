package dev.logos.dimension.npc;

import java.util.EnumSet;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * 重伤（生命不超过三成）时逃跑：背离打伤自己的敌人跑开，直到恢复到六成生命、拉开 {@link #SAFE} 格或跑满
 * {@link #MAX_TICKS} 刻；逃跑时不出刀、不放箭，但仍会格挡。脱离战斗后神狐会慢慢回血（DivineFox.aiStep）。
 */
public final class FoxFleeGoal extends Goal {
    public static final double SAFE = 20;
    public static final int MAX_TICKS = 200;

    private final DivineFox fox;
    private LivingEntity threat;
    private int ticks, repath;

    public FoxFleeGoal(DivineFox fox) {
        this.fox = fox;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private LivingEntity threat() {
        LivingEntity by = fox.getLastHurtByMob();
        return by != null && by.isAlive() ? by : fox.getTarget();
    }

    @Override
    public boolean canUse() {
        if (!fox.badlyHurt() || fox.isDeadOrDying()) return false;
        threat = threat();
        return threat != null && threat.isAlive() && fox.distanceTo(threat) < SAFE;
    }

    @Override
    public boolean canContinueToUse() {
        return threat != null && threat.isAlive() && ticks < MAX_TICKS && fox.getHealth() < fox.getMaxHealth() * .6f
                && fox.distanceTo(threat) < SAFE;
    }

    @Override
    public void start() {
        ticks = 0;
        repath = 0;
        fox.setFleeing(true);
        fox.setSprinting(true);
        if (fox.isUsingItem()) fox.stopUsingItem();
    }

    @Override
    public void tick() {
        ticks++;
        if (--repath > 0 && !fox.getNavigation().isDone()) return;
        repath = 20;
        Vec3 away = DefaultRandomPos.getPosAway(fox, 16, 7, threat.position());
        if (away != null) fox.getNavigation().moveTo(away.x, away.y, away.z, 1.5);
    }

    @Override
    public void stop() {
        fox.setFleeing(false);
        fox.setSprinting(false);
        fox.getNavigation().stop();
        threat = null;
    }
}
