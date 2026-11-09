package dev.logos.dimension.mixin;

import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellObject;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Logos 术式形体（魔弹、游脉、缠绕……）的所属术式、编号和所在的世界，狐火要按它们认出是不是狐火术式。 */
@Mixin(value = SpellObject.class, remap = false)
public interface SpellObjectAccess {
    @Accessor("inst")
    SpellInstance logos_dimension$inst();

    @Accessor("id")
    int logos_dimension$id();

    @Accessor("level")
    ServerLevel logos_dimension$level();
}
