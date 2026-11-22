package dev.logos.dimension.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBiome;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.wind.GrassWind;
import dev.logos.dimension.wind.WindField;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** 缓存区段的原草模型，单批提交；顶点着色器弯曲草叶，不逐帧搜索世界或重建区块。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class WindGrass {
    private static final int RADIUS = 40, KEEP_RADIUS = 56, MAX_SECTIONS = 48, MAX_QUADS = 12000;
    private static final Map<Long, Batch> CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> REQUESTS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> REFUSED = ConcurrentHashMap.newKeySet();
    private static final ContextKey<Frame> FRAME = new ContextKey<>(LogosDimension.id("grass_wind"));
    private static volatile boolean reset;
    private static volatile Vec3 eye;
    private static ClientLevel previous;
    private static int clock;
    private static int probe;
    public static volatile int lastQuads;
    private static RenderType type;
    private record Vertex(float x, float y, float z, float u, float v, int color, int light, int weight, float nx, float ny, float nz) {}
    private record Tuft(BlockPos block, List<Vertex> vertices) {}
    private record Batch(BlockPos origin, List<Tuft> tufts, int quads) {}
    private record Frame(List<Batch> batches, WindField.Wind wind, double amount) {}

    private WindGrass() {}
    public static int cachedSections() { return CACHE.size(); }
    public static boolean cachedAt(BlockPos pos) {
        var batch = CACHE.get(SectionPos.asLong(pos));
        return batch != null && batch.tufts().stream().anyMatch(t -> t.block().equals(pos));
    }
    public static void modelsChanged() { reset = true; }

    private static RenderType type() {
        if (type == null) {
            var pipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation(LogosDimension.id("pipeline/grass_wind"))
                    .withVertexShader(LogosDimension.id("core/grass_wind"))
                    .withFragmentShader(LogosDimension.id("core/grass_wind"))
                    .withSampler("Sampler0").withSampler("Sampler2")
                    .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS).withCull(true)
                    .withDepthStencilState(DepthStencilState.DEFAULT).build();
            type = RenderType.create("logos_dimension_grass_wind", RenderSetup.builder(pipeline)
                    .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS).useLightmap().createRenderSetup());
        }
        return type;
    }

    /** 只接管长风草皮上的四类草；双层草从共同根点检查支撑。可从区块编译线程调用。 */
    static boolean eligible(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        if (!GrassWindModel.grass(state)) return false;
        BlockPos root = state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        return level.getBlockState(root.below()).is(CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get());
    }

    static boolean replace(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        Vec3 at = eye;
        if (at == null || !eligible(level, pos, state)) return false;
        long key = SectionPos.asLong(pos);
        if (CACHE.containsKey(key)) return true;
        if (pos.distToCenterSqr(at) < RADIUS * RADIUS && REQUESTS.size() < 128 && !REFUSED.contains(key)) REQUESTS.add(key);
        return false;
    }

    /** 方块改变时立即撤掉旧草，重新烘焙；上下边界也失效，双层草不会剩下一截。 */
    public static void changed(BlockPos pos) {
        if (eye == null) return;
        for (int dy = -1; dy <= 1; dy++) {
            long key = SectionPos.asLong(pos.above(dy));
            if (CACHE.remove(key) != null) {
                REQUESTS.add(key);
                dirty(key);
            }
            REFUSED.remove(key);
        }
    }

    private static void dirty(long key) {
        var mc = Minecraft.getInstance();
        if (mc.level != null) mc.levelRenderer.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
    }

    private static void clear() {
        var keys = List.copyOf(CACHE.keySet());
        CACHE.clear(); REQUESTS.clear(); REFUSED.clear(); eye = null; lastQuads = 0;
        keys.forEach(WindGrass::dirty);
    }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (reset || previous != mc.level) { clear(); previous = mc.level; reset = false; }
        if (mc.level == null || !mc.level.dimension().equals(LogosDimension.CLOUDSEA) || !ClientConfig.GRASS_WIND.getAsBoolean()) {
            if (eye != null) clear();
            return;
        }
        boolean starting = eye == null;
        eye = mc.gameRenderer.getMainCamera().position();
        if (starting) mc.levelRenderer.allChanged();
        var at = eye;
        // 已经编译成静态草的远区段不会再调用模型包装器；分摊少量地表探针，让步行进入的新近景也能接管。
        var cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 12; i++) {
            int cell = probe++ % (21 * 21);
            int x = (int) Math.floor(at.x / 4) * 4 + (cell % 21 - 10) * 4;
            int z = (int) Math.floor(at.z / 4) * 4 + (cell / 21 - 10) * 4;
            if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
            int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            cursor.set(x, y, z);
            var state = mc.level.getBlockState(cursor);
            if (eligible(mc.level, cursor, state)) {
                replace(mc.level, cursor, state);
                if (state.hasProperty(DoublePlantBlock.HALF)) replace(mc.level, cursor.above(), mc.level.getBlockState(cursor.above()));
            }
        }
        REFUSED.removeIf(key -> SectionPos.of(key).origin().distToCenterSqr(at) > KEEP_RADIUS * KEEP_RADIUS);
        boolean released = false;
        for (var entry : List.copyOf(CACHE.entrySet())) {
            BlockPos p = entry.getValue().origin();
            if (p.distToCenterSqr(at) > KEEP_RADIUS * KEEP_RADIUS || !mc.level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) {
                CACHE.remove(entry.getKey()); dirty(entry.getKey()); released = true;
            }
        }
        if (released) REFUSED.clear();
        // 每刻最多扫描两个 16³ 区段；不请求未加载区块。
        var requested = REQUESTS.stream().sorted(Comparator.comparingDouble(k -> SectionPos.of(k).origin().distToCenterSqr(at))).limit(2).toList();
        for (long key : requested) {
            REQUESTS.remove(key);
            if (CACHE.containsKey(key) || CACHE.size() >= MAX_SECTIONS) continue;
            BlockPos origin = SectionPos.of(key).origin();
            if (!mc.level.hasChunk(origin.getX() >> 4, origin.getZ() >> 4) || origin.distToCenterSqr(at) > KEEP_RADIUS * KEEP_RADIUS) continue;
            Batch batch = build(mc, origin);
            if (batch.quads() + CACHE.values().stream().mapToInt(Batch::quads).sum() > MAX_QUADS) { REFUSED.add(key); continue; }
            CACHE.put(key, batch); dirty(key);
        }
        // 随机刻、灯光和换季会改变外观；低频重建一个区段，避免光照永久停在旧值。
        if (++clock % 20 == 0 && !CACHE.isEmpty()) {
            var keys = new ArrayList<>(CACHE.keySet()); Collections.sort(keys);
            long key = keys.get((clock / 20) % keys.size());
            Batch fresh = build(mc, SectionPos.of(key).origin());
            int others = CACHE.values().stream().mapToInt(Batch::quads).sum() - CACHE.get(key).quads();
            if (others + fresh.quads() <= MAX_QUADS) CACHE.put(key, fresh);
            else { CACHE.remove(key); REFUSED.add(key); dirty(key); }
        }
    }

    private static Batch build(Minecraft mc, BlockPos origin) {
        var tufts = new ArrayList<Tuft>();
        var lighter = new ModelBlockRenderer(true, false, mc.getBlockColors());
        int quads = 0;
        for (var p : BlockPos.betweenClosed(origin, origin.offset(15, 15, 15))) {
            var state = mc.level.getBlockState(p);
            if (!eligible(mc.level, p, state)) continue;
            var vertices = new ArrayList<Vertex>();
            BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
            if (model instanceof GrassWindModel wrapper) model = wrapper.original();
            model = GrassWindModel.appearance(mc.level, p, state, model);
            boolean tall = state.hasProperty(DoublePlantBlock.HALF);
            int upper = tall && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? 1 : 0;
            lighter.tesselateBlock((x, y, z, quad, instance) -> {
                for (int i = 0; i < 4; i++) {
                    var v = quad.position(i);
                    var normal = quad.direction();
                    int bakedNormal = quad.bakedNormals().normal(i);
                    boolean specified = !net.neoforged.neoforge.client.model.quad.BakedNormals.isUnspecified(bakedNormal);
                    vertices.add(new Vertex(x + v.x(), y + v.y(), z + v.z(), UVPair.unpackU(quad.packedUV(i)), UVPair.unpackV(quad.packedUV(i)),
                            net.minecraft.util.ARGB.multiply(instance.getColor(i), quad.bakedColors().color(i)), instance.getLightCoordsWithEmission(i, quad.materialInfo().lightEmission()),
                            GrassWind.weight(v.y() + upper, tall),
                            specified ? net.neoforged.neoforge.client.model.quad.BakedNormals.unpackX(bakedNormal) : normal.getStepX(),
                            specified ? net.neoforged.neoforge.client.model.quad.BakedNormals.unpackY(bakedNormal) : normal.getStepY(),
                            specified ? net.neoforged.neoforge.client.model.quad.BakedNormals.unpackZ(bakedNormal) : normal.getStepZ()));
                }
            }, p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ(), mc.level, p, state, model, state.getSeed(p));
            quads += vertices.size() / 4;
            if (quads > MAX_QUADS) break;
            tufts.add(new Tuft(p.immutable(), List.copyOf(vertices)));
        }
        return new Batch(origin, List.copyOf(tufts), quads);
    }

    @SubscribeEvent static void extract(ExtractLevelRenderStateEvent event) {
        if (eye == null || event.getLevel() != previous || CACHE.isEmpty()) return;
        event.getRenderState().setRenderData(FRAME, new Frame(List.copyOf(CACHE.values()), WindField.of(event.getLevel()), ClientConfig.GRASS_WIND_AMOUNT.getAsDouble()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) static void submit(SubmitCustomGeometryEvent event) {
        var frame = event.getLevelRenderState().getRenderData(FRAME);
        lastQuads = 0;
        if (frame == null) return;
        var camera = event.getLevelRenderState().cameraRenderState.pos;
        var collector = LogosSkyOverlay.collector(event.getSubmitNodeCollector());
        // UV0 保留原材质，UV2 保留真实光照；自有 UV1 存风参数，Color.a 存根尖权重。
        collector.submitCustomGeometry(event.getPoseStack(), type(), (pose, out) -> {
            for (var batch : frame.batches()) {
                float x = (float) (batch.origin().getX() - camera.x), y = (float) (batch.origin().getY() - camera.y), z = (float) (batch.origin().getZ() - camera.z);
                for (var tuft : batch.tufts()) {
                    double rootX = tuft.block().getX() + .5, rootZ = tuft.block().getZ() + .5;
                    int wind = GrassWind.wind(frame.wind(), frame.amount() * GrassWind.fade(Math.hypot(rootX - camera.x, rootZ - camera.z)));
                    int phase = GrassWind.phase(rootX, rootZ, frame.wind());
                    for (var v : tuft.vertices()) out.addVertex(pose, x + v.x(), y + v.y(), z + v.z())
                            .setColor(v.color() & 0xffffff | v.weight() << 24).setUv(v.u(), v.v()).setUv1(wind, phase)
                            .setLight(v.light()).setNormal(pose, v.nx(), v.ny(), v.nz());
                }
                lastQuads += batch.quads();
            }
        });
    }
}
