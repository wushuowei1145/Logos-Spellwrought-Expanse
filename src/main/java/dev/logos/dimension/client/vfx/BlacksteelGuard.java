package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ChargeStyle;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.longsword.LongswordPayload;
import dev.logos.dimension.longsword.SwordCasting;
import dev.logos.lang.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * 黑钢长剑竖剑格挡时，身前浮现一圈奥秘符文（Logos 奥秘射线的字形 {@code arcane_ray/runes}，3×3 图集），
 * 圈面朝着视线，缓缓转动，每枚各自明灭。举剑 0.3 秒、真正挡得住时才成形；放下或转去蓄力时散去。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class BlacksteelGuard {
    private static final RenderType FUZZ = VfxParts.add("arcane_ray/fuzz");
    private static final RenderType RUNES_DEEP = VfxParts.blend("arcane_ray/runes");
    private static final RenderType RUNES = VfxParts.add("arcane_ray/runes");
    private static final ChargeStyle STYLE = ChargeStyle.of(Element.ARCANE);
    private static final int COUNT = 8;
    /** 圈心在眼前多远、比眼睛低多少，圈的半径与每枚符文的半宽（格）。 */
    private static final double AHEAD = .85, BELOW = .25, RADIUS = .5;
    private static final float SIZE = .1f;
    /** 举剑后第几刻开始成形（格挡前摇 0.3 秒 = 6 刻），以及成形、散去要几刻。 */
    private static final float FORM_AT = 4, FORM = 2, FADE = 4;

    private BlacksteelGuard() {}

    /** 符文圈的显现程度（0–1）。按真实刻数算：原版的格挡前摇是固定的 0.3 秒，不随攻速变。 */
    private static float shown(LongswordPayload p, double now) {
        float age = (float) (now - p.started());
        return switch (p.move()) {
            case SwordCasting.GUARD_RAISE -> smooth((age - FORM_AT) / FORM);
            case SwordCasting.GUARD -> 1;
            case "guard_lower", "guard_cancel", SwordCasting.CHARGE -> 1 - smooth(age / FADE);
            default -> 0;
        };
    }

    /** 在 Logos 之后提交（Logos 在这个事件里先 begin ShaderPackOverlay）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double now = mc.level.getGameTime() + pt;
        var camera = event.getLevelRenderState().cameraRenderState;
        var raw = event.getSubmitNodeCollector();
        var layers = VfxParts.layered(raw, ShaderPackOverlay.collector(raw));
        PoseStack pose = event.getPoseStack();
        for (Player player : mc.level.players()) {
            var p = LongswordClient.pose(player.getUUID());
            if (p == null || !(player.getMainHandItem().getItem() instanceof dev.logos.dimension.item.BlacksteelLongswordItem) || player.isInvisible()) continue;
            float a = shown(p, now);
            if (a <= .003f) continue;
            boolean self = player == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
            if (self) a *= .7f;
            pose.pushPose();
            pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
            ring(player, a, now, pt, pose, layers);
            pose.popPose();
        }
    }

    private static void ring(Player player, float alpha, double now, float pt, PoseStack pose,
                             java.util.function.IntFunction<net.minecraft.client.renderer.OrderedSubmitNodeCollector> layers) {
        Vec3 look = player.getViewVector(pt);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(look).normalize();
        Vec3 center = player.getEyePosition(pt).add(look.scale(AHEAD)).add(up.scale(-BELOW));
        // 成形时从小到大张开一点。
        double radius = RADIUS * (.75 + .25 * alpha);
        double turn = now * .03;
        Vec3[] at = new Vec3[COUNT], out = new Vec3[COUNT], along = new Vec3[COUNT];
        float[] a = new float[COUNT];
        for (int i = 0; i < COUNT; i++) {
            double angle = turn + i * Math.PI * 2 / COUNT;
            out[i] = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            along[i] = out[i].cross(look).normalize();
            at[i] = center.add(out[i].scale(radius));
            a[i] = alpha * (.55f + .45f * (float) Math.pow(.5 + .5 * Math.sin(now * .12 + i * 2.4), 1.5));
        }
        layers.apply(0).submitCustomGeometry(pose, FUZZ, (p, buf) -> {
            for (int i = 0; i < COUNT; i++) glyph(p, buf, at[i], along[i], out[i], SIZE * 2.2f, -1, VfxParts.argb(a[i] * .3f, STYLE.deep()));
        });
        // 先画一层深色混合，亮天空下字形也看得清（与 Logos 的奥秘蓄力一样）。
        layers.apply(1).submitCustomGeometry(pose, RUNES_DEEP, (p, buf) -> {
            for (int i = 0; i < COUNT; i++) glyph(p, buf, at[i], along[i], out[i], SIZE, i % 9, VfxParts.argb(a[i] * .55f, STYLE.deep()));
        });
        layers.apply(2).submitCustomGeometry(pose, RUNES, (p, buf) -> {
            for (int i = 0; i < COUNT; i++) glyph(p, buf, at[i], along[i], out[i], SIZE, i % 9,
                    VfxParts.argb(a[i] * .95f, VfxParts.lerpColor(STYLE.light(), STYLE.main(), .35f + .3f * (i % 2))));
        });
    }

    /** 圈面上的一枚：头朝圈外，{@code frame} 为图集里第几枚字形（-1 画整张贴图）。 */
    private static void glyph(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, Vec3 side, Vec3 up, float r, int frame, int color) {
        float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
        if (frame >= 0) {
            u0 = (frame % 3) / 3f; u1 = u0 + 1 / 3f;
            v0 = (frame / 3) / 3f; v1 = v0 + 1 / 3f;
        }
        Vec3 s = side.scale(r), u = up.scale(r);
        VfxParts.vertex(pose, buf, c.subtract(s).add(u), u0, v0, color);
        VfxParts.vertex(pose, buf, c.subtract(s).subtract(u), u0, v1, color);
        VfxParts.vertex(pose, buf, c.add(s).subtract(u), u1, v1, color);
        VfxParts.vertex(pose, buf, c.add(s).add(u), u1, v0, color);
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
