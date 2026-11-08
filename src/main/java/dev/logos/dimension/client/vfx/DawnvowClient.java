package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dawnvow.DawnvowPayload;
import java.util.ArrayList;
import java.util.Collection;
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
    /** 巨型剑气放出后剑身的长金光保留到哪一刻。 */
    private static final Map<Integer, Long> BLESSED = new HashMap<>();
    /** 金光最后这么多刻淡去。 */
    private static final int BLESS_FADE = 40;
    private static Level shown;
    /** 客户端场景截图用：特效停在这一刻（游戏刻，可带小数）；负数时照常走。 */
    private static double pinned = -1;

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
                WAVES.add(new ExcaliburWave(p.entity(), p.from(), p.dir(), p.path(), p.tick()));
            }
            case DawnvowPayload.BLESS -> BLESSED.put(p.entity(), p.tick());
            default -> {}
        }
    }

    private static void clear(Level level) {
        AURAS.clear();
        WAVES.clear();
        BLESSED.clear();
        shown = level;
        pinned = -1;
    }

    /**
     * 这位玩家的剑身金光（{@link DawnvowBlade#encode} 后的值）：大型蓄力 12 秒起亮起，放出巨型剑气后按蓄满的样子保留 60 秒，
     * 最后两秒淡去；都不是时为 0。
     */
    public static float blade(int entity, float partialTick) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return 0;
        double now = now(partialTick);
        Long until = BLESSED.get(entity);
        if (until != null && now < until) return DawnvowBlade.encode(VfxParts.smooth(0, BLESS_FADE, (float) (until - now)), true);
        DawnvowAura aura = AURAS.get(entity);
        if (aura == null) return 0;
        return DawnvowBlade.encode(aura.blade(now), aura.full >= 0 && now >= aura.full && aura.ended < 0);
    }

    /** 正在画的大型蓄力与巨型剑气（客户端场景检查用）。 */
    public static int auras() { return AURAS.size(); }

    public static int waves() { return WAVES.size(); }

    /** 有没有要画的大型蓄力或巨型剑气（没有时后期一个通道都不跑）。 */
    static boolean busy() { return !AURAS.isEmpty() || !WAVES.isEmpty(); }

    static Collection<DawnvowAura> auraList() { return AURAS.values(); }

    static List<ExcaliburWave> waveList() { return WAVES; }

    /**
     * 客户端场景用：让曦誓的特效和后期停在 {@code time} 这一刻（游戏刻，可带小数），截图就能准确地停在时间轴的某一刻；
     * 停着时不再往前推，也不丢掉已经画完的。传负数放开。
     */
    public static void pin(double time) { pinned = time; }

    static boolean pinned() { return pinned >= 0; }

    /** 特效用的时钟：游戏刻加帧间进度；停住时是停住的那一刻。 */
    static double now(float partialTick) {
        if (pinned >= 0) return pinned;
        Level level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getGameTime() + partialTick;
    }

    /** 这一帧的刻内进度；停住时为 0。 */
    static float partial() {
        return pinned >= 0 ? 0 : Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /** 某道巨型剑气到头的游戏刻（客户端场景对时间轴用）；没有时为 -1。 */
    public static double arrival(int entity) {
        for (ExcaliburWave w : WAVES) if (w.entity() == entity) return w.arrive();
        return -1;
    }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != shown) clear(mc.level);
        if (mc.level == null || mc.isPaused() || pinned >= 0) return;
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
        BLESSED.values().removeIf(until -> until <= now);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || AURAS.isEmpty() && WAVES.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        float pt = partial();
        double now = now(pt);
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
