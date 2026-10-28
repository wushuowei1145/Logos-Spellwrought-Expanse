package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.logos.dimension.item.AncientEmbraceItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 读取最终格挡结果，保留方向、绕盾伤害、穿透、耐久损耗和禁盾行为。 */
@Mixin(LivingEntity.class)
public abstract class AncientEmbraceBlockMixin {
    @Shadow protected it.unimi.dsi.fastutil.objects.Object2LongMap<net.minecraft.world.entity.Entity> recentKineticEnemies;

    /** 盾是实际使用物品时也初始化原版动能接触表，停止使用仍由原版清空。 */
    @Inject(method = "startUsingItem", at = @At("TAIL"))
    private void startGuardedCharge(net.minecraft.world.InteractionHand hand, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player
                && dev.logos.dimension.item.SpearShieldGuard.kineticActive(player) && recentKineticEnemies == null) {
            recentKineticEnemies = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
        }
    }

    /** 原版命中反馈仍按原时序播放，反手搭配从副手矛读取音效组件。 */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "onKineticHit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"))
    private Object guardedHitFeedback(net.minecraft.world.item.ItemStack used,
            net.minecraft.core.component.DataComponentType<?> type, Operation<Object> original) {
        if ((Object) this instanceof net.minecraft.world.entity.player.Player player
                && dev.logos.dimension.item.SpearShieldGuard.kineticActive(player)) {
            return player.getOffhandItem().get(net.minecraft.core.component.DataComponents.KINETIC_WEAPON);
        }
        return original.call(used, type);
    }
    @WrapMethod(method = "applyItemBlocking")
    private float ancientEmbraceBlock(ServerLevel level, DamageSource source, float damage, Operation<Float> original) {
        var entity = (LivingEntity) (Object) this;
        var shield = entity.getItemBlockingWith();
        int elapsed = entity.getTicksUsingItem();
        float blocked = original.call(level, source, damage);
        if (blocked > 0 && shield != null && entity instanceof ServerPlayer player) {
            AncientEmbraceItem.blocked(player, shield, elapsed);
        }
        return blocked;
    }
}
