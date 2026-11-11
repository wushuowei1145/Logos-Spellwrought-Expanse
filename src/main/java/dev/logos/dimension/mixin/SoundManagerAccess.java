package dev.logos.dimension.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 预读乐器采样用（{@code SampleMaps.prewarm}）：拿到声音引擎。 */
@Mixin(SoundManager.class)
public interface SoundManagerAccess {
    @Accessor("soundEngine")
    SoundEngine logos_dimension$engine();
}
