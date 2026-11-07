package dev.logos.dimension.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 渲染类型的设置：消散要读它的贴图（见 {@code DissolveSkin}）。 */
@Mixin(RenderType.class)
public interface RenderTypeAccess {
    @Accessor("state")
    RenderSetup logos_dimension$state();
}
