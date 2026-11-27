package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.client.foxfire.FoxfireClient;
import dev.logos.dimension.foxfire.FoxfireFlame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 手上缠着龙炎时，Logos 的物品表面火焰（手里东西燃着的火、挥出去的火团和火星）换成深绯红：Logos 的火只会整体转色相，
 * 橙色转出来是淡粉，所以色相是龙炎那个记号（{@link FoxfireFlame#DRAGON} 的 {@code hue}）时，每个颜色改按
 * {@link FoxfireClient#dragonRed} 换。别的色相照旧。
 */
@Mixin(targets = "dev.logos.client.SurfaceAuraVisual", remap = false)
abstract class DragonfireSurfaceAuraMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Ldev/logos/client/VfxParts;hue(IF)I"), require = 0)
    private static int logos_dimension$dragonRed(int rgb, float turns, Operation<Integer> original) {
        return turns == FoxfireFlame.DRAGON.hue ? FoxfireClient.dragonRed(rgb) : original.call(rgb, turns);
    }
}
