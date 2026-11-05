package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.gate.GateIgnitionPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/**
 * 门点亮的特效。素材全用 Logos 现成的贴图（光环、旋涡、辉光、光痕都是白色的亮度图），颜色由顶点给成门的蓝青色。
 *
 * <ul>
 *   <li>0–24 刻（门洞正在填）：光点从四周螺旋汇向门面；门洞内两圈反向旋转的光环和中心的旋涡越来越亮；脚下一圈尖刺光环慢慢转起来。</li>
 *   <li>第 24 刻（填满）：门洞中心一道闪光，一束光柱从门里冲上天，贴地荡开两圈冲击环，光痕向上飞散。</li>
 *   <li>之后光柱收细，光点从门面慢慢飘起，到第 84 刻全部淡去。</li>
 * </ul>
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class GateIgnition {
    private static final RenderType RING = VfxParts.add("force/ring"), SPIKY = VfxParts.add("fire_beam/spiky_ring"), SWIRL = VfxParts.add("heal/swirl"),
            GLOW = VfxParts.add("heal/glow"), FLARE = VfxParts.add("fire_beam/flare"), SHAFT = VfxParts.add("fire_beam/glow"),
            STREAK = VfxParts.add("force/streak"), DOT = VfxParts.add("force/dot");
    private static final int DEEP = 0x1F7CF0, BRIGHT = 0x5CCBFF, WHITE = 0xE6FBFF;
    /** 填满的时刻和整段时长（刻）；要和 {@code GateStoneBlockEntity.FILL_TICKS} 大致对上。 */
    private static final float FULL = 24, DURATION = 84;
    private static final int CAP = 8, MOTES = 110, STREAKS = 44, RISERS = 40;
    /** 光柱多高（格）。 */
    private static final float SHAFT_HEIGHT = 56;
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final List<Live> LIVE = new ArrayList<>();
    private static Level shown;

    private record Live(Vec3 center, Vec3 along, Vec3 normal, double bottom, float halfWidth, float halfHeight, long start) {}

    private GateIgnition() {}

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(GateIgnitionPayload.TYPE, (p, context) -> receive(p));
    }

    /** 客户端场景也直接调它重放一段。 */
    public static void receive(GateIgnitionPayload p) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        if (level != shown) LIVE.clear();
        shown = level;
        if (LIVE.size() >= CAP) LIVE.removeFirst();
        Vec3 along = p.alongX() ? new Vec3(1, 0, 0) : new Vec3(0, 0, 1), normal = p.alongX() ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);
        LIVE.add(new Live(new Vec3(p.x(), p.y(), p.z()), along, normal, p.bottom(), p.halfWidth(), p.halfHeight(), level.getGameTime()));
    }

    /** 正在播放几个（客户端场景用来检查）。 */
    public static int count() { return LIVE.size(); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (LIVE.isEmpty()) return;
        if (level != shown) { LIVE.clear(); return; }
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float now = level.getGameTime() + pt;
        LIVE.removeIf(l -> now - l.start > DURATION);
        if (LIVE.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (Live live : LIVE) {
            float t = now - live.start;
            out.submitCustomGeometry(pose, RING, (p, buf) -> rings(p, buf, live, t));
            out.submitCustomGeometry(pose, SPIKY, (p, buf) -> rune(p, buf, live, t));
            out.submitCustomGeometry(pose, SWIRL, (p, buf) -> swirl(p, buf, live, t));
            out.submitCustomGeometry(pose, GLOW, (p, buf) -> glow(p, buf, view, live, t));
            out.submitCustomGeometry(pose, FLARE, (p, buf) -> flare(p, buf, view, live, t));
            out.submitCustomGeometry(pose, SHAFT, (p, buf) -> shaft(p, buf, view, live, t));
            out.submitCustomGeometry(pose, DOT, (p, buf) -> motes(p, buf, view, live, t));
            out.submitCustomGeometry(pose, STREAK, (p, buf) -> streaks(p, buf, view, live, t));
        }
        pose.popPose();
    }

    private static float size(Live l) { return Math.max(l.halfWidth, l.halfHeight); }
    /** 门洞里放得下的半径：光环画在门框以内，不会被树冠挡住。 */
    private static float inner(Live l) { return Math.min(l.halfWidth, l.halfHeight); }
    private static Vec3 feet(Live l) { return new Vec3(l.center.x, l.bottom + .07, l.center.z); }

    /** 门洞内两圈反向旋转的光环（门面两侧各画一层，从哪边看都有），和填满时贴地荡开的两圈冲击环。 */
    private static void rings(PoseStack.Pose pose, VertexConsumer buf, Live l, float t) {
        float in = VfxParts.smooth(0, 14, t) * (1 - VfxParts.smooth(FULL + 4, FULL + 24, t));
        if (in > 0) for (float side : new float[]{-.12f, .12f}) {
            Vec3 c = l.center.add(l.normal.scale(side));
            float breathe = 1 + .05f * Mth.sin(t * .35f);
            VfxParts.flat(pose, buf, c, l.along, UP, inner(l) * 1.02f * breathe, t * .11f, VfxParts.argb(.95f * in, BRIGHT));
            VfxParts.flat(pose, buf, c, l.along, UP, inner(l) * .68f * breathe, -t * .16f, VfxParts.argb(.8f * in, WHITE));
        }
        if (t < FULL) return;
        float after = t - FULL, k = 1 - (1 - VfxParts.smooth(0, 34, after)) * (1 - VfxParts.smooth(0, 34, after)), fade = 1 - VfxParts.smooth(6, 38, after);
        VfxParts.ground(pose, buf, feet(l), 2 + k * (size(l) * 3.2f + 8), after * .02f, VfxParts.argb(.95f * fade, BRIGHT));
        VfxParts.ground(pose, buf, feet(l), 1 + k * (size(l) * 2f + 4), -after * .03f, VfxParts.argb(.8f * fade, DEEP));
    }

    /** 脚下慢慢转起来的尖刺光环。 */
    private static void rune(PoseStack.Pose pose, VertexConsumer buf, Live l, float t) {
        float in = VfxParts.smooth(0, 18, t) * (1 - VfxParts.smooth(FULL + 14, FULL + 44, t));
        if (in <= 0) return;
        VfxParts.ground(pose, buf, feet(l), size(l) * 1.35f, t * .05f, VfxParts.argb(.85f * in, BRIGHT));
        VfxParts.ground(pose, buf, feet(l).add(0, .02, 0), size(l) * .9f, -t * .08f, VfxParts.argb(.6f * in, DEEP));
    }

    /** 门面中心的旋涡，填的时候越来越亮，填满后张开散掉。 */
    private static void swirl(PoseStack.Pose pose, VertexConsumer buf, Live l, float t) {
        float in = VfxParts.smooth(2, FULL, t) * (1 - VfxParts.smooth(FULL + 2, FULL + 22, t));
        if (in <= 0) return;
        float r = inner(l) * (1f + .9f * VfxParts.smooth(FULL, FULL + 22, t));
        for (float side : new float[]{-.08f, .08f}) {
            Vec3 c = l.center.add(l.normal.scale(side));
            VfxParts.flat(pose, buf, c, l.along, UP, r, t * .24f, VfxParts.argb(.95f * in, BRIGHT));
            VfxParts.flat(pose, buf, c, l.along, UP, r * .7f, -t * .3f, VfxParts.argb(.75f * in, WHITE));
        }
    }

    /** 整段门洞的辉光，填的时候慢慢亮，填满时最亮。 */
    private static void glow(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Live l, float t) {
        float base = VfxParts.smooth(0, FULL, t) * (1 - VfxParts.smooth(FULL + 8, DURATION, t));
        VfxParts.sprite(pose, buf, view, l.center, size(l) * 2.1f, 0, VfxParts.argb(.5f * base, BRIGHT));
        if (t < FULL) return;
        float after = t - FULL, spike = VfxParts.smooth(0, 2, after) * (1 - VfxParts.smooth(2, 20, after));
        VfxParts.sprite(pose, buf, view, l.center, size(l) * (2.4f + after * .1f), 0, VfxParts.argb(.95f * spike, WHITE));
    }

    /** 填满那一下的星芒。 */
    private static void flare(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Live l, float t) {
        if (t < FULL) return;
        float after = t - FULL, spike = VfxParts.smooth(0, 1.5f, after) * (1 - VfxParts.smooth(1.5f, 16, after));
        if (spike <= 0) return;
        VfxParts.sprite(pose, buf, view, l.center, size(l) * 3.4f, after * .02f, VfxParts.argb(spike, WHITE));
        VfxParts.sprite(pose, buf, view, l.center, size(l) * 5f, -after * .015f, VfxParts.argb(.7f * spike, BRIGHT));
    }

    /** 光柱：填满时几刻内冲上去，然后收细淡去。 */
    private static void shaft(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Live l, float t) {
        if (t < FULL) return;
        float after = t - FULL, rise = 1 - (1 - VfxParts.smooth(0, 7, after)) * (1 - VfxParts.smooth(0, 7, after));
        float fade = VfxParts.smooth(0, 2, after) * (1 - VfxParts.smooth(10, 48, after)), thin = 1 - .75f * VfxParts.smooth(6, 48, after);
        if (fade <= 0) return;
        Vec3 from = feet(l).add(0, -2, 0), to = from.add(0, SHAFT_HEIGHT * rise, 0);
        float width = inner(l) * 2.3f * thin;
        VfxParts.streak(pose, buf, view, from, to, width * 2.2f, VfxParts.argb(.55f * fade, DEEP));
        VfxParts.streak(pose, buf, view, from, to, width, VfxParts.argb(.8f * fade, BRIGHT));
        VfxParts.streak(pose, buf, view, from, to, width * .4f, VfxParts.argb(.95f * fade, WHITE));
    }

    /** 光点：填的时候从四周螺旋汇向门面，边走边亮；填满后从门面慢慢飘起。 */
    private static void motes(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Live l, float t) {
        if (t <= FULL + 8) for (int i = 0; i < MOTES; i++) {
            float delay = hash(i, 1) * (FULL - 6), life = 10 + hash(i, 2) * 10, k = (t - delay) / life;
            if (k <= 0 || k >= 1) continue;
            float angle = hash(i, 3) * Mth.TWO_PI + k * 2.6f, reach = (1 - k * k) * (size(l) + 3 + hash(i, 4) * 4);
            float side = (hash(i, 5) > .5f ? 1 : -1) * (1 - k) * (1.5f + hash(i, 6) * 4);
            Vec3 at = l.center.add(l.along.scale(Mth.cos(angle) * reach)).add(0, Mth.sin(angle) * reach, 0).add(l.normal.scale(side));
            float alpha = VfxParts.smooth(0, .25f, k) * (1 - VfxParts.smooth(.88f, 1, k));
            VfxParts.sprite(pose, buf, view, at, .14f + .2f * hash(i, 7), 0, VfxParts.argb(alpha, i % 3 == 0 ? WHITE : BRIGHT));
        }
        if (t < FULL) return;
        float after = t - FULL;
        for (int i = 0; i < RISERS; i++) {
            float delay = hash(i, 20) * 26, life = 20 + hash(i, 21) * 20, k = (after - delay) / life;
            if (k <= 0 || k >= 1) continue;
            float angle = hash(i, 22) * Mth.TWO_PI, reach = hash(i, 23);
            Vec3 at = l.center.add(l.along.scale(Mth.cos(angle) * reach * l.halfWidth)).add(0, Mth.sin(angle) * reach * l.halfHeight + k * (3 + hash(i, 24) * 4), 0)
                    .add(l.normal.scale((hash(i, 25) - .5f) * 2.4f * k));
            float alpha = VfxParts.smooth(0, .2f, k) * (1 - VfxParts.smooth(.5f, 1, k));
            VfxParts.sprite(pose, buf, view, at, .1f + .12f * hash(i, 26), 0, VfxParts.argb(.9f * alpha, BRIGHT));
        }
    }

    /** 填满后光痕从门洞向上飞散。 */
    private static void streaks(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Live l, float t) {
        if (t < FULL) return;
        float after = t - FULL;
        for (int i = 0; i < STREAKS; i++) {
            float delay = hash(i, 8) * 10, life = 14 + hash(i, 9) * 18, k = (after - delay) / life;
            if (k <= 0 || k >= 1) continue;
            float angle = hash(i, 10) * Mth.TWO_PI;
            Vec3 from = l.center.add(l.along.scale(Mth.cos(angle) * l.halfWidth)).add(0, Mth.sin(angle) * l.halfHeight, 0)
                    .add(l.normal.scale((hash(i, 11) - .5f) * 1.2f));
            float rise = (1 - (1 - k) * (1 - k)) * (10 + hash(i, 12) * 14);
            Vec3 head = from.add(0, rise, 0), tail = from.add(0, Math.max(0, rise - 2.2f - hash(i, 13) * 2), 0);
            float alpha = VfxParts.smooth(0, .15f, k) * (1 - VfxParts.smooth(.55f, 1, k));
            VfxParts.streak(pose, buf, view, tail, head, .2f + .16f * hash(i, 14), VfxParts.argb(alpha, i % 4 == 0 ? WHITE : BRIGHT));
        }
    }

    private static float hash(int i, int salt) {
        long z = (i * 0x9E3779B97F4A7C15L) ^ (salt * 0xBF58476D1CE4E5B9L);
        z = (z ^ (z >>> 30)) * 0x94D049BB133111EBL;
        return ((z ^ (z >>> 31)) >>> 40) / (float) (1L << 24);
    }
}
