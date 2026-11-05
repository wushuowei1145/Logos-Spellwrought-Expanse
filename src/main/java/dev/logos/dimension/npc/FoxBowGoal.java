package dev.logos.dimension.npc;

import java.util.EnumSet;

import dev.logos.dimension.combat.Moveset;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.pathfinder.Node;
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
    /** 换了武器之后至少拿这么多刻才考虑换回去（和弓从拿出来到第一箭约 37 刻）。 */
    public static final int SETTLE = 60;
    private static final int RELOAD = 10, REACH_CHECK = 20, REPOSITION = 20, BACK_OFF = 10;
    /** 和弓：到会之后持满多少刻放箭；看不见目标时最多持满多少刻。 */
    public static final int KAI_HOLD = 6, KAI_GIVE_UP = 60;

    private final DivineFox fox;
    private int reload, reachCheck, reposition, backOff, seeTime, kaiTicks;
    private boolean reachable = true;
    /** {@link #reachable} 查的是哪个目标。 */
    private LivingEntity checked;

    public FoxBowGoal(DivineFox fox) {
        this.fox = fox;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** 用弓的理由：苦力怕、走不到、太远、在天上。 */
    private boolean wantsBow(LivingEntity target) {
        if (target != checked) {
            // 换了目标：上一个目标走不走得到不算数，先当走得到，马上重查
            checked = target;
            reachable = true;
            reachCheck = 0;
        }
        if (--reachCheck <= 0) {
            Path path = fox.getNavigation().createPath(target, 1);
            // 离地时（跳起、突进、被打飞）原版不算路径，返回 null：这不代表走不到，落地再查
            if (path != null) {
                reachCheck = REACH_CHECK;
                reachable = path.canReach();
            }
        }
        return target instanceof Creeper || !reachable || fox.distanceTo(target) > FAR || target.isNoGravity() && !target.onGround();
    }

    @Override
    public boolean canUse() {
        LivingEntity target = fox.getTarget();
        if (target == null || !target.isAlive() || fox.fleeing() || !fox.hasBow()) return false;
        // 刚从弓换回刀时先用一阵刀；弓还在手上（施法、逃跑打断了）就接着用，苦力怕总是用弓
        return wantsBow(target) && (fox.bowMode() || target instanceof Creeper || fox.weaponSettled(SETTLE));
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = fox.getTarget();
        if (target == null || !target.isAlive() || fox.fleeing() || !fox.hasBow()) return false;
        boolean wanted = wantsBow(target);
        double dist = fox.distanceTo(target);
        // 走得到的目标贴上来了：弓没用，立刻换刀
        if (!wanted && dist < TOO_CLOSE) return false;
        // 否则先放完一箭再说；回到刀的条件要更近一些，免得在边界上来回换
        return wanted || !fox.weaponSettled(SETTLE) || dist > FAR - 6;
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
        fox.yumiCancel();
        // 弓先留在手上：要用刀时拔刀会收起它，平静下来也会收（DivineFox.aiStep）；被施法打断时不必换一次手
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
            // 太近：能退就先退（退的时候不拉弓）；退不开（悬崖边、小平台）就站着射，不干等
            if (--backOff <= 0) {
                backOff = BACK_OFF;
                if (backAway(target, dist)) fox.yumiCancel();
                else fox.getNavigation().stop();
            }
        } else if (--reposition <= 0) {
            reposition = REPOSITION;
            if (dist > 40) fox.getNavigation().moveTo(target, 1.0);
            else fox.getNavigation().stop();
        }
        if (fox.holdsYumi()) {
            kyudo(target, see);
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

    /** 找一条背离目标、确实能拉开距离的路；有就走，没有（路的尽头离目标没有更远）返回 false。 */
    private boolean backAway(LivingEntity target, double dist) {
        Vec3 away = fox.position().subtract(target.position()).normalize().scale(PREFERRED - dist);
        Path path = fox.getNavigation().createPath(fox.getX() + away.x, fox.getY(), fox.getZ() + away.z, 1);
        Node end = path == null ? null : path.getEndNode();
        if (end == null) return false;
        double gain = dist + 2;
        return target.distanceToSqr(end.x + .5, end.y, end.z + .5) >= gain * gain && fox.getNavigation().moveTo(path, 1.25);
    }

    /** 和弓的射法：动作里身体转向左手一侧，箭顺着身体原来的朝向飞，所以拉弓时身体直接对准目标（原版身体会落后视线）。 */
    private void kyudo(LivingEntity target, boolean see) {
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
            if (!drawing && see && reload <= 0 && fox.getNavigation().isDone()) fox.yumiInput(Moveset.Input.USE_PRESS);
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
