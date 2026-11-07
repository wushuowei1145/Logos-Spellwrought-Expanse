package dev.logos.dimension.mixin;

import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 渲染设置里按采样器存的贴图；值通过 {@link TextureBindingAccess} 读。 */
@Mixin(RenderSetup.class)
public interface RenderSetupAccess {
    @Accessor("textures")
    Map<String, ?> logos_dimension$textures();
}
