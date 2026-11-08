package dev.logos.dimension.client.screenfx;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 在全屏后期里画的特效：世界和手都画完、画面已经调过色（比如爆裂魔法吟唱时周围变暗）之后，再把发光的特效画上去，
 * 它们就不会跟着暗下去，也不受雾和光影包的影响，几百格外的东西照样看得见。
 * <p>
 * 用法：这一帧开始画世界之后，在 {@code SubmitCustomGeometryEvent} 之类的地方用 {@link #add}/{@link #blend} 取一个顶点接收器，
 * 交给 Logos 的 {@code VfxParts} 画（位置相对镜头、世界坐标轴，和 VfxParts 平常的用法一样先 {@code translate(-camera)}）。
 * 世界画完后由 {@code ScreenEffectMixin} 调 {@link #draw}，先画半透明的、再画发光的，同一类按交上来的顺序一批一批画：和半透明之前的深度比较，被方块挡住的不画；
 * 手挡在前面的也不画，除非这批标了 {@code front}（第一人称杖头上的光）。
 * <p>
 * 这一帧没人交东西就什么都不做。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class PostVfx {
    private PostVfx() {}

    private static final RenderPipeline[] PIPELINES = new RenderPipeline[4];
    static {
        for (int i = 0; i < 4; i++) {
            boolean additive = (i & 1) == 0, front = (i & 2) != 0;
            var b = RenderPipeline.builder()
                    .withLocation(LogosDimension.id("pipeline/post_vfx_" + (additive ? "add" : "blend") + (front ? "_front" : "")))
                    .withVertexShader(LogosDimension.id("post/post_vfx"))
                    .withFragmentShader(LogosDimension.id("post/post_vfx"))
                    .withSampler("Sampler0")
                    .withSampler("HandDepthSampler")
                    .withUniform("PostVfx", UniformType.UNIFORM_BUFFER)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .withCull(false)
                    .withDepthStencilState(new DepthStencilState(front ? CompareOp.ALWAYS_PASS : CompareOp.LESS_THAN_OR_EQUAL, false))
                    .withColorTargetState(new ColorTargetState(additive ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT));
            if (front) b.withShaderDefine("FRONT");
            PIPELINES[i] = b.build();
        }
    }
    /** 启动时就编译好，第一次画时不卡一下。 */
    @SubscribeEvent
    static void pipelines(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent event) {
        for (RenderPipeline p : PIPELINES) event.registerPipeline(p);
    }

    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putMat4f().putVec4().get();
    /** 每个顶点：位置 3、UV 2、颜色 1。 */
    private static final int STRIDE = 6;

    /** 一批：同一张贴图、同一种混合。 */
    private static final class Batch implements VertexConsumer {
        final Identifier texture;
        final boolean additive, front;
        float[] data = new float[STRIDE * 256];
        int size;

        Batch(Identifier texture, boolean additive, boolean front) {
            this.texture = texture;
            this.additive = additive;
            this.front = front;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (size + STRIDE > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size] = x;
            data[size + 1] = y;
            data[size + 2] = z;
            data[size + 5] = Float.intBitsToFloat(-1);
            size += STRIDE;
            return this;
        }

        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            return setColor(a << 24 | r << 16 | g << 8 | b);
        }

        @Override public VertexConsumer setColor(int color) {
            if (size > 0) data[size - 1] = Float.intBitsToFloat(color);
            return this;
        }

        @Override public VertexConsumer setUv(float u, float v) {
            if (size > 0) {
                data[size - 3] = u;
                data[size - 2] = v;
            }
            return this;
        }

        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    private static final Map<String, Batch> BY_KEY = new LinkedHashMap<>();
    private static final List<Batch> ORDER = new ArrayList<>();
    private static boolean open;
    private static boolean wanted;
    private static long passes;
    private static @Nullable RenderTarget depth;
    private static @Nullable MappableRingBuffer uniforms;
    private static @Nullable ByteBufferBuilder bytes;

    /** 加法发光（亮的特效）。{@code front}：画在手前面（第一人称杖头上的光）。 */
    public static VertexConsumer add(Identifier texture, boolean front) {
        return batch(texture, true, front);
    }

    /** 半透明混合（暗的、要遮住后面的东西）。 */
    public static VertexConsumer blend(Identifier texture, boolean front) {
        return batch(texture, false, front);
    }

    /** {@code logos:textures/vfx/<path>.png}。 */
    public static Identifier logos(String path) {
        return Identifier.fromNamespaceAndPath("logos", "textures/vfx/" + path + ".png");
    }

    private static VertexConsumer batch(Identifier texture, boolean additive, boolean front) {
        if (!open) return DISCARD;
        String key = texture + (additive ? "+" : "~") + (front ? "!" : "");
        Batch b = BY_KEY.get(key);
        if (b == null) {
            b = new Batch(texture, additive, front);
            BY_KEY.put(key, b);
        }
        // 每批在这一帧第一次交上来的位置画：先交的在下面
        if (b.size == 0 && !ORDER.contains(b)) ORDER.add(b);
        return b;
    }

    /** 不在收的时候（比如物品栏里的小人）交上来的几何直接丢掉。 */
    private static final VertexConsumer DISCARD = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    };

    /** 通道一共跑了多少次（客户端场景看有没有在画）。 */
    public static long passes() { return passes; }

    /** 这一帧留下的半透明之前的深度；这一帧没留时为 null。别的全屏通道（爆裂魔法的球体）也拿它还原位置。 */
    public static @Nullable RenderTarget depth() {
        if (!wanted || depth == null) return null;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        return depth.width == main.width && depth.height == main.height ? depth : null;
    }

    /** 世界要开始画了：这一帧的几何从这里开始收。{@code active} 为 true 时会在半透明之前留一份深度。 */
    public static void begin(boolean active) {
        for (Batch b : ORDER) b.size = 0;
        ORDER.clear();
        open = true;
        wanted = active;
    }

    @SubscribeEvent
    static void onOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        if (!wanted) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        target(main).copyDepthFrom(main);
    }

    private static RenderTarget target(RenderTarget main) {
        if (depth == null || depth.useStencil != main.useStencil) {
            if (depth != null) depth.destroyBuffers();
            depth = new TextureTarget("logos_dimension post vfx depth", main.width, main.height, true, main.useStencil);
        } else if (depth.width != main.width || depth.height != main.height) {
            depth.resize(main.width, main.height);
        }
        return depth;
    }

    /** 由 Mixin 在世界画完之后调用；{@code viewProjection} 是世界渲染用的投影乘镜头朝向。 */
    public static void draw(Matrix4f viewProjection) {
        open = false;
        if (!wanted || depth == null || ORDER.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (depth.width != main.width || depth.height != main.height) return;
        if (bytes == null) bytes = new ByteBufferBuilder(1 << 18);
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension post vfx", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data()).putMat4f(viewProjection).putVec4(main.width, main.height, 0, 0);
        }
        var repeat = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        boolean drew = false;
        // 半透明的先画，发光的后画：暗的底色不能盖住光；画在手前面的最后画
        List<Batch> sorted = new ArrayList<>(ORDER);
        sorted.sort((x, y) -> Integer.compare((x.front ? 2 : 0) + (x.additive ? 1 : 0), (y.front ? 2 : 0) + (y.additive ? 1 : 0)));
        for (Batch b : sorted) {
            if (b.size < STRIDE * 4) continue;
            AbstractTexture texture = mc.getTextureManager().getTexture(b.texture);
            BufferBuilder buffer = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            int vertices = b.size / STRIDE / 4 * 4;
            for (int i = 0; i < vertices * STRIDE; i += STRIDE) {
                buffer.addVertex(b.data[i], b.data[i + 1], b.data[i + 2]).setUv(b.data[i + 3], b.data[i + 4]).setColor(Float.floatToRawIntBits(b.data[i + 5]));
            }
            MeshData mesh = buffer.build();
            if (mesh == null) continue;
            try (mesh) {
                GpuBuffer vertexBuffer = DefaultVertexFormat.POSITION_TEX_COLOR.uploadImmediateVertexBuffer(mesh.vertexBuffer());
                RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                GpuBuffer indexBuffer = indices.getBuffer(mesh.drawState().indexCount());
                try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension post vfx", main.getColorTextureView(), OptionalInt.empty(),
                        depth.getDepthTextureView(), OptionalDouble.empty())) {
                    pass.setPipeline(PIPELINES[(b.additive ? 0 : 1) | (b.front ? 2 : 0)]);
                    pass.setUniform("PostVfx", uniforms.currentBuffer());
                    pass.bindTexture("Sampler0", texture.getTextureView(), repeat);
                    pass.bindTexture("HandDepthSampler", main.getDepthTextureView(), nearest);
                    pass.setVertexBuffer(0, vertexBuffer);
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
                }
                drew = true;
            }
        }
        uniforms.rotate();
        if (drew) passes++;
    }
}
