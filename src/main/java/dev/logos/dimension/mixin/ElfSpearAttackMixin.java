package dev.logos.dimension.mixin;

import dev.logos.dimension.item.ElfSpearItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 在玩家原版长矛刺击真正造成物理命中后接入印记，沿用其射程、阻挡、速度和冷却判断。 */
@Mixin(Player.class)
public abstract class ElfSpearAttackMixin {
    @Redirect(method = "stabAttack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean elfSpearHit(Entity victim, DamageSource source, float amount,
                              EquipmentSlot slot, Entity target, float baseDamage,
                              boolean dealsDamage, boolean dealsKnockback, boolean dismounts) {
        boolean hit = victim.hurtOrSimulate(source, amount);
        if (hit && (Object) this instanceof ServerPlayer player && victim instanceof LivingEntity living) {
            ElfSpearItem.afterPhysicalHit(player, living, slot);
        }
        return hit;
    }
}
