package dev.logos.dimension.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Iris 和 Sodium 是玩家自己选装的模组。这里只通过方法句柄碰它们的公开 API：编译时不依赖，没装时什么都不做，
 * 找不到接口就记一条日志，退回逐顶点写（只是慢），不会崩溃。
 */
final class ShaderCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi", SODIUM_WRITER = "net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter";
    private static final int VERTEX_INTS = 9;
    /** {@code () -> boolean}：现在是不是在画光影包的阴影。 */
    private static final @Nullable MethodHandle SHADOW_PASS = shadowPass();
    /** {@code (Object writer, MemoryStack, long pointer, int vertices, VertexFormat) -> void}：Sodium 的批量写入。 */
    private static final @Nullable MethodHandle PUSH = push();
    /** 批量写入要从原生内存读。整局游戏只用这一块，所以不释放。 */
    private static long nativeStaging;
    private static int nativeStagingInts;

    private ShaderCompat() {}

    private static @Nullable MethodHandle shadowPass() {
        try {
            Class<?> api = Class.forName(IRIS_API, true, ShaderCompat.class.getClassLoader());
            var lookup = MethodHandles.publicLookup();
            Object instance = lookup.findStatic(api, "getInstance", MethodType.methodType(api)).invoke();
            return lookup.findVirtual(api, "isRenderingShadowPass", MethodType.methodType(boolean.class)).bindTo(instance);
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (Throwable e) {
            LOGGER.warn("找不到 Iris 的阴影状态接口，NPC 的阴影不做简化", e);
            return null;
        }
    }

    private static @Nullable MethodHandle push() {
        try {
            Class<?> writer = Class.forName(SODIUM_WRITER, true, ShaderCompat.class.getClassLoader());
            return MethodHandles.publicLookup()
                    .findVirtual(writer, "push", MethodType.methodType(void.class, MemoryStack.class, long.class, int.class, VertexFormat.class))
                    .asType(MethodType.methodType(void.class, Object.class, MemoryStack.class, long.class, int.class, VertexFormat.class));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (Throwable e) {
            LOGGER.warn("找不到 Sodium 的批量写入接口，光影下 NPC 退回逐顶点写", e);
            return null;
        }
    }

    /** 现在是不是在画光影包的阴影。阴影通道的姿势矩阵在太阳的视图空间里，看的不是玩家的镜头。 */
    static boolean renderingShadowPass() {
        if (SHADOW_PASS == null) return false;
        try {
            return (boolean) SHADOW_PASS.invokeExact();
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把原版实体格式的顶点交给 Sodium 写进 {@code target}，由它转换成缓冲区实际的格式：Iris 换了顶点格式时
     * 就是 Iris 自己注册的转换，逐面算好切线和 mid UV，比逐顶点写快得多。没装 Sodium 时返回 null。
     */
    static NpcMesh.@Nullable VertexSink sodiumSink(BulkVertexTarget target) {
        return PUSH == null ? null : (data, ints) -> push(target, data, ints);
    }

    private static void push(BulkVertexTarget target, int[] data, int ints) {
        if (ints > nativeStagingInts) {
            nativeStaging = MemoryUtil.nmemRealloc(nativeStaging, (long) data.length * Integer.BYTES);
            nativeStagingInts = data.length;
        }
        MemoryUtil.memIntBuffer(nativeStaging, ints).put(data, 0, ints);
        target.logos_dimension$endLastVertex();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PUSH.invokeExact((Object) target, stack, nativeStaging, ints / VERTEX_INTS, (VertexFormat) DefaultVertexFormat.ENTITY);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
