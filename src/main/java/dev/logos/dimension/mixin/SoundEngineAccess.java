package dev.logos.dimension.mixin;

import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 预读乐器采样用（{@code SampleMaps.prewarm}）：拿到声音缓冲的缓存。 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccess {
    @Accessor("soundBuffers")
    SoundBufferLibrary logos_dimension$buffers();
}
