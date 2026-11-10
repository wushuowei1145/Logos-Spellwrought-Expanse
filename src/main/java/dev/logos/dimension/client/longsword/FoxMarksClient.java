package dev.logos.dimension.client.longsword;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.ryuka.FoxMarks;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/**
 * 狐火印的样子（{@link FoxMarks}）：目标头顶绕着几点淡粉的狐火，一枚印一点，越接近攒满转得越快、越亮；
 * 爆开时一圈往外散。隔 {@link FoxMarks#LIFE} 刻没再添印就淡掉（和服务端一样的规矩，不另发包）。
 * 爆开之后能处决的那 {@link FoxMarks#WINDOW} 刻：身体中部绕着一圈五点更大更亮的狐火，转得快、一闪一闪，
 * 和平时的狐火缠身分得开；处决开始（窗口用掉）或到点就没了。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class FoxMarksClient {
    private static final RenderType GLOW = VfxParts.add("heal/glow");
    /** 爆开那一圈往外散多久（刻）。 */
    private static final float BURST_LIFE = 10;

    private record Marks(int count, float since) {}
    private static final Map<Integer, Marks> MARKS = new HashMap<>();
    private static final Map<Integer, Float> BURSTS = new HashMap<>();
    /** 能处决的目标，从哪一刻起（窗口长 {@link FoxMarks#WINDOW}）。 */
    private static final Map<Integer, Float> OPEN = new HashMap<>();

    private FoxMarksClient() {}

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(FoxMarks.Payload.TYPE, (payload, context) -> receive(payload));
    }

    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        MARKS.clear();
        BURSTS.clear();
        OPEN.clear();
    }

    private static void receive(FoxMarks.Payload payload) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        float now = level.getGameTime();
        if (payload.count() <= 0) MARKS.remove(payload.entity());
        else MARKS.put(payload.entity(), new Marks(payload.count(), now));
        if (payload.burst()) {
            BURSTS.put(payload.entity(), now);
            OPEN.put(payload.entity(), now);
        }
        if (payload.closed()) OPEN.remove(payload.entity());
    }

    /** {@code entity} 现在看起来能不能处决（客户端场景检查用）。 */
    public static boolean executable(int entity) {
        var level = Minecraft.getInstance().level;
        Float since = OPEN.get(entity);
        return since != null && level != null && level.getGameTime() - since < FoxMarks.WINDOW;
    }

    /** {@code entity} 头上现在看得见几枚印（客户端场景检查用）。 */
    public static int count(int entity) {
        var level = Minecraft.getInstance().level;
        Marks m = MARKS.get(entity);
        return m == null || level == null || level.getGameTime() - m.since > FoxMarks.LIFE ? 0 : m.count;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null || MARKS.isEmpty() && BURSTS.isEmpty() && OPEN.isEmpty()) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float now = level.getGameTime() + pt;
        MARKS.values().removeIf(m -> now - m.since > FoxMarks.LIFE + 10);
        BURSTS.values().removeIf(t -> now - t > BURST_LIFE);
        OPEN.values().removeIf(t -> now - t > FoxMarks.WINDOW);
        var camera = event.getLevelRenderState().cameraRenderState;
        VfxParts.View view = VfxParts.View.of(camera);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        FoxfireFlame flame = FoxfireFlame.SHIRAUME;
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (var entry : MARKS.entrySet()) {
            Entity e = level.getEntity(entry.getKey());
            if (e == null || e.isInvisible() || e == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) continue;
            Marks m = entry.getValue();
            float age = now - m.since;
            // 最后两秒慢慢淡掉
            float fade = Math.clamp((FoxMarks.LIFE - age) / 40f, 0, 1) * Math.min(1, age / 3f + .3f);
            if (fade <= 0) continue;
            Vec3 head = e.getPosition(pt).add(0, e.getBbHeight() + .35, 0);
            float orbit = e.getBbWidth() * .45f + .25f;
            float full = m.count / (float) FoxMarks.FULL;
            int count = m.count;
            out.submitCustomGeometry(pose, GLOW, (p, buf) -> {
                for (int i = 0; i < count; i++) {
                    double a = now * (.12 + .12 * full) + i * Math.PI * 2 / FoxMarks.FULL;
                    Vec3 w = head.add(Math.cos(a) * orbit, .06 * Math.sin(now * .4 + i), Math.sin(a) * orbit);
                    float pulse = .85f + .15f * (float) Math.sin(now * (.5 + full) + i);
                    VfxParts.sprite(p, buf, view, w, .1f + .04f * full, 0, VfxParts.argb(fade * pulse, flame.bright));
                    VfxParts.sprite(p, buf, view, w, .24f + .08f * full, 0, VfxParts.argb(fade * .5f * pulse, flame.core));
                }
            });
        }
        for (var entry : OPEN.entrySet()) {
            Entity e = level.getEntity(entry.getKey());
            if (e == null || e.isInvisible() || e == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) continue;
            float age = now - entry.getValue();
            // 爆开的那一圈散完才出来，最后一秒淡掉
            float fade = Math.clamp((age - 6) / 6f, 0, 1) * Math.clamp((FoxMarks.WINDOW - age) / 20f, 0, 1);
            if (fade <= 0) continue;
            Vec3 center = e.getPosition(pt).add(0, e.getBbHeight() * .55, 0);
            float orbit = e.getBbWidth() * .5f + .45f;
            out.submitCustomGeometry(pose, GLOW, (p, buf) -> {
                for (int i = 0; i < FoxMarks.FULL; i++) {
                    double a = now * .32 + i * Math.PI * 2 / FoxMarks.FULL;
                    Vec3 w = center.add(Math.cos(a) * orbit, .12 * Math.sin(now * .5 + i * 1.3), Math.sin(a) * orbit);
                    float pulse = .75f + .25f * (float) Math.sin(now * .9 + i);
                    VfxParts.sprite(p, buf, view, w, .16f, 0, VfxParts.argb(fade * pulse, 0xFFFFFF));
                    VfxParts.sprite(p, buf, view, w, .4f, 0, VfxParts.argb(fade * .6f * pulse, flame.core));
                }
            });
        }
        for (var entry : BURSTS.entrySet()) {
            Entity e = level.getEntity(entry.getKey());
            if (e == null) continue;
            float k = (now - entry.getValue()) / BURST_LIFE;
            Vec3 center = e.getPosition(pt).add(0, e.getBbHeight() * .55, 0);
            float radius = (e.getBbWidth() * .5f + .4f) * (.4f + 1.4f * k);
            out.submitCustomGeometry(pose, GLOW, (p, buf) -> {
                for (int i = 0; i < FoxMarks.FULL * 2; i++) {
                    double a = i * Math.PI * 2 / (FoxMarks.FULL * 2);
                    Vec3 w = center.add(Math.cos(a) * radius, (i % 2 == 0 ? .15 : -.15) * k, Math.sin(a) * radius);
                    VfxParts.sprite(p, buf, view, w, .3f * (1 - k) + .08f, 0, VfxParts.argb((1 - k) * .9f, flame.bright));
                }
            });
        }
        pose.popPose();
    }
}
