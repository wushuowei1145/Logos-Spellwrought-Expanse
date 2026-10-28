package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.item.SpearShieldGuard;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 原版武器耐久回调固定主手；反手联动改为实际矛槽，避免副手矛损毁时清掉主手盾。 */
@Mixin(ItemStack.class)
public abstract class SpearShieldDurabilityMixin {
    @WrapOperation(method = "postHurtEnemy", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V"))
    private void actualSpearSlot(ItemStack stack, int damage, LivingEntity user, EquipmentSlot slot, Operation<Void> original) {
        if (user instanceof Player player && SpearShieldGuard.reversed(player) && player.getOffhandItem() == stack) slot = EquipmentSlot.OFFHAND;
        original.call(stack, damage, user, slot);
    }
}
