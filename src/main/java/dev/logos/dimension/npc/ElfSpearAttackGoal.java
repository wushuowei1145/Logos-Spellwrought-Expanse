package dev.logos.dimension.npc;

import java.util.EnumSet;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.Vec3;

/** 长矛在有效射程外沿戳刺；副手使用盾时仍调用原版刺击，不占第二个物品使用槽。 */
public final class ElfSpearAttackGoal extends Goal {
    private final ForestElf elf;
    private int cooldown, reposition;

    public ElfSpearAttackGoal(ForestElf elf) {
        this.elf = elf;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean isSpear(ItemStack stack) {
        return stack.has(DataComponents.PIERCING_WEAPON) && stack.has(DataComponents.ATTACK_RANGE);
    }

    @Override
    public boolean canUse() {
        return elf.getTarget() != null && elf.getTarget().isAlive() && isSpear(elf.weapon());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        elf.setAggressive(true);
    }

    @Override
    public void stop() {
        elf.setAggressive(false);
        elf.getNavigation().stop();
        reposition = 0;
        // 换武器不能重置刺击间隔；举盾由实体单独管理。
    }

    @Override
    public void tick() {
        LivingEntity target = elf.getTarget();
        if (target == null) return;
        if (cooldown > 0) cooldown--;
        ItemStack spear = elf.getMainHandItem();
        if (!isSpear(spear)) return;
        AttackRange range = elf.getAttackRangeWith(spear);
        double min = range.effectiveMinRange(elf), max = range.effectiveMaxRange(elf);
        double preferred = max - Math.min(.25, Math.max(0, max - min) * .2);
        double distance = Math.sqrt(target.getBoundingBox().distanceToSqr(elf.getEyePosition()));
        boolean visible = elf.getSensing().hasLineOfSight(target);
        aim(target);

        reposition--;
        if (!visible) {
            if (reposition <= 0) {
                reposition = 5;
                elf.getNavigation().moveTo(target, 1.0);
            }
        } else if (distance < preferred - .15 || distance > max) {
            Vec3 away = elf.position().subtract(target.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 1e-6) away = elf.getLookAngle().multiply(-1, 0, -1);
            Vec3 offset = away.normalize().scale(preferred - distance);
            Vec3 spot = elf.position().add(offset);
            double speed = distance < preferred ? 1.2 : 1.0;
            // 默认寻路允许离目标节点一格就到达，短矛后撤会原地踏步；末段在有支撑的平地精确微调。
            if (offset.lengthSqr() < 1.44 && elf.onGround()
                    && elf.level().getBlockState(BlockPos.containing(spot).below()).isSolid()
                    && elf.level().noCollision(elf, elf.getBoundingBox().expandTowards(offset))) {
                elf.getNavigation().stop();
                elf.getMoveControl().setWantedPosition(spot.x, elf.getY(), spot.z, speed);
            } else if (reposition <= 0) {
                reposition = 5;
                var path = elf.getNavigation().createPath(BlockPos.containing(spot.x, target.getY(), spot.z), 0);
                if (path != null) elf.getNavigation().moveTo(path, speed);
            }
        } else {
            elf.getNavigation().stop();
        }

        // 明确使用主手长矛的范围，不能用 getActiveItem（举盾时它是盾）。
        if (cooldown <= 0 && visible && range.isInRange(elf, target.getBoundingBox(), 0)) {
            spear.get(DataComponents.PIERCING_WEAPON).attack(elf, EquipmentSlot.MAINHAND);
            cooldown = Math.max(1, Mth.ceil(20 / Math.max(.1, elf.getAttributeValue(Attributes.ATTACK_SPEED))));
        }
    }

    /** 原版刺击沿头部视线做碰撞检测，指向目标胸口，不能仅排队让 LookControl 缓慢转头。 */
    private void aim(LivingEntity target) {
        Vec3 delta = target.position().add(0, target.getBbHeight() * .6, 0).subtract(elf.getEyePosition());
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90;
        elf.setYRot(yaw);
        elf.setYHeadRot(yaw);
        elf.setYBodyRot(yaw);
        elf.setXRot((float) (-Mth.atan2(delta.y, delta.horizontalDistance()) * Mth.RAD_TO_DEG));
        elf.getLookControl().setLookAt(target, 30, 30);
    }
}
