package dev.logos.dimension.client.music;

import com.mojang.logging.LogUtils;
import javax.sound.sampled.AudioFormat;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.SOFTLoopPoints;
import org.slf4j.Logger;

/**
 * 声音缓冲上的循环点（SoundBufferLoopMixin 把它加到原版的 SoundBuffer 上）：持续音的采样从头放一遍起音，
 * 之后在 [起点, 终点) 之间无缝循环，按住多久响多久。用 OpenAL Soft 的 AL_SOFT_loop_points，
 * 在缓冲上传之后、挂到声源之前设上；循环本身由 OpenAL 逐采样点完成，没有接缝。
 */
public interface LoopedSoundBuffer {
    void logos_dimension$loop(int start, int end);

    Logger LOGGER = LogUtils.getLogger();

    /** 在声音线程上、缓冲刚上传完时调用。返回是否设上了。 */
    static boolean apply(int buffer, AudioFormat format, int bytes, int start, int end) {
        int frameBytes = Math.max(1, format.getChannels() * format.getSampleSizeInBits() / 8);
        int frames = bytes / frameBytes;
        end = Math.min(end, frames);
        if (start < 0 || end - start < 64) return false;
        if (!Support.checked) {
            Support.checked = true;
            Support.present = AL10.alIsExtensionPresent("AL_SOFT_loop_points");
            if (!Support.present) LOGGER.warn("OpenAL has no AL_SOFT_loop_points; held instrument notes will end when their sample ends");
        }
        if (!Support.present) return false;
        AL10.alGetError();
        AL11.alBufferiv(buffer, SOFTLoopPoints.AL_LOOP_POINTS_SOFT, new int[]{start, end});
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            LOGGER.warn("Could not set instrument loop points {}-{} (OpenAL error {})", start, end, error);
            return false;
        }
        Support.applied++;
        return true;
    }

    /** 测试用：已经设上循环点的缓冲数。 */
    static int appliedCount() {
        return Support.applied;
    }

    /** 声音线程上第一次用到时问一次 OpenAL。 */
    final class Support {
        static boolean checked;
        static boolean present;
        static volatile int applied;

        private Support() {}
    }
}
