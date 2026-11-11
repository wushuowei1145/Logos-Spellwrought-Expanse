package dev.logos.dimension.client.music;

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
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.orchestra.Orchestra;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * 选中声部的描边（docs/ORCHESTRA.md 第 8 节）：指挥选中一个声部时，演奏它的乐器外面画一圈这个声部颜色的线。
 * 看得见的轮廓是实线；被别的东西挡住的乐器（或乐器被挡住的那一截）也描出来，淡一些；这个声部有音进来时亮一下。
 *
 * <p>做法：乐器的方块模型（真实的几何，透明的像素不算）在世界画完之后按世界的投影画到一张和屏幕一样大的空白图上：
 * g 是剪影，r 是其中没被挡住的部分（和半透明之前留下的深度比，所以玻璃、水挡不住）。再用一个全屏通道在剪影外面一圈画线，
 * 附近有看得见的剪影就是实线，只有被挡住的剪影就是淡线。手挡在前面的地方不画。和泛光、黑白闪同一个时机
 * （{@code ScreenEffectMixin}），所以有没有 Sodium、用不用光影包都一样。线宽按屏幕的像素（1080p 时 2 像素），
 * 线条是纯色，没有贴在屏幕上的纹理。
 *
 * <p>只有选了这个声部的人看得到：选中是各自客户端的事，并且要拿着绑在这座指挥台上的指挥棒，或者开着它的界面。
 * 没选中时一个通道都不跑。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class PartOutline {
    private PartOutline() {}

    private static final RenderPipeline MASK = RenderPipeline.builder()
            .withLocation(LogosDimension.id("pipeline/part_outline_mask"))
            .withVertexShader(LogosDimension.id("post/part_outline_mask"))
            .withFragmentShader(LogosDimension.id("post/part_outline_mask"))
            .withSampler("Sampler0")
            .withSampler("DepthSampler")
            .withUniform("PartOutline", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .build();
    private static final RenderPipeline COMPOSITE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/part_outline"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(LogosDimension.id("post/part_outline"))
            .withSampler("InSampler")
            .withSampler("HandDepthSampler")
            .withUniform("PartOutline", UniformType.UNIFORM_BUFFER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .build();
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putMat4f().putVec4().putVec4().get();
    /** 每个顶点：相对乐器主格的位置 3、图集 UV 2。 */
    private static final int STRIDE = 5;
    /** 看得见的线、被挡住的线的不透明度。 */
    private static final float SEEN_ALPHA = 0.95f, HIDDEN_ALPHA = 0.36f;
    /** 一帧最多画这么多个顶点（32 件乐器绰绰有余）。 */
    private static final int MAX_VERTICES = 1 << 17;

    /** 一件乐器的几何：各格的方块状态没变就一直用。 */
    private record Shape(List<BlockState> states, float[] data, @Nullable Identifier atlas) {}

    private static final Map<BlockPos, Shape> SHAPES = new HashMap<>();
    private static Conducting.@Nullable Selection active;
    private static long passes;
    private static @Nullable RenderTarget depth, mask;
    private static @Nullable MappableRingBuffer uniforms;
    private static @Nullable ByteBufferBuilder bytes;

    /** 通道一共跑了多少次（客户端场景确认没选中时不跑）。 */
    public static long passes() { return passes; }

    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(MASK);
        event.registerPipeline(COMPOSITE);
    }

    /** 这一帧要不要描：选中了声部、有人演奏它，并且是自己在指挥这座指挥台（拿着绑定的棒，或开着它的界面）。 */
    static Conducting.@Nullable Selection wanted() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return null;
        Conducting.Selection s = Conducting.selection();
        if (s == null || s.members().isEmpty()) return null;
        if (mc.screen instanceof PodiumScreen screen && screen.podium().equals(s.podium())) return s;
        for (ItemStack stack : new ItemStack[]{mc.player.getMainHandItem(), mc.player.getOffhandItem()}) {
            GlobalPos bound = stack.get(Orchestra.BATON_PODIUM.get());
            if (bound != null && bound.pos().equals(s.podium()) && bound.dimension().equals(mc.level.dimension())) return s;
        }
        return null;
    }

    /** 半透明之前留一份深度：玻璃和水挡不住线。 */
    @SubscribeEvent
    static void onOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        active = wanted();
        if (active == null) return;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        depth = fit(depth, main, true, "logos_dimension part outline depth");
        depth.copyDepthFrom(main);
    }

    private static RenderTarget fit(@Nullable RenderTarget t, RenderTarget main, boolean withDepth, String name) {
        if (t == null || withDepth && t.useStencil != main.useStencil) {
            if (t != null) t.destroyBuffers();
            return new TextureTarget(name, main.width, main.height, withDepth, withDepth && main.useStencil);
        }
        if (t.width != main.width || t.height != main.height) t.resize(main.width, main.height);
        return t;
    }

    /** 由 Mixin 在世界和手都画完之后调用（泛光之后）。 */
    public static void draw() {
        Conducting.Selection s = active;
        active = null;
        if (s == null || depth == null) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (mc.level == null || depth.width != main.width || depth.height != main.height) return;
        mask = fit(mask, main, false, "logos_dimension part outline");
        if (bytes == null) bytes = new ByteBufferBuilder(1 << 18);
        if (SHAPES.size() > 96) SHAPES.clear();

        Vec3 camera = mc.gameRenderer.getMainCamera().position();
        BufferBuilder buffer = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        Identifier atlas = null;
        int vertices = 0;
        for (BlockPos member : s.members()) {
            Shape shape = shape(member);
            if (shape == null || shape.data.length == 0) continue;
            if (atlas == null) atlas = shape.atlas;
            float ox = (float) (member.getX() - camera.x), oy = (float) (member.getY() - camera.y), oz = (float) (member.getZ() - camera.z);
            float[] d = shape.data;
            for (int i = 0; i + STRIDE <= d.length && vertices < MAX_VERTICES; i += STRIDE, vertices++) {
                buffer.addVertex(d[i] + ox, d[i + 1] + oy, d[i + 2] + oz).setUv(d[i + 3], d[i + 4]);
            }
        }
        if (atlas == null) return;
        MeshData mesh = buffer.build();
        if (mesh == null) return;

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension part outline", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
        int argb = s.color();
        float width = Math.min(5f, Math.max(2f, main.height / 400f));
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(PostVfx.viewProjection())
                    .putVec4((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, SEEN_ALPHA)
                    .putVec4(width, HIDDEN_ALPHA, Conducting.pulse(s), 0);
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (mesh) {
            GpuBuffer vertexBuffer = DefaultVertexFormat.POSITION_TEX.uploadImmediateVertexBuffer(mesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indexBuffer = indices.getBuffer(mesh.drawState().indexCount());
            try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension part outline mask", mask.getColorTextureView(), OptionalInt.of(0))) {
                pass.setPipeline(MASK);
                pass.setUniform("PartOutline", uniforms.currentBuffer());
                pass.bindTexture("Sampler0", mc.getTextureManager().getTexture(atlas).getTextureView(), nearest);
                pass.bindTexture("DepthSampler", depth.getDepthTextureView(), nearest);
                pass.setVertexBuffer(0, vertexBuffer);
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
            }
        }
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension part outline", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(COMPOSITE);
            pass.setUniform("PartOutline", uniforms.currentBuffer());
            pass.bindTexture("InSampler", mask.getColorTextureView(), nearest);
            pass.bindTexture("HandDepthSampler", main.getDepthTextureView(), nearest);
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    /** 乐器（主格在 member）各格的模型面，位置相对主格；不是乐器了时为 null。 */
    private static @Nullable Shape shape(BlockPos member) {
        Minecraft mc = Minecraft.getInstance();
        var level = mc.level;
        BlockState mainState = level.getBlockState(member);
        if (!(mainState.getBlock() instanceof InstrumentBlock block)) {
            SHAPES.remove(member);
            return null;
        }
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos c : block.cells(mainState, member)) cells.add(c.immutable());
        List<BlockState> states = new ArrayList<>(cells.size());
        for (BlockPos c : cells) states.add(level.getBlockState(c));
        Shape cached = SHAPES.get(member);
        if (cached != null && cached.states.equals(states)) return cached;

        float[] data = new float[STRIDE * 4 * 64];
        int size = 0;
        Identifier atlas = null;
        List<BlockStateModelPart> parts = new ArrayList<>();
        for (int c = 0; c < cells.size(); c++) {
            BlockPos cell = cells.get(c);
            BlockState state = states.get(c);
            if (!(state.getBlock() instanceof InstrumentBlock)) continue;
            parts.clear();
            mc.getModelManager().getBlockStateModelSet().get(state).collectParts(RandomSource.create(state.getSeed(cell)), parts);
            float ox = cell.getX() - member.getX(), oy = cell.getY() - member.getY(), oz = cell.getZ() - member.getZ();
            for (BlockStateModelPart part : parts) {
                for (int d = 0; d <= Direction.values().length; d++) {
                    for (BakedQuad q : part.getQuads(d < Direction.values().length ? Direction.values()[d] : null)) {
                        if (atlas == null) atlas = q.materialInfo().sprite().atlasLocation();
                        if (size + STRIDE * 4 > data.length) data = Arrays.copyOf(data, data.length * 2);
                        size = put(data, size, q.position0(), q.packedUV0(), ox, oy, oz);
                        size = put(data, size, q.position1(), q.packedUV1(), ox, oy, oz);
                        size = put(data, size, q.position2(), q.packedUV2(), ox, oy, oz);
                        size = put(data, size, q.position3(), q.packedUV3(), ox, oy, oz);
                    }
                }
            }
        }
        Shape shape = new Shape(states, Arrays.copyOf(data, size), atlas);
        SHAPES.put(member, shape);
        return shape;
    }

    private static int put(float[] data, int at, Vector3fc p, long uv, float ox, float oy, float oz) {
        data[at] = p.x() + ox;
        data[at + 1] = p.y() + oy;
        data[at + 2] = p.z() + oz;
        data[at + 3] = UVPair.unpackU(uv);
        data[at + 4] = UVPair.unpackV(uv);
        return at + STRIDE;
    }
}
