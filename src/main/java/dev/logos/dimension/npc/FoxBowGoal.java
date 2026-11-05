package dev.logos.dimension.npc;

import java.util.EnumSet;

import dev.logos.dimension.combat.Moveset;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * 背弓的神狐在打刀够不着时换弓：目标是苦力怕（总是远远地射）、走路到不了（在天上、隔着水或悬崖），或离得太远。
 * 和目标保持距离、拉满再放（无限箭）；目标重新走得到又不远时收弓拔刀。
 * 和弓按弓道的射法：招式状态机走打起し、引分け到会，持满片刻放箭，残心之后再拉下一箭；放箭前身体正对目标。别的弓走原版拉弓。
 */
public final class FoxBowGoal extends Goal {
    /** 拉满所需刻数（玩家满蓄力 20 刻）。 */
    public static final int DRAW_TICKS = 20;
    /** 超过这个距离用弓；用弓时的理想距离与后撤距离。 */
    public static final double FAR = 16, PREFERRED = 12, TOO_CLOSE = 6;
    private static final int RELOAD = 10, REACH_CHECK = 20, REPOSITION = 20;
    /** 和弓：到会之后持满多少刻放箭；看不见目标时最多持满多少刻。 */
    public static final int KAI_HOLD = 6, KAI_GIVE_UP = 60;

    private final DivineFox fox;
    private int reload, reachCheck, reposition, seeTime, kaiTicks;
    private boolean reachable = true;

    public FoxBowGoal(DivineFox fox) {
        this.fox = fox;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** 用弓的理由：苦力怕、走不到、太远、在天上。 */
    private boolean wantsBow(LivingEntity target) {
        if (--reachCheck <= 0) {
            reachCheck = REACH_CHECK;
            Path path = fox.getNavigation().createPath(target, 1);
            reachable = path != null && path.canReach();
        }
        return target instanceof Creeper || !reachable || fox.distanceTo(target) > FAR || target.isNoGravity() && !target.onGround();
    }

    @Override
    public boolean canUse() {
        LivingEntity target = fox.getTarget();
        if (target == null || !target.isAlive() || fox.fleeing() || !fox.hasBow()) return false;
        return wantsBow(target);
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = fox.getTarget();
        if (target == null || !target.isAlive() || fox.fleeing() || !fox.hasBow()) return false;
        // 回到刀的条件要更近一些，免得在边界上来回换
        return wantsBow(target) || fox.distanceTo(target) > FAR - 6;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        fox.setAggressive(true);
        fox.equipBow();
        seeTime = 0;
    }

    @Override
    public void stop() {
        fox.setAggressive(false);
        if (fox.isUsingItem()) fox.stopUsingItem();
        fox.unequipBow();
        fox.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = fox.getTarget();
        if (target == null || !fox.equipBow()) return;
        double dist = fox.distanceTo(target);
        boolean see = fox.getSensing().hasLineOfSight(target);
        seeTime = see ? Math.max(0, seeTime) + 1 : Math.min(0, seeTime) - 1;
        fox.getLookControl().setLookAt(target, 30, 30);
        if (reload > 0) reload--;
        if (seeTime < -40) {
            fox.getNavigation().moveTo(target, 1.0);
        } else if (dist < TOO_CLOSE) {
            fox.yumiCancel();
            Vec3 away = fox.position().subtract(target.position()).normalize().scale(PREFERRED - dist);
            fox.getNavigation().moveTo(fox.getX() + away.x, fox.getY(), fox.getZ() + away.z, 1.25);
        } else if (--reposition <= 0) {
            reposition = REPOSITION;
            if (dist > 40) fox.getNavigation().moveTo(target, 1.0);
            else fox.getNavigation().stop();
        }
        if (fox.holdsYumi()) {
            kyudo(target, dist, see);
            return;
        }
        if (fox.isUsingItem() && fox.getUseItem().getItem() instanceof BowItem) {
            if (!see && seeTime < -40) {
                fox.stopUsingItem();
            } else if (see && fox.getTicksUsingItem() >= DRAW_TICKS) {
                fox.stopUsingItem();
                fox.performRangedAttack(target, BowItem.getPowerForTime(DRAW_TICKS));
                reload = RELOAD;
            }
        } else if (see && reload <= 0 && fox.getMainHandItem().getItem() instanceof BowItem) {
            fox.startUsingItem(InteractionHand.MAIN_HAND);
        }
    }

    /** 和弓的射法：动作里身体转向左手一侧，箭顺着身体原来的朝向飞，所以拉弓时身体直接对准目标（原版身体会落后视线）。 */
    private void kyudo(LivingEntity target, double dist, boolean see) {
        String move = fox.yumiMove();
        if (move == null) return;
        boolean drawing = move.equals("uchiokoshi") || move.equals("hikiwake") || move.equals("kai");
        if (drawing) {
            float yaw = (float) (Mth.atan2(target.getZ() - fox.getZ(), target.getX() - fox.getX()) * Mth.RAD_TO_DEG) - 90;
            fox.setYRot(yaw);
            fox.setYBodyRot(yaw);
            fox.setYHeadRot(yaw);
        }
        if (!move.equals("kai")) {
            kaiTicks = 0;
            // 残心没收完时状态机不接拉弓
            if (!drawing && see && reload <= 0 && dist >= TOO_CLOSE && fox.getNavigation().isDone()) fox.yumiInput(Moveset.Input.USE_PRESS);
            return;
        }
        kaiTicks++;
        if (see && kaiTicks >= KAI_HOLD) {
            fox.yumiInput(Moveset.Input.USE_RELEASE);
            fox.performRangedAttack(target, 1);
        } else if (!see && kaiTicks > KAI_GIVE_UP) {
            fox.yumiCancel();
        }
    }
}
