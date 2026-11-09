package dev.logos.dimension.mixin;

import dev.logos.client.SurfaceAuras;
import dev.logos.dimension.client.foxfire.FoxfireClient;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 手上缠着狐火（{@link FoxfireWraps}）时，Logos 按奥秘给手里东西的缠绕外观换成那种颜色的表面火焰
 * （同神狐刀身上的幽蓝狐火、流火刀身上的淡粉狐火）。
 */
@Mixin(value = SurfaceAuras.class, remap = false)
abstract class FoxfireSurfaceAuraMixin {
    @Inject(method = "resolved", at = @At("TAIL"), require = 0)
    private static void logos_dimension$foxfire(ItemStackRenderState out, ItemStack stack, ItemDisplayContext context, @Nullable ItemOwner owner, CallbackInfo ci) {
        SurfaceAuras.Carrier carrier = (SurfaceAuras.Carrier) out;
        if (carrier.logos$wrap() == null) return;
        ItemOwner holder = owner instanceof ItemOwner.OffsetFromOwner offset ? offset.owner() : owner;
        if (!(holder instanceof LivingEntity living)) return;
        FoxfireFlame flame = FoxfireWraps.flame(living);
        if (flame != null) carrier.logos$aura(FoxfireClient.fire(flame), null, living.getId());
    }
}
