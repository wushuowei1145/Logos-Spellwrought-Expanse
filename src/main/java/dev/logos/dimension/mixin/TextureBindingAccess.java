package dev.logos.dimension.mixin;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 渲染设置的贴图来自哪里（这个记录在包外看不到）。 */
@Mixin(targets = "net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding")
public interface TextureBindingAccess {
    @Accessor("location")
    Identifier logos_dimension$location();
}
