package dev.logos.dimension.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.logos.dimension.client.music.LoopedSoundBuffer;
import java.util.OptionalInt;
import javax.sound.sampled.AudioFormat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 乐器采样的循环点：缓冲第一次上传到 OpenAL 之后设上（见 {@link LoopedSoundBuffer}）。其他声音不受影响。 */
@Mixin(SoundBuffer.class)
public abstract class SoundBufferLoopMixin implements LoopedSoundBuffer {
    @Shadow private boolean hasAlBuffer;
    @Shadow private int alBuffer;
    @Shadow @Final private AudioFormat format;
    @Shadow @Final private int size;
    @Unique private int logos_dimension$loopStart = -1;
    @Unique private int logos_dimension$loopEnd = -1;
    @Unique private boolean logos_dimension$loopSet;

    @Override
    public void logos_dimension$loop(int start, int end) {
        this.logos_dimension$loopStart = start;
        this.logos_dimension$loopEnd = end;
    }

    @Inject(method = "getAlBuffer", at = @At("RETURN"))
    private void logos_dimension$applyLoop(CallbackInfoReturnable<OptionalInt> cir) {
        if (logos_dimension$loopSet || logos_dimension$loopEnd <= 0 || !hasAlBuffer) return;
        logos_dimension$loopSet = true;
        LoopedSoundBuffer.apply(alBuffer, format, size, logos_dimension$loopStart, logos_dimension$loopEnd);
    }
}
