package dev.logos.dimension.client.vfx;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.client.ShaderPackOverlay;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.net.ManaTornadoPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/**
 * 客户端上正在卷的魔力龙卷：收包后建立，同一个实体身上只留一个（再收到就换成新的），每刻推进，每帧绘制，
 * 散完或换世界时丢弃。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ManaTornados {
    private static final int CAP = 8;
    private static final List<ManaTornadoVisual> LIVE = new ArrayList<>();
    private static Level shown;
    private static long seed;

    private ManaTornados() {}

    /**
     * 在本客户端卷起一个，不经过服务端（客户端场景也用它）。{@code entity} 为 -1 时停在 {@code at}；
     * {@code ticks} 为负一直卷，为 0 则收掉这个实体身上的。
     */
    public static void spawn(int entity, Vec3 at, int ticks, float scale, int cycle) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        if (level != shown) LIVE.clear();
        shown = level;
        if (ticks == 0) {
            for (ManaTornadoVisual v : LIVE) if (entity >= 0 && v.entity() == entity) v.release();
            return;
        }
        if (entity >= 0) LIVE.removeIf(v -> v.entity() == entity);
        if (LIVE.size() >= CAP) LIVE.removeFirst();
        LIVE.add(new ManaTornadoVisual(level, entity, at, ticks, scale, cycle, ++seed * 0x9E3779B97F4A7C15L));
    }

    /** 正在卷的个数（客户端场景检查用）。 */
    public static int count() { return LIVE.size(); }

    public static void clear() { LIVE.clear(); }

    @SubscribeEvent static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(ManaTornadoPayload.TYPE, (p, context) -> spawn(p.entity(), new Vec3(p.x(), p.y(), p.z()), p.ticks(), p.scale(), p.cycle()));
    }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != shown) { LIVE.clear(); shown = mc.level; }
        if (mc.isPaused()) return;
        LIVE.removeIf(v -> !v.tick());
    }

    // Logos 在同一事件里先调用 ShaderPackOverlay.begin()；附属排在最后，叠加收集器已经就绪
    @SubscribeEvent(priority = EventPriority.LOWEST) static void submit(SubmitCustomGeometryEvent event) {
        if (LIVE.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (ManaTornadoVisual v : LIVE) v.submit(view, pt, pose, out, VfxParts.layered(raw, out));
        pose.popPose();
    }
}
