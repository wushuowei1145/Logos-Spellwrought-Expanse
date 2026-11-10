package dev.logos.dimension.client.screenfx;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.worldstyle.StyleDoor;
import dev.logos.dimension.worldstyle.StyleRealm;
import dev.logos.dimension.worldstyle.StyleRealmsPayload;
import dev.logos.dimension.worldstyle.WorldStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 本机的区域画风：判断镜头在哪个区域，播进出时的球形波，再用一个全屏通道把画风画上去。
 * 只用「半透明之前的深度」和「最终画面的颜色」，不往世界里画东西，所以和 Sodium、光影包都走同一条路。
 * 附近没有门、也不在区域里时整个通道都不跑，没有开销。
 *
 * <p>群系也能带画风（见 {@link WorldStyle#biomes()}）：镜头所在群系有画风时，它就替代「原画」，
 * 门里门外、波里波外凡是原画的地方都换成它；进出群系时用几秒淡入淡出，不放波。
 * 群系画风带球形遮罩，离镜头 64 格以内保持原画、128 格以外才是完整的画风（着色器里的 {@code BIOME_MASK}）。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class WorldStyleRenderer {
    /** 含视角晃动的投影矩阵，由 Mixin 每帧记下；还原位置要和世界渲染用同一个，否则走路时遮罩和门框错开。 */
    public static final Matrix4f PROJECTION = new Matrix4f();
    private static final int MAX_DOORS = 4;
    /** 门在这个距离以内才算数。再远门洞也就几个像素。 */
    private static final double DOOR_RANGE = 128;
    /** 波走完要多久，走到多远。走到这里时天空也换掉了，和着色器里的 WAVE_REACH 差出羽化的宽度。 */
    private static final float WAVE_SECONDS = 1.6f, WAVE_RADIUS = 104;
    private static final float PREVIEW = .5f, FULL = 1;
    /** 一帧内挪动超过这么远就不是走过去的了：传送的直线路径碰巧穿过门框时，不能算走门。 */
    private static final double MAX_WALK_STEP = 8;
    /** 进出带画风的群系时淡入淡出要多久。 */
    private static final float BIOME_FADE_SECONDS = 2.5f;
    /** 贴在世界上的纹理（笔触、云气）在 x、z 上每隔这么远重复一次：坐标先取模再传进着色器，免得远离原点时浮点精度不够。须是 256 的倍数。 */
    private static final long NOISE_PERIOD = 4096;
    /** 云气随风飘的速度，格每秒。 */
    private static final double WIND_X = 1.1, WIND_Z = .45;
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/world_style"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(LogosDimension.id("post/world_style"))
            .withSampler("InSampler")
            .withSampler("DepthSampler")
            .withSampler("HandDepthSampler")
            .withUniform("WorldStyle", UniformType.UNIFORM_BUFFER)
            .build();
    private static final int UNIFORM_SIZE;
    static {
        var size = new Std140SizeCalculator().putMat4f();
        for (int i = 0; i < MAX_DOORS * 3; i++) size.putVec4();
        UNIFORM_SIZE = size.putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();
    }

    private static List<StyleRealm> realms = List.of();
    private static @Nullable Level realmsLevel;
    private static @Nullable StyleRealm current;
    private static @Nullable Vec3 lastEye;
    private static boolean captured, enteredByDoor;
    private static long passes;
    /** 当前这一波：开始的时间、球心，以及波还没走到的地方是什么样子。 */
    private static long waveStart;
    private static boolean waving;
    private static Vec3 waveCenter = Vec3.ZERO;
    private static int outerStyle;
    private static float outerLevel;
    /** 群系画风：哪一种、现在淡到几成，上一帧的时间。离开群系后画风留着，直到淡完。 */
    private static @Nullable WorldStyle biomeStyle;
    private static float biomeLevel;
    private static long lastFrame;
    /** 这一帧最终的雾色（其他模组改完之后），国画用它定纸色的明暗：夜里纸也是暗的。 */
    private static float fogRed = .75f, fogGreen = .8f, fogBlue = .85f;
    private static @Nullable RenderTarget scene;
    private static @Nullable MappableRingBuffer uniforms;

    private WorldStyleRenderer() {}

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(StyleRealmsPayload.TYPE, (payload, context) -> {
            realms = payload.realms();
            realmsLevel = Minecraft.getInstance().level;
        });
    }

    /** 半透明画之前：判断这一帧要不要画，要的话留一份深度。这样玻璃和水挡在门前也不会打断门的判定。 */
    @SubscribeEvent static void beforeTranslucent(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != realmsLevel) {
            realms = List.of();
            realmsLevel = null;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        trackCamera(camera, eye);
        trackBiome(mc, camera, eye);
        if (current == null && !waving && biomeLevel <= 0 && doorsNear(eye).isEmpty()) return;

        RenderTarget main = mc.getMainRenderTarget();
        if (scene == null || scene.useStencil != main.useStencil) {
            if (scene != null) scene.destroyBuffers();
            scene = new TextureTarget("logos_dimension world style", main.width, main.height, true, main.useStencil);
        } else if (scene.width != main.width || scene.height != main.height) {
            scene.resize(main.width, main.height);
        }
        scene.copyDepthFrom(main);
        captured = true;
    }

    private static void trackCamera(Camera camera, Vec3 eye) {
        StyleRealm now = null;
        for (StyleRealm realm : realms) {
            if (realm.contains(eye)) {
                now = realm;
                break;
            }
        }
        if (lastEye != null && now != current) startWave(camera, current, now, crossedDoor(lastEye, eye, current, now));
        current = now;
        lastEye = eye;
    }

    /** 镜头所在群系的画风，按真实时间淡入淡出。水下、岩浆里不算：那时看到的已经不是山水了。 */
    private static void trackBiome(Minecraft mc, Camera camera, Vec3 eye) {
        long now = Util.getNanos();
        float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9f, .25f);
        lastFrame = now;
        WorldStyle found = null;
        if (mc.level != null && camera.getFluidInCamera() == FogType.NONE && ClientConfig.BIOME_STYLE.getAsDouble() > 0) {
            Holder<Biome> biome = mc.level.getBiome(BlockPos.containing(eye));
            for (WorldStyle style : WorldStyle.values()) {
                if (biome.is(style.biomes())) {
                    found = style;
                    break;
                }
            }
        }
        // 换到另一种群系画风时先淡出旧的，再淡入新的
        float step = dt / BIOME_FADE_SECONDS;
        if (found != null && (found == biomeStyle || biomeStyle == null)) {
            biomeStyle = found;
            biomeLevel = Math.min(biomeLevel + step, 1);
        } else {
            biomeLevel = Math.max(biomeLevel - step, 0);
            if (biomeLevel <= 0) biomeStyle = null;
        }
    }

    /** 记下最终的雾色。排在最后，别的模组改过的也算进去。 */
    @SubscribeEvent(priority = EventPriority.LOWEST) static void fogColor(ViewportEvent.ComputeFogColor event) {
        fogRed = event.getRed();
        fogGreen = event.getGreen();
        fogBlue = event.getBlue();
    }

    private static boolean crossedDoor(Vec3 from, Vec3 to, @Nullable StyleRealm a, @Nullable StyleRealm b) {
        if (from.distanceTo(to) > MAX_WALK_STEP) return false;
        for (StyleRealm realm : new StyleRealm[] {a, b}) {
            if (realm == null) continue;
            for (StyleDoor door : realm.doors()) if (door.crossedBy(from, to)) return true;
        }
        return false;
    }

    /**
     * 走门进去时，眼前本来就透过门看到了预览，波把它推成完全体；走门出来时门外本来就是原画，只有一圈光。
     * 不走门（传送、死亡）就没有这种连续性，波外保持原来的样子，等波来替换。
     */
    private static void startWave(Camera camera, @Nullable StyleRealm from, @Nullable StyleRealm to, boolean viaDoor) {
        enteredByDoor = viaDoor;
        waving = true;
        waveStart = Util.getNanos();
        waveCenter = camera.entity() != null ? camera.entity().position() : camera.position();
        if (viaDoor) {
            outerStyle = to == null ? 0 : to.style().shaderId();
            outerLevel = to == null ? 0 : PREVIEW;
        } else {
            outerStyle = from == null ? 0 : from.style().shaderId();
            outerLevel = from == null ? 0 : FULL;
        }
    }

    private static List<DoorInfo> doorsNear(Vec3 eye) {
        if (realms.isEmpty()) return List.of();
        var doors = new ArrayList<DoorInfo>();
        for (StyleRealm realm : realms) for (StyleDoor door : realm.doors()) {
            double distance = door.center().distanceTo(eye);
            if (distance <= DOOR_RANGE) doors.add(new DoorInfo(door, realm.style(), realm == current, distance));
        }
        doors.sort(Comparator.comparingDouble(DoorInfo::distance));
        return doors.size() > MAX_DOORS ? doors.subList(0, MAX_DOORS) : doors;
    }

    private record DoorInfo(StyleDoor door, WorldStyle style, boolean own, double distance) {}

    /** 由 Mixin 在世界和手都画完之后调用。 */
    public static void draw() {
        if (!captured) return;
        captured = false;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.position();
        RenderTarget main = mc.getMainRenderTarget();
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "logos_dimension world style", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), scene.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);

        Matrix4f inverse = new Matrix4f(PROJECTION).mul(camera.getViewRotationMatrix(new Matrix4f())).invert();
        List<DoorInfo> doors = doorsNear(eye);
        Vec3 wave = waveCenter.subtract(eye);
        float radius = waveRadius();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(uniforms.currentBuffer(), false, true)) {
            Std140Builder out = Std140Builder.intoBuffer(view.data()).putMat4f(inverse);
            for (int i = 0; i < MAX_DOORS; i++) {
                if (i < doors.size()) {
                    Vec3 c = doors.get(i).door().center().subtract(eye);
                    out.putVec4((float) c.x, (float) c.y, (float) c.z, doors.get(i).style().shaderId());
                } else out.putVec4(0, 0, 0, 0);
            }
            for (int i = 0; i < MAX_DOORS; i++) {
                if (i < doors.size()) {
                    Vec3 r = doors.get(i).door().right();
                    out.putVec4((float) r.x, (float) r.y, (float) r.z, doors.get(i).own() ? 1 : 0);
                } else out.putVec4(0, 0, 0, 0);
            }
            for (int i = 0; i < MAX_DOORS; i++) {
                if (i < doors.size()) {
                    Vec3 u = doors.get(i).door().up();
                    out.putVec4((float) u.x, (float) u.y, (float) u.z, 0);
                } else out.putVec4(0, 0, 0, 0);
            }
            out.putVec4((float) wave.x, (float) wave.y, (float) wave.z, radius);
            out.putVec4(current == null ? 0 : current.style().shaderId(), outerStyle, outerLevel, RenderSystem.getDevice().isZZeroToOne() ? 1 : 0);
            float biome = biomeStyle == null ? 0 : biomeLevel * (float) ClientConfig.BIOME_STYLE.getAsDouble();
            out.putVec4(doors.size(), biomeStyle == null ? 0 : biomeStyle.shaderId(), biome, mc.options.getEffectiveRenderDistance() * 16);
            // 世界坐标取模后传进去，笔触贴在地形上；云气另减去风吹过的路程
            double seconds = Util.getNanos() / 1e9;
            out.putVec4(wrap(eye.x), (float) eye.y, wrap(eye.z), (float) (seconds % 3600));
            out.putVec4(fogRed, fogGreen, fogBlue, 0);
            // y 是投影矩阵的纵向缩放：着色器拿它算一格方块在屏幕上有多大
            out.putVec4(wrap(WIND_X * seconds), PROJECTION.m11(), wrap(WIND_Z * seconds), 0);
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (RenderPass pass = encoder.createRenderPass(() -> "logos_dimension world style", main.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(PIPELINE);
            pass.setUniform("WorldStyle", uniforms.currentBuffer());
            pass.bindTexture("InSampler", scene.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("DepthSampler", scene.getDepthTextureView(), nearest);
            pass.bindTexture("HandDepthSampler", main.getDepthTextureView(), nearest);
            pass.draw(0, 3);
        }
        uniforms.rotate();
        passes++;
    }

    private static float wrap(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate / NOISE_PERIOD) * NOISE_PERIOD);
    }

    /** 波现在的半径，没有波时是 -1。开头慢慢起步、然后快速铺开，看得清是从脚下出来的。 */
    public static float waveRadius() {
        if (!waving) return -1;
        float t = (Util.getNanos() - waveStart) / 1e9f / WAVE_SECONDS;
        if (t >= 1 || t < 0) {
            waving = false;
            return -1;
        }
        return WAVE_RADIUS * t * t * Mth.lerp(t, 1.4f, 1);
    }

    public static @Nullable StyleRealm current() {
        return current;
    }

    /** 这一波是不是走门触发的。客户端场景用。 */
    public static boolean enteredByDoor() {
        return enteredByDoor;
    }

    /** 收到了几个区域。客户端场景用。 */
    public static int realmCount() {
        return realms.size();
    }

    /** 群系画风现在淡到几成，没有时是 0。客户端场景用。 */
    public static float biomeLevel() {
        return biomeStyle == null ? 0 : biomeLevel;
    }

    /** 跳过淡入淡出，直接到位。客户端场景截图用。 */
    public static void settleBiomeFade() {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        for (int i = 0; i < 40; i++) {
            lastFrame = Util.getNanos() - 250_000_000L;
            trackBiome(mc, camera, camera.position());
        }
    }

    /** 通道一共跑了多少次：确认附近没有门时确实不跑。客户端场景用。 */
    public static long passes() {
        return passes;
    }
}
