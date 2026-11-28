package dev.logos.dimension.client.companion;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.companion.PrincessUltimatePlan;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * 精灵公主奥义在世界里画的东西（docs/ELF_PRINCESS_COMPANION.md 第 11 节），所有看得见她的人都看得到，照 {@link PrincessUltimatePlan} 的时间表：
 * <ul>
 *   <li>种子破土：地上一道道发光的裂缝，一株两片叶子的新苗，金色光尘往上飘；</li>
 *   <li>抽枝化箭：两股藤蔓螺旋着往上长到她搭箭的手边，越往上越收拢，坍缩成一支带叶形箭簇的翠金主箭；</li>
 *   <li>莲华光环：她脚下绽开一朵半透明的莲华，一圈光往外荡到十六格；</li>
 *   <li>主箭：放箭时弹出一圈音爆环；飞上天时越长越大，拖着光和打转的叶片，到法阵中心掉头朝下，最后垂直贯进地里；</li>
 *   <li>莲华法阵：敌群正上方一朵从花苞绽开的翠金莲华，下面两圈一支支凝出来、朝下指着的光矢（落下去以后由服务端的 Logos 拖尾接着画）；</li>
 *   <li>贯地：地上往外荡的冲击环、一圈发光的根系，之后是慢慢淡掉的花海余韵。</li>
 * </ul>
 * 贴图都是 Logos 的（{@code logos:textures/vfx/...}）。白天在浅色地面、天空上光靠加法会冲成一片白，所以叶片、法阵先垫一层半透明的深绿（同琥珀的炼成阵）。
 * 几何经 {@link ShaderPackOverlay} 的收集器提交，排在 Logos 之后。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class PrincessUltimateFx {
    static final int MINT = PrincessUltimateShow.MINT, LIGHT = PrincessUltimateShow.LIGHT, GOLD = PrincessUltimateShow.GOLD, DEEP = 0x1E5A40;
    /** 新苗的嫩绿、暖金叶（实色，不靠加法）。 */
    static final int SPROUT = 0x3FD98A, AMBER = 0xF0B43C;
    private static final String LEAF = "life_impact/leaf", GLOW = "heal/glow", STREAK = "force/streak", RING = "heal/ring",
            CIRCLE = "life_field/circle_13", RUNES = "charge/circle_11", VEINS = "pillar_burst/veins", FLARE = "life_impact/flare",
            RAYS = "life_impact/rays", RIPPLE = "pillar_burst/ripple_0";
    private static final Vec3 UP = new Vec3(0, 1, 0), DOWN = new Vec3(0, -1, 0), X = new Vec3(1, 0, 0), Z = new Vec3(0, 0, 1);

    private static final List<PrincessUltimateShow> SHOWS = new ArrayList<>();
    /** 画了几帧（客户端场景检查）。 */
    private static long frames;

    private PrincessUltimateFx() {}

    static void add(PrincessUltimateShow show) {
        SHOWS.add(show);
    }

    static void remove(PrincessUltimateShow show) {
        SHOWS.remove(show);
    }

    public static long frames() {
        return frames;
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        SHOWS.clear();
    }

    /** 在 Logos 之后提交（Logos 在这个事件里先 begin ShaderPackOverlay）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        if (SHOWS.isEmpty() || Minecraft.getInstance().level == null) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (PrincessUltimateShow s : SHOWS) new Draw(pose, out, view, s).all();
        pose.popPose();
        frames++;
    }

    /** 一帧里画一段。 */
    private record Draw(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, PrincessUltimateShow s) {
        void all() {
            float a = s.age;
            PrincessUltimatePlan.Timeline p = s.plan;
            if (p.sprouts()) {
                sprout(a, p);
                vine(a, p);
            }
            ring(a, p);
            boom(a, p);
            arrow(a, p);
            lotus(a, p);
            impact(a, p);
        }

        // ---------------------------------------------------------------- 种子、新苗

        private void sprout(float a, PrincessUltimatePlan.Timeline p) {
            if (a < p.seed() || a > p.nock() + 4) return;
            float k = VfxParts.smooth(p.seed(), p.seed() + 5, a), fade = 1 - VfxParts.smooth(p.nock(), p.nock() + 4, a);
            float alpha = k * fade;
            if (alpha <= .003f) return;
            Vec3 at = s.seed.add(0, .02, 0);
            // 地缝：一道道发光的裂纹。新苗和地缝都用带颜色的半透明层画（白天浅色的地上光靠加法会冲成一片白，
            // Cut 1 压黑的后期也要靠「有颜色、够亮」认出它们，留着不压）
            ground(false, VEINS, at, .2f + .42f * k, .3f, VfxParts.argb(.45f * alpha, SPROUT));
            ground(true, GLOW, at.add(0, .008, 0), .45f * k, 0, VfxParts.argb(.3f * alpha, MINT));
            // 新苗：一根茎、两片叶子（一片嫩绿、一片暖金）
            float h = .42f * VfxParts.smooth(p.seed() + 1, p.seed() + 8, a);
            if (h > .01f) {
                Vec3 top = at.add(0, h, 0);
                streak(false, GLOW, at, top, .06f, VfxParts.argb(alpha, SPROUT));
                float leaf = .36f * VfxParts.smooth(p.seed() + 4, p.seed() + 10, a);
                Vec3 l = UP.scale(.75).add(s.side.scale(.66)).normalize(), r = UP.scale(.75).subtract(s.side.scale(.66)).normalize();
                blade(false, LEAF, top, l, leaf, leaf * .55f, VfxParts.argb(alpha, SPROUT));
                blade(false, LEAF, top, r, leaf, leaf * .55f, VfxParts.argb(alpha, AMBER));
                blade(true, LEAF, top, l, leaf * .9f, leaf * .5f, VfxParts.argb(.45f * alpha, LIGHT));
                blade(true, LEAF, top, r, leaf * .9f, leaf * .5f, VfxParts.argb(.45f * alpha, GOLD));
                sprite(true, GLOW, top, .12f, 0, VfxParts.argb(.5f * alpha, GOLD));
            }
            // 金色光尘往上飘
            for (int i = 0; i < 10; i++) {
                double rise = ((a - p.seed()) * (.04 + .03 * PrincessUltimatePlan.hash(s.cast.seed(), i)) + PrincessUltimatePlan.hash(s.cast.seed(), i + 20)) % 1.2;
                double ang = PrincessUltimatePlan.hash(s.cast.seed(), i + 40) * Math.PI * 2, rr = .08 + .25 * PrincessUltimatePlan.hash(s.cast.seed(), i + 60);
                Vec3 m = at.add(Math.cos(ang) * rr, .05 + rise, Math.sin(ang) * rr);
                float f = alpha * (1 - (float) rise / 1.2f);
                sprite(false, GLOW, m, .035f, 0, VfxParts.argb(.9f * f, AMBER));
            }
        }

        /** 第 {@code u}（0–1）处的藤蔓：从种子到搭箭的地方，绕着转两圈，越往上越收拢；{@code collapse} 时整股收成一条线。 */
        private Vec3 helix(float u, double turn, float collapse) {
            Vec3 c = s.seed.lerp(s.nock, u);
            double ang = u * Math.PI * 4 + turn;
            double r = (.55 * (1 - u) + .1) * (1 - collapse);
            return c.add(s.dir.scale(Math.cos(ang) * r)).add(s.side.scale(Math.sin(ang) * r));
        }

        private void vine(float a, PrincessUltimatePlan.Timeline p) {
            if (a < p.grow() || a > p.nock() + 3) return;
            float grow = 1 - (float) Math.pow(1 - Mth.clamp((a - p.grow()) / (p.nock() - p.grow()), 0, 1), 1.6);
            float collapse = VfxParts.smooth(p.nock() - 3, p.nock() + 1, a), fade = 1 - VfxParts.smooth(p.nock(), p.nock() + 3, a);
            if (grow <= .01f || fade <= .003f) return;
            int n = 30;
            for (int strand = 0; strand < 2; strand++) {
                double turn = strand * Math.PI;
                Vec3 prev = helix(0, turn, collapse);
                for (int i = 1; i <= n; i++) {
                    float u = grow * i / n;
                    Vec3 next = helix(u, turn, collapse);
                    float tip = .4f + .6f * i / n;
                    streak(false, GLOW, prev, next, .16f, VfxParts.argb(.25f * fade, DEEP));
                    streak(true, STREAK, prev, next, .07f, VfxParts.argb(.9f * fade * tip, strand == 0 ? LIGHT : GOLD));
                    streak(true, GLOW, prev, next, .28f, VfxParts.argb(.22f * fade * tip, MINT));
                    prev = next;
                }
                // 藤上长出的叶子，朝外
                for (int j = 1; j <= 5; j++) {
                    float u = j / 6f;
                    if (u > grow) break;
                    Vec3 at = helix(u, turn, collapse);
                    Vec3 out = at.subtract(s.seed.lerp(s.nock, u));
                    out = out.lengthSqr() < 1e-4 ? s.side : out.normalize();
                    Vec3 tipDir = out.add(UP.scale(.6)).normalize();
                    float size = .2f * (1 - collapse) * Mth.clamp((grow - u) * 6, 0, 1);
                    blade(false, LEAF, at, tipDir, size * 1.1f, size * .6f, VfxParts.argb(.45f * fade, DEEP));
                    blade(true, LEAF, at, tipDir, size, size * .55f, VfxParts.argb(.9f * fade, (j & 1) == 0 ? LIGHT : MINT));
                }
                // 长到哪一头最亮
                sprite(true, FLARE, helix(grow, turn, collapse), .35f, a * .2f, VfxParts.argb(.8f * fade, GOLD));
            }
        }

        // ---------------------------------------------------------------- 莲华光环

        private void ring(float a, PrincessUltimatePlan.Timeline p) {
            if (a < p.ring() || a > p.release() + 8) return;
            float open = VfxParts.smooth(p.ring(), p.ring() + 5, a), fade = 1 - VfxParts.smooth(p.release(), p.release() + 8, a);
            float k = open * fade;
            Vec3 c = s.feet.add(0, .03, 0);
            float spin = a * .02f;
            if (k > .003f) {
                ground(false, CIRCLE, c, 2f * open, spin, VfxParts.argb(.4f * k, DEEP));
                ground(true, CIRCLE, c.add(0, .004, 0), 2f * open, spin, VfxParts.argb(.75f * k, MINT));
                ground(true, RING, c.add(0, .008, 0), 2.15f * open, 0, VfxParts.argb(.55f * k, LIGHT));
                // 一朵莲华：八片花瓣朝外斜着翘起来
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * 2 * i / 8 + .2;
                    Vec3 out = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    Vec3 tipDir = out.scale(Math.cos(Math.toRadians(35))).add(UP.scale(Math.sin(Math.toRadians(35))));
                    Vec3 tangent = new Vec3(-out.z, 0, out.x);
                    Vec3 base = c.add(out.scale(.45 * open));
                    petal(false, base, tipDir, tangent, .55f * open, .26f * open, VfxParts.argb(.35f * k, DEEP));
                    petal(true, base, tipDir, tangent, .55f * open, .26f * open, VfxParts.argb(.55f * k, i % 2 == 0 ? LIGHT : GOLD));
                }
            }
            // 生命光环一圈往外荡到十六格
            float w = (a - p.ring()) / 10f;
            if (w < 1) ground(true, RING, c.add(0, .02, 0), (float) (.5 + (PrincessUltimatePlan.HEAL_RADIUS - .5) * (1 - (1 - w) * (1 - w))), 0,
                    VfxParts.argb(.7f * (1 - w) * (1 - w), LIGHT));
        }

        // ---------------------------------------------------------------- 放箭的音爆环

        private void boom(float a, PrincessUltimatePlan.Timeline p) {
            float w = (a - p.release()) / 7f;
            if (w < 0 || w >= 1) return;
            Vec3 h = s.aim, across = perpendicular(h), up = across.cross(h).normalize();
            Vec3 at = s.nock.add(h.scale(.7));
            float k = (1 - w) * (1 - w);
            flat(true, RING, at, across, up, .3f + 2.2f * w, 0, VfxParts.argb(.85f * k, LIGHT));
            flat(true, RING, at.add(h.scale(.5)), across, up, .2f + 1.4f * w, 0, VfxParts.argb(.6f * k, GOLD));
            sprite(true, RAYS, at, 1.3f * (1 - w), w * 2, VfxParts.argb(.7f * k, LIGHT));
        }

        // ---------------------------------------------------------------- 主箭

        private void arrow(float a, PrincessUltimatePlan.Timeline p) {
            float formed = p.sprouts() ? VfxParts.smooth(p.nock() - 2, p.nock() + 1, a) : 1;
            if (a < p.release()) {
                // 搭在弓上：藤蔓收成的那一支，沿着她瞄的方向
                // 搭在弓上：藤蔓收成的那一支，从收拢的地方挪到她手里，跟着她拉弓瞄的方向
                if (formed > .003f) {
                    float onBow = p.sprouts() ? VfxParts.smooth(p.nock(), p.nock() + 3, a) : 1;
                    Vec3 at = s.nock.add(s.aim.scale(.25)), h = s.aim;
                    var her = Minecraft.getInstance().level.getEntity(s.cast.performer());
                    if (her != null) {
                        float pt = a - (float) Math.floor(a);
                        Vec3 view = her.getViewVector(pt);
                        at = at.lerp(her.getEyePosition(pt).add(0, -.18, 0).add(view.scale(.5)), onBow);
                        h = h.scale(1 - onBow).add(view.scale(onBow)).normalize();
                    }
                    shaft(at, h, PrincessUltimatePlan.ARROW_SHORT, formed * (1 - .35f * onBow), false);
                }
                return;
            }
            float fade = 1 - VfxParts.smooth(p.impact() + 4, p.impact() + 14, a);
            if (fade <= .003f) return;
            Vec3 at = s.arrow(a);
            if (at == null) return;
            Vec3 h = PrincessUltimatePlan.heading(p, a, s.nock, s.apex);
            float len = PrincessUltimatePlan.length(p, a);
            shaft(at, h, len, fade, a < p.apex() || a >= p.plunge() && a < p.impact());
            if (a < p.apex()) {
                // 飞上去时拖着打转的发光叶片
                Vec3 across = perpendicular(h), up = across.cross(h).normalize();
                for (int i = 0; i < 6; i++) {
                    double ang = a * 1.3 + i * 1.05;
                    Vec3 o = across.scale(Math.cos(ang)).add(up.scale(Math.sin(ang))).scale(.35 + .1 * i);
                    Vec3 m = at.subtract(h.scale(len * .4 + i * .7)).add(o);
                    blade(true, LEAF, m, h.scale(-1).add(o.normalize().scale(.5)).normalize(), .3f, .16f, VfxParts.argb(.8f * (1 - i / 7f), i % 2 == 0 ? LIGHT : GOLD));
                }
            }
        }

        /**
         * 一支箭：深色的底、光芯、外面一层柔光，叶形的箭簇（暖金）、两片叶形的箭羽。{@code at} 是箭身中点，{@code h} 朝箭头；
         * {@code moving} 时箭尾拖一道光。
         */
        private void shaft(Vec3 at, Vec3 h, float len, float alpha, boolean moving) {
            Vec3 tail = at.subtract(h.scale(len * .5)), head = at.add(h.scale(len * .5));
            Vec3 neck = head.subtract(h.scale(len * .2));
            float w = Math.max(.05f, len * .055f);
            streak(false, GLOW, tail, head, w * 3.2f, VfxParts.argb(.45f * alpha, DEEP));
            streak(true, GLOW, tail, head, w * 5f, VfxParts.argb(.35f * alpha, MINT));
            streak(true, STREAK, tail, neck, w * 1.6f, VfxParts.argb(alpha, LIGHT));
            streak(true, STREAK, tail, neck, w * .7f, VfxParts.argb(alpha, 0xFFFFFF));
            // 箭簇：一片朝前的叶子
            blade(false, LEAF, neck.subtract(h.scale(len * .03)), h, len * .27f, len * .16f, VfxParts.argb(.6f * alpha, DEEP));
            blade(true, LEAF, neck.subtract(h.scale(len * .03)), h, len * .25f, len * .14f, VfxParts.argb(alpha, GOLD));
            sprite(true, FLARE, head.subtract(h.scale(len * .08)), len * .2f, 0, VfxParts.argb(.75f * alpha, GOLD));
            // 箭羽：两片往后张开的叶子
            Vec3 across = perpendicular(h);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                Vec3 back = h.scale(-1).add(across.scale(.55 * sgn)).normalize();
                blade(true, LEAF, tail.add(h.scale(len * .12)), back, len * .15f, len * .07f, VfxParts.argb(.9f * alpha, LIGHT));
            }
            if (moving) streak(true, GLOW, tail.subtract(h.scale(len * 2.2)), tail, w * 3, VfxParts.argb(.45f * alpha, MINT));
        }

        // ---------------------------------------------------------------- 莲华法阵、挂着的光矢

        private void lotus(float a, PrincessUltimatePlan.Timeline p) {
            if (a < p.apex() || a > p.impact() + 10) return;
            float open = VfxParts.smooth(p.apex(), p.apex() + 6, a), fade = 1 - VfxParts.smooth(p.impact() + 1, p.impact() + 10, a);
            float k = VfxParts.smooth(p.apex(), p.apex() + 2, a) * fade;
            if (k <= .003f) return;
            float ease = 1 - (1 - open) * (1 - open) * (1 - open);
            float R = PrincessUltimatePlan.LOTUS_RADIUS * (.35f + .65f * ease);
            float spin = a * .015f + (1 - ease) * 1.2f;
            Vec3 c = s.apex;
            flat(false, GLOW, c.add(0, -.02, 0), X, Z, R * 1.2f, 0, VfxParts.argb(.35f * k, DEEP));
            flat(false, CIRCLE, c, X, Z, R, spin, VfxParts.argb(.5f * k, DEEP));
            flat(true, CIRCLE, c.add(0, .01, 0), X, Z, R, spin, VfxParts.argb(.85f * k, MINT));
            flat(true, RUNES, c.add(0, .02, 0), X, Z, R * .62f, -spin * 1.8f, VfxParts.argb(.6f * k, GOLD));
            flat(true, RING, c.add(0, .03, 0), X, Z, R * 1.06f, 0, VfxParts.argb(.7f * k, LIGHT));
            if (open < 1) flat(true, RING, c.add(0, .04, 0), X, Z, PrincessUltimatePlan.LOTUS_RADIUS * (.6f + .9f * open), 0,
                    VfxParts.argb(.6f * (1 - open) * fade, GOLD));
            // 两圈花瓣：从合着的花苞绽开，外圈平一些、里圈翘一些
            for (int ring = 0; ring < 2; ring++) {
                float tilt = (float) Math.toRadians(ring == 0 ? Mth.lerp(ease, 84, 24) : Mth.lerp(ease, 88, 50));
                float len = R * (ring == 0 ? .5f : .36f), wid = R * (ring == 0 ? .24f : .18f), off = R * (ring == 0 ? .22f : .08f);
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * 2 * (i + ring * .5) / 8 + spin * .3;
                    Vec3 out = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    Vec3 tipDir = out.scale(Math.cos(tilt)).add(UP.scale(Math.sin(tilt)));
                    Vec3 tangent = new Vec3(-out.z, 0, out.x);
                    Vec3 base = c.add(out.scale(off)).add(0, .05 + ring * .05, 0);
                    petal(false, base, tipDir, tangent, len, wid, VfxParts.argb(.62f * k, DEEP));
                    petal(true, base, tipDir, tangent, len, wid, VfxParts.argb(.9f * k, ring == 0 ? LIGHT : GOLD));
                }
            }
            sprite(true, GLOW, c.add(0, .3, 0), R * .45f, 0, VfxParts.argb(.55f * k, GOLD));
            sprite(true, RAYS, c.add(0, .3, 0), R * .55f, a * .03f, VfxParts.argb(.22f * k, LIGHT));
            // 挂着的光矢：一支支凝出来，朝下指着；落下以后交给服务端的拖尾
            for (int i = 0; i < PrincessUltimatePlan.RAIN; i++) {
                if (a < p.appear(i) || a >= p.launch(i)) continue;
                float shown = VfxParts.smooth(p.appear(i), p.appear(i) + 2, a) * fade;
                Vec3 slot = PrincessUltimatePlan.slot(c, s.cast.seed(), i);
                shaft(slot, DOWN, 2.6f, shown, false);
                float pop = 1 - Mth.clamp((a - p.appear(i)) / 4f, 0, 1);
                if (pop > 0) sprite(true, FLARE, slot.add(0, -.9, 0), .9f * pop, 0, VfxParts.argb(.9f * pop, LIGHT));
            }
        }

        // ---------------------------------------------------------------- 贯地、花海余韵

        private void impact(float a, PrincessUltimatePlan.Timeline p) {
            if (a < p.impact()) return;
            float w = a - p.impact();
            Vec3 c = s.center.add(0, .05, 0);
            if (w < 8) {
                float u = w / 8;
                ground(true, RING, c.add(0, .01, 0), 1 + 9 * (1 - (1 - u) * (1 - u)), 0, VfxParts.argb(.9f * (1 - u) * (1 - u), LIGHT));
            }
            if (w < 11) {
                float u = w / 11;
                ground(true, RIPPLE, c.add(0, .02, 0), .5f + 7 * (1 - (1 - u) * (1 - u)), 0, VfxParts.argb(.8f * (1 - u), GOLD));
            }
            // 沿地面荡开的一圈发光根系
            float roots = (1 - VfxParts.smooth(4, 22, w)) * VfxParts.smooth(0, 1.5f, w);
            float rr = 6 * (1 - (float) Math.pow(1 - Math.min(1, w / 5), 2));
            if (roots > .003f) {
                ground(false, VEINS, c, rr, .7f, VfxParts.argb(.4f * roots, DEEP));
                ground(true, VEINS, c.add(0, .004, 0), rr, .7f, VfxParts.argb(.85f * roots, MINT));
            }
            // 花海余韵：一圈淡淡的光，地上躺着发光的叶片，慢慢淡掉
            float rest = VfxParts.smooth(2, 6, w) * (1 - VfxParts.smooth(10, p.length() - p.impact(), w));
            if (rest <= .003f) return;
            ground(true, CIRCLE, c.add(0, .006, 0), (float) PrincessUltimatePlan.IMPACT_RADIUS, a * .006f, VfxParts.argb(.3f * rest, MINT));
            long seed = s.cast.seed();
            for (int i = 0; i < 28; i++) {
                double ang = PrincessUltimatePlan.hash(seed, 100 + i) * Math.PI * 2;
                double r = Math.sqrt(PrincessUltimatePlan.hash(seed, 200 + i)) * PrincessUltimatePlan.IMPACT_RADIUS;
                Vec3 at = c.add(Math.cos(ang) * r, .01, Math.sin(ang) * r);
                float turn = (float) (PrincessUltimatePlan.hash(seed, 300 + i) * Math.PI * 2);
                float size = .18f + .14f * (float) PrincessUltimatePlan.hash(seed, 400 + i);
                int color = i % 3 == 0 ? GOLD : i % 3 == 1 ? LIGHT : MINT;
                flat(true, LEAF, at, X, Z, size, turn, VfxParts.argb(.8f * rest, color));
            }
        }

        // ---------------------------------------------------------------- 画法

        private void submit(boolean glow, String texture, SubmitNodeCollector.CustomGeometryRenderer draw) {
            out.submitCustomGeometry(pose, glow ? VfxParts.add(texture) : VfxParts.blend(texture), draw);
        }

        private void ground(boolean glow, String texture, Vec3 c, float r, float turn, int color) {
            submit(glow, texture, (p, buf) -> VfxParts.ground(p, buf, c, r, turn, color));
        }

        private void flat(boolean glow, String texture, Vec3 c, Vec3 a, Vec3 b, float r, float turn, int color) {
            submit(glow, texture, (p, buf) -> VfxParts.flat(p, buf, c, a, b, r, turn, color));
        }

        private void sprite(boolean glow, String texture, Vec3 c, float r, float roll, int color) {
            submit(glow, texture, (p, buf) -> VfxParts.sprite(p, buf, view, c, r, roll, color));
        }

        private void streak(boolean glow, String texture, Vec3 tail, Vec3 head, float w, int color) {
            submit(glow, texture, (p, buf) -> VfxParts.streak(p, buf, view, tail, head, w, color));
        }

        /** 一片朝 {@code tipDir} 长的叶子（贴图里叶尖朝上），从 {@code base} 长出去 {@code len}，宽 {@code width}，尽量朝着镜头。 */
        private void blade(boolean glow, String texture, Vec3 base, Vec3 tipDir, float len, float width, int color) {
            Vec3 c = base.add(tipDir.scale(len * .5));
            Vec3 across = tipDir.cross(c.subtract(view.cam()));
            across = across.lengthSqr() < 1e-8 ? perpendicular(tipDir) : across.normalize();
            Vec3 half = across.scale(width * .5), along = tipDir.scale(len * .5);
            submit(glow, texture, (p, buf) -> VfxParts.quad(p, buf, c, half, along, color));
        }

        /** 一片躺在 {@code tipDir}、{@code tangent} 张成的面里的花瓣（不朝镜头转：一朵花的花瓣各朝各的方向）。 */
        private void petal(boolean glow, Vec3 base, Vec3 tipDir, Vec3 tangent, float len, float width, int color) {
            Vec3 c = base.add(tipDir.scale(len * .5));
            // 瓣身：柔光拉成的椭圆（莲花瓣宽而圆）；瓣脉：窄一点的叶子
            Vec3 body = tangent.scale(width * .62), along = tipDir.scale(len * .56);
            submit(glow, GLOW, (p, buf) -> VfxParts.quad(p, buf, c, body, along, color));
            Vec3 vein = tangent.scale(width * .38), veinAlong = tipDir.scale(len * .5);
            submit(glow, LEAF, (p, buf) -> VfxParts.quad(p, buf, c, vein, veinAlong, color));
        }

        private static Vec3 perpendicular(Vec3 v) {
            Vec3 a = v.cross(Math.abs(v.y) > .9 ? X : UP);
            return a.normalize();
        }
    }
}
