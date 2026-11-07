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
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * 本机的区域画风：判断镜头在哪个区域，播进出时的球形波，再用一个全屏通道把画风画上去。
 * 只用「半透明之前的深度」和「最终画面的颜色」，不往世界里画东西，所以和 Sodium、光影包都走同一条路。
 * 附近没有门、也不在区域里时整个通道都不跑，没有开销。
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
        UNIFORM_SIZE = size.putVec4().putVec4().putVec4().get();
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
        if (current == null && !waving && doorsNear(eye).isEmpty()) return;

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
            out.putVec4(doors.size(), 0, 0, 0);
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

    /** 通道一共跑了多少次：确认附近没有门时确实不跑。客户端场景用。 */
    public static long passes() {
        return passes;
    }
}
