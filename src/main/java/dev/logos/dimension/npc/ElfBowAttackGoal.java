package dev.logos.dimension.npc;

import java.util.EnumSet;
import java.util.List;

import dev.logos.dimension.item.NaturesForceItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.Vec3;

/**
 * 森林精灵用「自然之力」远射：拉弓比玩家快，倾向于蓄满再放；和目标保持距离，靠近了就后撤；
 * 站位朝附近一起作战的同伴靠拢，远距离成群放箭。
 *
 * <p>射出走弓自己的 {@link NaturesForceItem#shootMob}（满蓄力的强化箭，无限箭、不消耗魔力、不可拾取）。
 */
public final class ElfBowAttackGoal extends Goal {
    /** 拉满所需刻数：玩家满蓄力 40 刻，精灵更熟练。 */
    public static final int DRAW_TICKS = 24;
    /** 理想距离、太近要后撤的距离、能放箭的最远距离。 */
    public static final double PREFERRED = 18, TOO_CLOSE = 8, MAX_RANGE = 48;
    private static final int RELOAD = 12, REPOSITION = 20, LOST_SIGHT = 60;

    private final ForestElf elf;
    private int seeTime, reload, reposition;

    public ElfBowAttackGoal(ForestElf elf) {
        this.elf = elf;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** 手上或收着的自然之力（收着的由精灵在有目标时拿出来，见 {@link ForestElf#weapon()}）。 */
    private boolean holdsBow() {
        return elf.weapon().getItem() instanceof NaturesForceItem || elf.getOffhandItem().getItem() instanceof NaturesForceItem;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = elf.getTarget();
        return target != null && target.isAlive() && holdsBow();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        seeTime = 0;
        reload = 0;
        if (elf.isUsingItem()) elf.stopUsingItem();
        elf.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = elf.getTarget();
        if (target == null) return;
        double dist = elf.distanceTo(target);
        boolean see = elf.getSensing().hasLineOfSight(target);
        seeTime = see ? Math.max(0, seeTime) + 1 : Math.min(0, seeTime) - 1;
        elf.getLookControl().setLookAt(target, 30, 30);
        if (reload > 0) reload--;

        // 站位：看不见就靠近；太近就后撤；否则每秒重算一次理想站位（离目标 PREFERRED，朝同伴靠拢）
        if (seeTime < -LOST_SIGHT || dist > MAX_RANGE) {
            elf.getNavigation().moveTo(target, 1.0);
        } else if (dist < TOO_CLOSE) {
            Vec3 away = elf.position().subtract(target.position()).normalize().scale(PREFERRED - dist);
            elf.getNavigation().moveTo(elf.getX() + away.x, elf.getY(), elf.getZ() + away.z, 1.2);
        } else if (--reposition <= 0) {
            reposition = REPOSITION;
            Vec3 spot = standPoint(target);
            if (spot.distanceToSqr(elf.position()) > 9) elf.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.0);
            else elf.getNavigation().stop();
        }

        // 拉弓：看得见、在射程内就拉；拉满就放
        InteractionHand hand = ProjectileUtil.getWeaponHoldingHand(elf, item -> item instanceof NaturesForceItem);
        if (elf.isUsingItem()) {
            if (!see && seeTime < -LOST_SIGHT) {
                elf.stopUsingItem();
            } else if (see && elf.getTicksUsingItem() >= DRAW_TICKS) {
                elf.stopUsingItem();
                if (elf.level() instanceof ServerLevel level && elf.getItemInHand(hand).getItem() instanceof NaturesForceItem bow) {
                    bow.shootMob(level, elf, target, elf.getItemInHand(hand), hand);
                    elf.playSound(ElfSounds.ATTACK.get(), 1, elf.getVoicePitch());
                }
                reload = RELOAD;
            }
        } else if (see && reload <= 0 && dist <= MAX_RANGE && elf.getItemInHand(hand).getItem() instanceof NaturesForceItem) {
            // 刚进入战斗的那一刻弓可能还收着，精灵下一刻拿出来再拉
            elf.startUsingItem(hand);
        }
    }

    /** 理想站位：从目标出发、朝「自己与附近同伴的中点」方向退到 PREFERRED 远。 */
    private Vec3 standPoint(LivingEntity target) {
        List<ForestElf> allies = elf.level().getEntitiesOfClass(ForestElf.class, elf.getBoundingBox().inflate(16),
                other -> other != elf && other.getTarget() == target);
        Vec3 center = elf.position();
        for (ForestElf other : allies) center = center.add(other.position());
        center = center.scale(1.0 / (allies.size() + 1));
        Vec3 dir = center.subtract(target.position());
        if (dir.horizontalDistanceSqr() < 1e-4) dir = elf.position().subtract(target.position());
        dir = new Vec3(dir.x, 0, dir.z).normalize();
        return target.position().add(dir.scale(PREFERRED));
    }
}
