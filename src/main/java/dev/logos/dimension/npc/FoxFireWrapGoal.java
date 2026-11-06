package dev.logos.dimension.npc;

import java.util.EnumSet;

import dev.logos.dimension.combat.Moveset;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 狐火缠绕：遇上生命值超过 {@link #STRONG} 的敌人时，在安全的间隙（离目标有段距离、刚才没挨打）停一下，
 * 给自己的手缠上狐火（Logos 术式 {@link LogosMagicBridge#FOX_FIRE_WRAP}，魔力从神狐自己的池子付）。
 * 缠绕结束时这一仗还没打完，就在下一个安全的间隙再缠一次；用弓时同样生效（命中的箭也带着狐火）。
 */
public final class FoxFireWrapGoal extends Goal {
    public static final float STRONG = 50;
    public static final int COST = 100;
    /** 安全：离目标至少这么远，并且这么多刻没受伤。 */
    public static final double SAFE_DISTANCE = 4;
    public static final int SAFE_TICKS = 20;
    /** 停下来缠绕的刻数（架刀的姿势），失败后的重试间隔。 */
    private static final int CAST_TICKS = 12, RETRY = 40;

    private final DivineFox fox;
    private int castTicks, retry;

    public FoxFireWrapGoal(DivineFox fox) {
        this.fox = fox;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (retry > 0) {
            retry--;
            return false;
        }
        LivingEntity target = fox.getTarget();
        if (target == null || !target.isAlive() || fox.fleeing() || fox.isDeadOrDying()) return false;
        if (!(target.getHealth() > STRONG || fox.fireFight())) return false;
        if (fox.mana() < COST || fox.wrapped()) return false;
        return fox.distanceTo(target) >= SAFE_DISTANCE && fox.sinceHurt() >= SAFE_TICKS
                && LogosMagicBridge.available(LogosMagicBridge.FOX_FIRE_WRAP, false);
    }

    @Override
    public boolean canContinueToUse() {
        return castTicks > 0 && fox.getTarget() != null && !fox.fleeing();
    }

    @Override
    public void start() {
        LivingEntity target = fox.getTarget();
        fox.getNavigation().stop();
        if (target != null) fox.getLookControl().setLookAt(target, 30, 30);
        if (!fox.spendMana(COST) || !LogosMagicBridge.cast(fox, LogosMagicBridge.FOX_FIRE_WRAP, false, COST, target)) {
            retry = RETRY;
            castTicks = 0;
            return;
        }
        fox.firedUp();
        castTicks = CAST_TICKS;
        // 用刀时架刀一下，狐火顺着刀身燃起；手上是弓或还没拿武器的背弓神狐不动手上的东西，免得白白换一次手
        if ((fox.katanaMove() != null || !fox.hasBow()) && fox.drawKatana()) fox.katanaInput(Moveset.Input.USE_PRESS);
    }

    @Override
    public void tick() {
        castTicks--;
        LivingEntity target = fox.getTarget();
        if (target != null) fox.getLookControl().setLookAt(target, 30, 30);
    }

    @Override
    public void stop() {
        castTicks = 0;
        fox.katanaInput(Moveset.Input.USE_RELEASE);
    }
}
