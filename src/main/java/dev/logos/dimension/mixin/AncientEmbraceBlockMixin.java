package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.logos.dimension.item.AncientEmbraceItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/** 读取最终格挡结果，保留方向、绕盾伤害、穿透、耐久损耗和禁盾行为。 */
@Mixin(LivingEntity.class)
public abstract class AncientEmbraceBlockMixin {
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
