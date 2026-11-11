package dev.logos.dimension.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.logos.dimension.client.music.LoopedSoundBuffer;
import dev.logos.dimension.client.music.OrganMixer;
import dev.logos.dimension.client.music.SampleMaps;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 乐器声音的缓冲：
 * <ul>
 *   <li>管风琴不放 ogg，客户端按音栓合成循环段（{@link OrganMixer}）：声音引擎要
 *       {@code logos_dimension:sounds/organ_runtime/…} 的数据时直接交给内存里的那一份，不读资源、不进原版缓存。</li>
 *   <li>持续音乐器的采样（{@link SampleMaps} 里登记了循环点的文件）：读出缓冲后记下循环点，
 *       上传到 OpenAL 时设上（{@link LoopedSoundBuffer}）。</li>
 * </ul>
 */
@Mixin(SoundBufferLibrary.class)
public abstract class OrganBufferMixin {
    @Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
    private void logos_dimension$organBuffer(Identifier location, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
        if (OrganMixer.owns(location)) cir.setReturnValue(OrganMixer.buffer(location));
    }

    @Inject(method = "getCompleteBuffer", at = @At("RETURN"), cancellable = true)
    private void logos_dimension$sampleLoop(Identifier location, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
        int[] loop = SampleMaps.loop(location);
        if (loop == null) return;
        cir.setReturnValue(cir.getReturnValue().thenApply(buffer -> {
            ((LoopedSoundBuffer) (Object) buffer).logos_dimension$loop(loop[0], loop[1]);
            return buffer;
        }));
    }
}
