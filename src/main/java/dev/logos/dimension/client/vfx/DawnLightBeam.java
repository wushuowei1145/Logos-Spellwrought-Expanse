package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dawnvow.DawnvowTrial;
import dev.logos.dimension.dawnvow.SwordStoneBlock;
import dev.logos.dimension.dawnvow.SwordStoneBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 石中剑上的斜光：像丁达尔效应那样，一道若有若无的淡金色光从石座后上方的洞顶缝隙斜斜照下来，罩住剑。
 *
 * <p>光束是绕自身轴线转向镜头的长条（任何角度看都是一道光），宽的外晕、稍窄的光身，里面再有几缕细光线，
 * 像林间透下的光那样各自是一条直线、明暗缓缓变化。顶端窄、往下逐渐散开变宽；亮度在洞顶缝隙附近最高，
 * 往下越来越淡，到石座处已完全透明；光里慢慢飘落几粒微尘。
 * 贴图复用 Logos 的 {@code logos:textures/vfx/fire_beam/}（glow 取中间一行作柔和的横截面、dot 光点），颜色由顶点决定。
 * 平时很淡，破晓时（{@link DawnvowTrial#dawnGlow}）稍亮一些，提示这时剑最松。
 *
 * <p>与 Logos 的特效一样经 {@link ShaderPackOverlay#collector} 提交，排在 Logos 之后（它在这个事件里先 begin）。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class DawnLightBeam {
    private static final RenderType SHAFT = VfxParts.add("fire_beam/glow");
    private static final RenderType MOTE = VfxParts.add("fire_beam/dot");
    /** 晨光的颜色：偏暖的淡金。 */
    private static final int LIGHT = 0xFFE2A8, CORE = 0xFFF4DA;
    /** 光束长度、与竖直方向的夹角、相对剑面朝向偏转的角度。 */
    private static final double LENGTH = 12, TILT = Math.toRadians(32), YAW = Math.toRadians(28);
    /** 多远以内画（格）。 */
    private static final double RANGE = 96;
    private static final int SEGMENTS = 8, MOTES = 9, RAYS = 5;

    private DawnLightBeam() {
    }

    /** 光束落点（石冠顶上）与光源方向（指向斜上方的单位向量）。 */
    public static Vec3[] geometry(BlockPos pos, net.minecraft.core.Direction facing) {
        Vec3 end = new Vec3(pos.getX() + 0.5, pos.getY() + 0.62, pos.getZ() + 0.5);
        // 光从剑背后、偏一侧的上方来：站在洞口看，光正好斜着落在剑上
        double yaw = Math.toRadians(facing.toYRot()) + Math.PI + YAW;
        Vec3 back = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 up = back.scale(Math.sin(TILT)).add(0, Math.cos(TILT), 0).normalize();
        return new Vec3[]{end, up};
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || SwordStoneBlockEntity.LOADED.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        List<SwordStoneBlockEntity> shown = new ArrayList<>();
        for (SwordStoneBlockEntity stone : SwordStoneBlockEntity.LOADED) {
            if (stone.isRemoved() || stone.getLevel() != level) continue;
            if (stone.getBlockPos().getCenter().distanceToSqr(camera.pos) > RANGE * RANGE) continue;
            shown.add(stone);
        }
        if (shown.isEmpty()) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float time = (level.getGameTime() % 72000) + pt;
        float dawn = DawnvowTrial.dawnGlow(level, pt);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (SwordStoneBlockEntity stone : shown) {
            Vec3[] g = geometry(stone.getBlockPos(), stone.getBlockState().getValue(SwordStoneBlock.FACING));
            Vec3 end = g[0], top = source(level, end, g[1]);
            boolean crack = top.distanceTo(end) < LENGTH - 0.01;
            // 平时很淡，破晓时亮到约一倍半；很慢地呼吸
            float strength = (1 + 0.6f * dawn) * (1 + 0.08f * Mth.sin(time * 0.045f + stone.getBlockPos().getX()));
            float near = VfxParts.fade(view.cam(), end.add(g[1].scale(2)), 1.2, 3.5);
            long seed = stone.getBlockPos().asLong();
            out.submitCustomGeometry(pose, SHAFT, (p, buf) -> {
                shaft(p, buf, view, top, end, Vec3.ZERO, Vec3.ZERO, 1.0f, 4.6f, 0.08f * strength * near, LIGHT, crack);
                shaft(p, buf, view, top, end, Vec3.ZERO, Vec3.ZERO, 0.55f, 2.6f, 0.09f * strength, CORE, crack);
                rays(p, buf, view, top, end, g[1], seed, time, strength, crack);
            });
            if (crack) out.submitCustomGeometry(pose, SHAFT, (p, buf) ->
                // 洞顶透光的那道缝
                VfxParts.sprite(p, buf, view, top, 0.7f, 0, VfxParts.argb(0.16f * strength, CORE)));
            out.submitCustomGeometry(pose, MOTE, (p, buf) -> motes(p, buf, view, stone.getBlockPos(), top, end, time, strength));
        }
        pose.popPose();
    }

    /** 光从哪里来：沿光束往上，碰到洞顶就从那里开始（那一点画成透光的缝），没有遮挡时取满长。 */
    private static Vec3 source(Level level, Vec3 end, Vec3 up) {
        Vec3 from = end.add(up.scale(2.5)), to = end.add(up.scale(LENGTH));
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? to : hit.getLocation().subtract(up.scale(0.05));
    }

    /**
     * 光束里的几缕细光线：每缕在垂直于光轴的平面里偏开一点，偏移量随光束宽度按比例放大，
     * 所以在三维里仍是一条直线（与整束光同一个发散点）；各自的亮度很慢地起伏。
     */
    private static void rays(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 top, Vec3 end, Vec3 up,
                             long seed, float time, float strength, boolean crack) {
        Vec3 p1 = up.cross(new Vec3(0, 1, 0));
        p1 = p1.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : p1.normalize();
        Vec3 p2 = up.cross(p1).normalize();
        for (int i = 0; i < RAYS; i++) {
            double angle = hash(seed, 60 + i) * Mth.TWO_PI, radius = 0.12 + 0.3 * hash(seed, 70 + i);
            Vec3 dir = p1.scale(Math.cos(angle) * radius).add(p2.scale(Math.sin(angle) * radius));
            float flicker = 0.5f + 0.5f * Mth.sin(time * (0.006f + 0.006f * hash(seed, 80 + i)) + hash(seed, 90 + i) * 40f);
            float width = 0.12f + 0.16f * hash(seed, 100 + i);
            shaft(pose, buf, view, top, end, dir.scale(0.6), dir.scale(2.6), width, width * 3.5f,
                (0.03f + 0.07f * flicker) * strength, CORE, crack);
        }
    }

    /**
     * 一条光：从 top 到 end 的转向镜头长条，中心线在两端各偏开 off0、off1（都为零就是光轴本身，偏移随长度线性变化，
     * 仍是直线），宽度由顶端的 w0 散开到底端的 w1，透明度见 {@link #profile}。每个分段点只算一次朝向镜头的侧向，
     * 相邻两段共用这条边，长条的两条边因此是直的、不会在段与段之间错开。
     * 只取贴图中间一列（U 固定 0.5），V 方向是柔和的横截面，两边自然淡出。
     */
    private static void shaft(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 top, Vec3 end, Vec3 off0, Vec3 off1,
                              float w0, float w1, float alpha, int rgb, boolean crack) {
        Vec3 from = top.add(off0), along = end.add(off1).subtract(from);
        Vec3 a = from, sa = side(view, along, a, w0);
        for (int i = 1; i <= SEGMENTS; i++) {
            float k0 = (float) (i - 1) / SEGMENTS, k1 = (float) i / SEGMENTS;
            Vec3 b = from.add(along.scale(k1)), sb = side(view, along, b, Mth.lerp(k1, w0, w1));
            int ca = VfxParts.argb(alpha * profile(k0, crack), rgb), cb = VfxParts.argb(alpha * profile(k1, crack), rgb);
            VfxParts.vertex(pose, buf, a.add(sa), 0.5f, 0, ca);
            VfxParts.vertex(pose, buf, a.subtract(sa), 0.5f, 1, ca);
            VfxParts.vertex(pose, buf, b.subtract(sb), 0.5f, 1, cb);
            VfxParts.vertex(pose, buf, b.add(sb), 0.5f, 0, cb);
            a = b;
            sa = sb;
        }
    }

    /** 光轴上 at 处朝向镜头的半宽向量。 */
    private static Vec3 side(VfxParts.View view, Vec3 along, Vec3 at, float width) {
        Vec3 side = along.cross(at.subtract(view.cam()));
        return side.lengthSqr() < 1e-10 ? Vec3.ZERO : side.normalize().scale(width * 0.5);
    }

    /** 沿光束（0 顶端、1 落点）的亮度：洞顶缝隙下最亮（悬空时在顶端渐显），往下越来越淡，到落点为 0。 */
    private static float profile(float k, boolean crack) {
        return VfxParts.smooth(0f, crack ? 0.06f : 0.3f, k) * (float) Math.pow(1 - k, 1.3);
    }

    /** 光里慢慢飘落的微尘：各自沿光束下落、左右轻摆，到两端淡出。 */
    private static void motes(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, BlockPos pos, Vec3 top, Vec3 end, float time, float strength) {
        Vec3 along = end.subtract(top);
        Vec3 side = along.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
        Vec3 depth = side.cross(along).normalize();
        long base = pos.asLong();
        for (int i = 0; i < MOTES; i++) {
            float phase = hash(base, i);
            float k = (phase + time * (0.0016f + 0.0008f * hash(base, i + 31))) % 1f;
            if (k < 0.25f) continue;
            float sway = Mth.sin(time * 0.02f + phase * 40f) * 0.35f, drift = Mth.cos(time * 0.017f + phase * 23f) * 0.3f;
            Vec3 at = top.add(along.scale(k)).add(side.scale(sway * (0.4 + k))).add(depth.scale(drift * (0.4 + k)));
            float a = VfxParts.smooth(0.25f, 0.4f, k) * (1 - VfxParts.smooth(0.55f, 0.95f, k));
            VfxParts.sprite(pose, buf, view, at, 0.03f + 0.02f * hash(base, i + 7), 0, VfxParts.argb(0.35f * a * strength, CORE));
        }
    }

    private static float hash(long seed, int i) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        return ((h >>> 40) & 0xFFFFFF) / (float) 0x1000000;
    }
}
