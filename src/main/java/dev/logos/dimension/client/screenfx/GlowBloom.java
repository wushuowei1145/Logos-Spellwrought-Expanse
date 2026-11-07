package dev.logos.dimension.client.screenfx;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.LogosDimension;
import java.util.Arrays;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * 只给该发光的东西加泛光：特效把发光的几何交过来，这一帧画完之后，再把它们画到一张空白的小图上，模糊，加回屏幕。
 * 和黑白闪一样在世界和手都画完之后跑（{@code ScreenEffectMixin}），只读世界的深度、只写最终颜色，所以不管有没有 Sodium、用什么光影包都是同一条路；
 * 光影包自己的泛光不受影响。这一帧没人交东西，就一个通道都不跑。
 * <p>不按亮度取阈值：那样雪、天空和所有白墙都会跟着泛光，为一个特效改了整个游戏的观感。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class GlowBloom {
    private GlowBloom() {}

    private static final RenderPipeline MASK = RenderPipeline.builder()
            .withLocation(LogosDimension.id("pipeline/bloom_mask"))
            .withVertexShader(LogosDimension.id("post/bloom_mask"))
            .withFragmentShader(LogosDimension.id("post/bloom_mask"))
            .withSampler("DepthSampler")
            .withSampler("HandDepthSampler")
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .build();
    private static final RenderPipeline DOWN = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/bloom_down"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(LogosDimension.id("post/bloom_down"))
            .withSampler("InSampler")
            .build();
    private static final RenderPipeline UP = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/bloom_up"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(LogosDimension.id("post/bloom_up"))
            .withSampler("InSampler")
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .build();
    private static final RenderPipeline COMPOSITE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/bloom_composite"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(LogosDimension.id("post/bloom_up"))
            .withShaderDefine("COMPOSITE")
            .withSampler("InSampler")
            .withUniform("Bloom", UniformType.UNIFORM_BUFFER)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .build();
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putFloat().get();
    /** 模糊几级，每级减半，第一级是屏幕的一半大。 */
    private static final int LEVELS = 6, SMALLEST = 8;
    /** 每个顶点：视图空间的位置，再加颜色。 */
    private static final int STRIDE = 4, QUAD = STRIDE * 4;
    /** 比这更贴近眼睛的要裁剪才行；格子和火星都很小，直接丢掉。 */
    private static final float MIN_W = .05f;
    /** 配置里的 1 相当于模糊结果的这么多。 */
    private static final float STRENGTH = .22f;

    private static final Geometry WORLD = new Geometry(), HAND = new Geometry();
    private static final Matrix4f VIEW = new Matrix4f();
    /** 从世界开始画到这一帧的通道之间才收：物品栏里的小人比通道画得晚，它不是世界。 */
    private static boolean open;
    private static long passes;
    private static @Nullable RenderTarget scene;
    private static final RenderTarget[] LEVEL = new RenderTarget[LEVELS];
    private static int levels;
    private static @Nullable MappableRingBuffer uniforms;
    private static @Nullable ByteBufferBuilder bytes;

    private static float strength() { return ClientConfig.BLOOM.get().floatValue() * STRENGTH; }

    /** 通道一共跑了多少次：客户端场景用来确认没东西发光时确实不跑。 */
    public static long passes() { return passes; }

    /** 这一帧要画的发光四边形，每四个顶点一片，每个顶点是位置加打包的颜色；{@code firstPerson} 表示在自己手上。阴影通道不要交。 */
    public static void quads(float[] data, int size, boolean firstPerson) {
        if (!open || size == 0 || strength() <= 0) return;
        Geometry out = firstPerson ? HAND : WORLD;
        Matrix4fc toView = firstPerson ? RenderSystem.getModelViewMatrix() : VIEW;
        Vector3f p = new Vector3f();
        for (int i = 0; i + STRIDE <= size; i += STRIDE) {
            toView.transformPosition(data[i], data[i + 1], data[i + 2], p);
            out.add(p.x, p.y, p.z, data[i + 3]);
        }
    }

    /** 一个朝向眼睛的发光小方块，{@code x y z} 相对镜头、世界坐标轴。阴影通道不要交。 */
    public static void sprite(float x, float y, float z, float size, int argb) {
        if (!open || strength() <= 0) return;
        Vector3f p = VIEW.transformPosition(x, y, z, new Vector3f());
        float colour = Float.intBitsToFloat(argb);
        WORLD.add(p.x - size, p.y - size, p.z, colour);
        WORLD.add(p.x + size, p.y - size, p.z, colour);
        WORLD.add(p.x + size, p.y + size, p.z, colour);
        WORLD.add(p.x - size, p.y + size, p.z, colour);
    }

    /** 世界要开始画了：这一帧的几何从这里开始收。 */
    public static void begin() {
        WORLD.size = HAND.size = 0;
        Minecraft.getInstance().gameRenderer.getMainCamera().getViewRotationMatrix(VIEW);
        open = true;
    }

    /** 半透明之前留一份深度：玻璃和水挡不住光。 */
    @SubscribeEvent
    static void onOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        if (WORLD.size == 0) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        scene(main).copyDepthFrom(main);
    }

    private static RenderTarget scene(RenderTarget main) {
        if (scene == null || scene.useStencil != main.useStencil) {
            if (scene != null) scene.destroyBuffers();
            scene = new TextureTarget("logos_dimension bloom depth", main.width, main.height, true, main.useStencil);
        } else if (scene.width != main.width || scene.height != main.height) {
            scene.resize(main.width, main.height);
        }
        return scene;
    }

    /** 由 Mixin 在世界和手都画完之后调用，{@code hudProjection} 是手用的投影。 */
    public static void draw(Matrix4fc hudProjection) {
        open = false;
        float strength = strength();
        if (WORLD.size == 0 && HAND.size == 0 || strength <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        RenderTarget depth = scene(main);
        fit(main);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (!mask(encoder, main, depth, hudProjection)) return;
        passes++;

        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        for (int i = 1; i < levels; i++) {
            try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension bloom down", LEVEL[i].getColorTextureView(), OptionalInt.of(0))) {
                pass.setPipeline(DOWN);
                pass.bindTexture("InSampler", LEVEL[i - 1].getColorTextureView(), linear);
                pass.draw(0, 3);
            }
        }
        for (int i = levels - 1; i > 0; i--) {
            try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension bloom up", LEVEL[i - 1].getColorTextureView(), OptionalInt.empty())) {
                pass.setPipeline(UP);
                pass.bindTexture("InSampler", LEVEL[i].getColorTextureView(), linear);
                pass.draw(0, 3);
            }
        }
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension bloom", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data()).putFloat(strength);
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension bloom composite", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(COMPOSITE);
            pass.setUniform("Bloom", uniforms.currentBuffer());
            pass.bindTexture("InSampler", LEVEL[0].getColorTextureView(), linear);
            pass.draw(0, 3);
        }
        uniforms.rotate();
    }

    private static void fit(RenderTarget main) {
        levels = 0;
        int w = main.width / 2, h = main.height / 2;
        while (levels < LEVELS && w >= SMALLEST && h >= SMALLEST) {
            RenderTarget t = LEVEL[levels];
            if (t == null) LEVEL[levels] = new TextureTarget("logos_dimension bloom " + levels, w, h, false);
            else if (t.width != w || t.height != h) t.resize(w, h);
            levels++;
            w /= 2;
            h /= 2;
        }
    }

    /** 把几何画到第一级；什么都没画出来时返回 false。 */
    private static boolean mask(CommandEncoder encoder, RenderTarget main, RenderTarget depth, Matrix4fc hudProjection) {
        if (bytes == null) bytes = new ByteBufferBuilder(1 << 16);
        BufferBuilder buffer = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        WORLD.project(buffer, WorldStyleRenderer.PROJECTION, null);
        HAND.project(buffer, hudProjection, RenderSystem.getDevice().isZZeroToOne() ? 0f : -1f);
        MeshData mesh = buffer.build();
        if (mesh == null) return false;
        try (mesh) {
            GpuBuffer vertices = DefaultVertexFormat.POSITION_COLOR.uploadImmediateVertexBuffer(mesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indexBuffer = indices.getBuffer(mesh.drawState().indexCount());
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension bloom mask", LEVEL[0].getColorTextureView(), OptionalInt.of(0))) {
                pass.setPipeline(MASK);
                pass.bindTexture("DepthSampler", depth.getDepthTextureView(), nearest);
                pass.bindTexture("HandDepthSampler", main.getDepthTextureView(), nearest);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
            }
        }
        return true;
    }

    /** 一帧的顶点，在视图空间。 */
    private static final class Geometry {
        private float[] data = new float[QUAD * 256];
        int size;

        void add(float x, float y, float z, float colour) {
            if (size + STRIDE > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
            data[size++] = colour;
        }

        /** 在 CPU 上投到屏幕：顶点不多，这个通道也不需要别的矩阵；{@code depth} 不为空时全部放在这个深度。 */
        void project(BufferBuilder out, Matrix4fc projection, @Nullable Float depth) {
            Vector4f p = new Vector4f();
            float[] corners = new float[12];
            for (int i = 0; i + QUAD <= size; i += QUAD) {
                boolean behind = false;
                for (int k = 0; k < 4 && !behind; k++) {
                    int o = i + k * STRIDE;
                    projection.transform(p.set(data[o], data[o + 1], data[o + 2], 1));
                    behind = p.w < MIN_W;
                    corners[k * 3] = p.x / p.w;
                    corners[k * 3 + 1] = p.y / p.w;
                    corners[k * 3 + 2] = depth != null ? depth : p.z / p.w;
                }
                if (behind) continue;
                for (int k = 0; k < 4; k++) {
                    out.addVertex(corners[k * 3], corners[k * 3 + 1], corners[k * 3 + 2]).setColor(Float.floatToRawIntBits(data[i + k * STRIDE + 3]));
                }
            }
        }
    }
}
