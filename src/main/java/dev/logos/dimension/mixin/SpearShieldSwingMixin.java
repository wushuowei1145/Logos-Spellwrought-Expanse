package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.item.SpearShieldGuard;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.PiercingWeapon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 反手戳刺挥动持矛的手，避免原版固定主手挥动使盾手播放攻击动作。 */
@Mixin(PiercingWeapon.class)
public abstract class SpearShieldSwingMixin {
    @WrapOperation(method = "attack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;swing(Lnet/minecraft/world/InteractionHand;Z)V"))
    private void guardedSpearSwing(LivingEntity attacker, InteractionHand hand, boolean update, Operation<Void> original,
                                  LivingEntity source, EquipmentSlot slot) {
        if (attacker instanceof Player player && SpearShieldGuard.paired(player)) hand = SpearShieldGuard.spearHand(player);
        original.call(attacker, hand, update);
    }
}
