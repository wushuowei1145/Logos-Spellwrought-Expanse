package dev.logos.dimension.mixin;

import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.item.NatureArrow;
import dev.logos.dimension.item.NatureAttachments;
import dev.logos.dimension.npc.ForestElf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 在原版箭上附加小量状态，完整保留药水、光灵、附魔和碰撞逻辑。 */
@Mixin(AbstractArrow.class)
public abstract class NatureArrowMixin implements NatureArrow {
    @Shadow private double baseDamage;
    @Shadow protected abstract boolean isInGround();
    @Unique private long natureExpiresAt;
    @Unique private boolean natureTrailing;

    @Unique private AbstractArrow natureSelf() { return (AbstractArrow) (Object) this; }
    @Override public int natureStage() {
        return natureSelf().hasData(NatureAttachments.ARROW_STAGE) ? natureSelf().getData(NatureAttachments.ARROW_STAGE) : 0;
    }
    @Override public void natureStage(int stage) {
        natureSelf().setData(NatureAttachments.ARROW_STAGE, stage);
        natureSelf().setNoGravity(stage == 4);
        if (stage == 4 && !natureSelf().level().isClientSide()) natureExpiresAt = natureSelf().level().getGameTime() + 80;
    }
    @Override public void natureDamageScale(double scale) { baseDamage *= scale; }

    // 在筛选碰撞目标时跳过同族，箭才能继续检查同一段飞行路径后面的敌人或方块。
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void natureSkipElves(Entity target, CallbackInfoReturnable<Boolean> ci) {
        if (natureStage() > 0 && natureSelf().getOwner() instanceof ForestElf && target instanceof ForestElf) {
            ci.setReturnValue(false);
        }
    }

    @ModifyVariable(method = "applyInertia", at = @At("HEAD"), argsOnly = true)
    private float natureInertia(float inertia) { return natureStage() == 4 ? 1 : inertia; }

    // 第四阶仍可计算满弓暴击伤害，但飞行外观只使用 Logos 生命拖尾。
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;isCritArrow()Z"))
    private boolean natureCriticalParticles(AbstractArrow arrow) {
        return natureStage() != 4 && arrow.isCritArrow();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void natureExpiry(CallbackInfo ci) {
        var arrow = natureSelf();
        if (natureStage() != 4 || arrow.level().isClientSide()) return;
        boolean expired = arrow.level().getGameTime() >= natureExpiresAt;
        // 不请求加载新区块：沿下一刻的短线段逐点检查，避免箭跨入未知区块后长期保存。
        var movement = arrow.getDeltaMovement();
        int steps = Math.max(1, (int) Math.ceil(movement.length() / 2));
        for (int i = 1; !expired && i <= steps; i++) {
            expired = !arrow.level().hasChunkAt(BlockPos.containing(arrow.position().add(movement.scale((double) i / steps))));
        }
        if (expired) {
            LogosNatureBridge.trail(arrow, true);
            arrow.discard();
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void natureTick(CallbackInfo ci) {
        var arrow = natureSelf();
        if (natureStage() != 4 || arrow.level().isClientSide()) return;
        if (arrow.isRemoved() || isInGround()) {
            if (natureTrailing) LogosNatureBridge.trail(arrow, true);
            natureTrailing = false;
        } else {
            LogosNatureBridge.trail(arrow, false);
            natureTrailing = true;
        }
    }

    // 只有原版认定命中成功并完成箭矢药效后才追加生命伤害，盾牌格挡不会触发。
    @Inject(method = "onHitEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;doPostHurtEffects(Lnet/minecraft/world/entity/LivingEntity;)V", shift = At.Shift.AFTER))
    private void natureHit(EntityHitResult hit, CallbackInfo ci) {
        var arrow = natureSelf();
        if (natureStage() == 4 && !arrow.level().isClientSide() && arrow.getOwner() instanceof LivingEntity caster
                && hit.getEntity() instanceof LivingEntity target && target.isAlive()) {
            LogosNatureBridge.damage(caster, target);
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void natureSave(ValueOutput out, CallbackInfo ci) {
        out.putInt("logos_dimension:nature_stage", natureStage());
        out.putLong("logos_dimension:nature_expires_at", natureExpiresAt);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void natureLoad(ValueInput in, CallbackInfo ci) {
        int stage = in.getIntOr("logos_dimension:nature_stage", 0);
        if (stage > 0) natureStage(Math.min(4, stage));
        natureExpiresAt = in.getLongOr("logos_dimension:nature_expires_at", natureExpiresAt);
    }
}
