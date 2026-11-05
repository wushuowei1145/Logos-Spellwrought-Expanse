package dev.logos.dimension.npc;

import java.util.EnumSet;

import dev.logos.dimension.combat.Moveset;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * 神狐用打刀近战：决定攻击后先拔刀，目标在突进范围里就一个箭步冲过去拉近距离，到了刀够得着的地方就连砍；
 * 出招、连段与接触时刻全由与玩家共用的招式状态机决定（{@link DivineFox#katanaInput}）。
 */
public final class FoxKatanaGoal extends Goal {
    /** 突进：离目标这么远时冲过去；之后冷却。 */
    public static final double DASH_MIN = 3.5, DASH_MAX = 10;
    private static final int DASH_COOLDOWN = 50, REPATH = 10;
    /** 刀能砍到的距离（加上目标的半宽）。 */
    public static final double REACH = 2.4;

    private final DivineFox fox;
    private int dashCooldown, repath;

    public FoxKatanaGoal(DivineFox fox) {
        this.fox = fox;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = fox.getTarget();
        return target != null && target.isAlive() && !fox.fleeing() && !fox.bowMode() && fox.canAttack(target);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        fox.setAggressive(true);
        fox.drawKatana();
        repath = 0;
    }

    @Override
    public void stop() {
        fox.setAggressive(false);
        fox.setSprinting(false);
        fox.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = fox.getTarget();
        if (target == null) return;
        if (dashCooldown > 0) dashCooldown--;
        fox.getLookControl().setLookAt(target, 30, 30);
        if (!fox.drawKatana()) return;
        double dist = fox.distanceTo(target), reach = REACH + target.getBbWidth() * .5;
        boolean see = fox.getSensing().hasLineOfSight(target);
        if (dist > reach) {
            if (dashCooldown <= 0 && see && fox.onGround() && dist >= DASH_MIN && dist <= DASH_MAX) {
                dash(target, dist - reach);
                dashCooldown = DASH_COOLDOWN;
            } else if (--repath <= 0) {
                repath = REPATH;
                fox.setSprinting(dist > 6);
                fox.getNavigation().moveTo(target, dist > 6 ? 1.35 : 1.15);
            }
        } else {
            fox.getNavigation().stop();
            fox.setSprinting(false);
            // 面向目标再出刀；连段由状态机自己排队
            fox.setYRot(fox.getYHeadRot());
            fox.yBodyRot = fox.getYHeadRot();
            fox.katanaInput(Moveset.Input.ATTACK);
        }
    }

    /** 一个箭步冲向目标：水平速度按要走的距离给（空中阻力 0.91 每刻，约飞 8 刻），微微离地。 */
    private void dash(LivingEntity target, double gap) {
        Vec3 d = target.position().subtract(fox.position());
        Vec3 flat = new Vec3(d.x, 0, d.z).normalize();
        double speed = Math.clamp(gap / 5.6, .4, 1.3);
        fox.setDeltaMovement(flat.x * speed, .28, flat.z * speed);
        fox.needsSync = true;
        fox.getNavigation().stop();
        fox.playSound(FoxSounds.ATTACK.get(), 1, fox.getVoicePitch());
    }
}
