package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dawnvow.DawnvowPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
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
 * 曦誓大型蓄力与巨型剑气在客户端的总入口：收服务端的 {@link DawnvowPayload}，每刻推进、每帧画。
 * 身上的光与四周的光点见 {@link DawnvowAura}，剑身金光见 {@link DawnvowBlade}，巨型剑气见 {@link ExcaliburWave}。
 * 普通蓄力斩的剑气是 Logos 的物理游脉，由 Logos 自己画。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class DawnvowClient {
    private static final Map<Integer, DawnvowAura> AURAS = new HashMap<>();
    private static final List<ExcaliburWave> WAVES = new ArrayList<>();
    private static Level shown;

    private DawnvowClient() {}

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(DawnvowPayload.TYPE, (p, context) -> receive(p));
    }

    /** 客户端场景也直接调用它重放一段。 */
    public static void receive(DawnvowPayload p) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        if (level != shown) clear(level);
        long now = level.getGameTime();
        DawnvowAura aura = AURAS.get(p.entity());
        switch (p.kind()) {
            case DawnvowPayload.GRAND -> {
                if (aura == null || aura.start != p.tick() || aura.ended >= 0) AURAS.put(p.entity(), new DawnvowAura(p.entity(), p.tick()));
            }
            case DawnvowPayload.FULL -> { if (aura != null) aura.full = now; }
            case DawnvowPayload.STOP -> { if (aura != null && aura.ended < 0) aura.ended = now; }
            case DawnvowPayload.RELEASE -> {
                if (aura != null && aura.ended < 0) aura.ended = now;
                WAVES.add(new ExcaliburWave(p.entity(), p.from(), p.dir(), p.length(), p.tick()));
            }
            default -> {}
        }
    }

    private static void clear(Level level) {
        AURAS.clear();
        WAVES.clear();
        shown = level;
    }

    /** 这位玩家的剑身金光（{@link DawnvowBlade#encode} 后的值），没在大型蓄力时为 0。 */
    public static float blade(int entity, float partialTick) {
        DawnvowAura aura = AURAS.get(entity);
        Level level = Minecraft.getInstance().level;
        if (aura == null || level == null) return 0;
        double now = level.getGameTime() + partialTick;
        return DawnvowBlade.encode(aura.blade(now), aura.full >= 0 && now >= aura.full && aura.ended < 0);
    }

    /** 正在画的大型蓄力与巨型剑气（客户端场景检查用）。 */
    public static int auras() { return AURAS.size(); }

    public static int waves() { return WAVES.size(); }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != shown) clear(mc.level);
        if (mc.level == null || mc.isPaused()) return;
        long now = mc.level.getGameTime();
        AURAS.values().removeIf(aura -> {
            Entity body = mc.level.getEntity(aura.entity);
            if (body == null) {
                if (aura.ended < 0) aura.ended = now;
                return true;
            }
            return !aura.tick(mc.level, body, now);
        });
        WAVES.removeIf(w -> !w.tick(mc.level, now));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || AURAS.isEmpty() && WAVES.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double now = mc.level.getGameTime() + pt;
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (DawnvowAura aura : AURAS.values()) {
            Entity body = mc.level.getEntity(aura.entity);
            if (body == null) continue;
            Vec3 feet = body.getPosition(pt);
            float height = body.getBbHeight();
            boolean self = body == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
            float near = self ? 0 : VfxParts.fade(view.cam(), feet.add(0, height * .55, 0), 1, 3);
            aura.submit(pose, out, view, feet, height, now, pt, near);
        }
        for (ExcaliburWave wave : WAVES) wave.submit(pose, out, view, now);
        pose.popPose();
    }
}
