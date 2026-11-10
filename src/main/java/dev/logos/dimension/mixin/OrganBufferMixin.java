package dev.logos.dimension.mixin;

import dev.logos.dimension.client.music.OrganMixer;
import com.mojang.blaze3d.audio.SoundBuffer;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 管风琴的声音不放 ogg：客户端按当前音栓合成循环段（{@link OrganMixer}），声音引擎要
 * {@code logos_dimension:sounds/organ_runtime/…} 的数据时直接交给它内存里的那一份，不读资源、不进原版缓存。
 */
@Mixin(SoundBufferLibrary.class)
public abstract class OrganBufferMixin {
    @Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
    private void logos_dimension$organBuffer(Identifier location, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
        if (OrganMixer.owns(location)) cir.setReturnValue(OrganMixer.buffer(location));
    }
}
