package dev.logos.dimension.fluid;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidType;

/** Vanilla lava travel, using this fluid's height rather than the vanilla lava tag. */
public final class ViscousFluidType extends FluidType {
    public ViscousFluidType(Properties properties) { super(properties); }

    @Override public boolean move(LivingEntity entity, Vec3 input, double gravity) {
        boolean falling = entity.getDeltaMovement().y <= 0;
        double oldY = entity.getY();
        entity.moveRelative(.02f, input);
        entity.move(MoverType.SELF, entity.getDeltaMovement());
        if (entity.getFluidHeight(this) <= entity.getFluidJumpThreshold()) {
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(.5, .8f, .5));
            entity.setDeltaMovement(entity.getFluidFallingAdjustedMovement(gravity, falling, entity.getDeltaMovement()));
        } else {
            entity.setDeltaMovement(entity.getDeltaMovement().scale(.5));
        }
        if (gravity != 0) entity.setDeltaMovement(entity.getDeltaMovement().add(0, -gravity / 4, 0));
        Vec3 movement = entity.getDeltaMovement();
        if (entity.horizontalCollision && entity.isFree(movement.x, movement.y + .6f - entity.getY() + oldY, movement.z)) {
            entity.setDeltaMovement(movement.x, .3f, movement.z);
        }
        return true;
    }

    @Override public void setItemMovement(ItemEntity entity) { entity.setFluidMovement(.95f); }
}
