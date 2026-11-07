package dev.logos.dimension.mixin;

import dev.logos.dimension.client.dissolve.Dissolves;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 每个要画的物品都在这里解析模型：记下谁拿着它。 */
@Mixin(ItemModelResolver.class)
public abstract class DissolveItemResolverMixin {
    @Inject(method = "updateForTopItem", at = @At("TAIL"))
    private void dissolveResolved(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext, @Nullable Level level,
            @Nullable ItemOwner owner, int seed, CallbackInfo ci) {
        Dissolves.resolved(output, item, displayContext, owner);
    }
}
