package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.longsword.LongswordPayload;
import dev.logos.dimension.longsword.LongswordStance;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 长枪连刺的枪尖残影：每刺到头的那一刻（动作里的 {@code tip_fx} 标记），在枪尖放出一枚枪头形状的半透明光影，
 * 箭头对准这一刺的方向，继续往前冲出一小段、很快淡去。
 *
 * <p>只在客户端：每个客户端按同步来的招式自己取样，看得到这名玩家的人都看得到，不需要额外的网络包。
 * 位置取第三人称骨架算出的枪尖（与玩家渲染层同一串变换）；本机第一人称时改用第一人称骨架，经镜头换到世界坐标。
 * 贴图 {@code textures/vfx/spear_tip_ghost.png} 由 tools/import_oath_spear.py 从枪头方块渲染，枪尖朝贴图右方（+U）。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class SpearThrustGhosts {
    public static final String MARKER = "tip_fx";
    private static final RenderType GHOST = VfxParts.blend(LogosDimension.id("textures/vfx/spear_tip_ghost.png"));
    /** 叠加一层发光（相加混合），在天空、雪地这类亮背景上也看得清。 */
    private static final RenderType GLOW = VfxParts.add(LogosDimension.id("textures/vfx/spear_tip_ghost.png"));
    private static final int GLOW_COLOR = 0x9FD8FF;
    /** 枪尖在武器网格里的位置。 */
    private static final Vector4f TIP = new Vector4f(0, 27.4f, 0, 1);
    /** 存在多久（刻）、往前冲多远、残影多长多宽（格）。 */
    private static final float LIFE = 4.5f, TRAVEL = 1.4f, LENGTH = 1.2f, WIDTH = 0.64f;
    private static final int MAX = 64;

    /** 一枚残影：枪尖位置、方向、出现时刻（游戏刻，含小数）。 */
    public record Ghost(Vec3 tip, Vec3 dir, double born) {}
    private static final List<Ghost> GHOSTS = new ArrayList<>();

    private SpearThrustGhosts() {}

    /** 当前还在的残影（UI 场景检查用）。 */
    public static List<Ghost> ghosts() { return List.copyOf(GHOSTS); }

    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { GHOSTS.clear(); }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) { GHOSTS.clear(); return; }
        double now = mc.level.getGameTime();
        GHOSTS.removeIf(g -> now - g.born > LIFE + 1);
        for (Player player : mc.level.players()) {
            var p = LongswordClient.pose(player.getUUID());
            if (p == null || player.isInvisible()) continue;
            var rig = LongswordRig.get(p.variant(), false);
            if (rig == null) continue;
            float[] marks = rig.markers(p.clip(), MARKER);
            if (marks.length == 0) continue;
            double length = rig.length(p.clip());
            // 这一刻跨过的标记：上一刻到这一刻之间的动作时间（刚换招时从 0 算起）。
            double t1 = Moveset.playbackSeconds(p.playback(), length, now - p.started(), p.speed(), p.offset());
            double t0 = now - 1 < p.started() ? -1 : Moveset.playbackSeconds(p.playback(), length, now - 1 - p.started(), p.speed(), p.offset());
            for (float m : marks) if (m > t0 && m <= t1) spawn(mc, player, p, m, p.started() + m * 20 / p.speed());
        }
        while (GHOSTS.size() > MAX) GHOSTS.removeFirst();
    }

    private static void spawn(Minecraft mc, Player player, LongswordPayload p, float time, double born) {
        boolean firstPerson = player == mc.player && mc.options.getCameraType().isFirstPerson();
        var rig = LongswordRig.get(p.variant(), firstPerson);
        var pose = rig == null ? null : rig.pose(p.clip(), time);
        if (pose == null) return;
        var weapon = rig.sample(pose).weapon();
        Matrix4f toWorld;
        boolean left = player.getMainArm() == HumanoidArm.LEFT;
        if (firstPerson) {
            // 与 LongswordClient.hand 的第一人称变换相同（不计视角摇晃），再经镜头从视图空间换到世界。
            var camera = mc.gameRenderer.getMainCamera();
            var cam = camera.position();
            toWorld = new Matrix4f().translation((float) cam.x, (float) cam.y, (float) cam.z).rotate(camera.rotation())
                    .rotate(Axis.XP.rotationDegrees(2)).scale(1 / 16f).translate(0, -29, .6f);
            if (left) toWorld.scale(-1, 1, 1);
        } else {
            // 与玩家渲染（转身、翻转、0.9375 缩放、-1.501 下移）加长剑渲染层（LongswordClient.SwordLayer）同一串变换。
            toWorld = new Matrix4f().translation((float) player.getX(), (float) player.getY(), (float) player.getZ())
                    .rotate(Axis.YP.rotationDegrees(180 - player.yBodyRot)).scale(-1, -1, 1).scale(0.9375f).translate(0, -1.501f, 0)
                    .mul(LongswordStance.base(player.isCrouching(), 1 / 16f)).translate(0, 1.5f, 0)
                    .scale(left ? 1 / 16f : -1 / 16f, -1 / 16f, 1 / 16f);
        }
        var m = toWorld.mul(weapon);
        var tip = m.transform(new Vector4f(TIP));
        var dir = m.transformDirection(new Vector3f(0, 1, 0)).normalize();
        GHOSTS.add(new Ghost(new Vec3(tip.x, tip.y, tip.z), new Vec3(dir.x, dir.y, dir.z), born));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || GHOSTS.isEmpty()) return;
        var camera = event.getLevelRenderState().cameraRenderState;
        double now = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        List<Ghost> live = new ArrayList<>();
        for (var g : GHOSTS) if (now >= g.born && now - g.born < LIFE) live.add(g);
        if (live.isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        out.submitCustomGeometry(pose, GHOST, (p, buf) -> {
            for (var g : live) ghost(p, buf, view, g, (float) ((now - g.born) / LIFE), 1, 0xFFFFFF);
        });
        out.submitCustomGeometry(pose, GLOW, (p, buf) -> {
            for (var g : live) ghost(p, buf, view, g, (float) ((now - g.born) / LIFE), 0.7f, GLOW_COLOR);
        });
        pose.popPose();
    }

    /** 一枚残影，k 为 0–1 的寿命进度：冲出去先快后慢，前三分之一保持实心，之后边走边拉长、变窄、淡出。 */
    private static void ghost(PoseStack.Pose p, com.mojang.blaze3d.vertex.VertexConsumer buf, VfxParts.View view, Ghost g, float k,
                              float strength, int rgb) {
        float travel = TRAVEL * (1 - (1 - k) * (1 - k));
        Vec3 head = g.tip.add(g.dir.scale(travel + LENGTH * 0.3));
        Vec3 tail = head.subtract(g.dir.scale(LENGTH * (1 + 0.5f * k)));
        float fade = k < 0.35f ? 1 : (float) Math.pow((1 - k) / 0.65f, 1.3);
        VfxParts.streak(p, buf, view, tail, head, WIDTH * (1 - 0.3f * k), 0, 0, 1, 1, VfxParts.argb(strength * fade, rgb));
    }
}
