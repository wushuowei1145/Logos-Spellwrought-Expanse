package dev.logos.dimension.client.hero;

import static dev.logos.dimension.ultimate.HeroFinisherTimeline.APEX;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.CONVERGE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.FALL;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.GOLD;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.IMPACT;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.LENGTH;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.LUNGE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.REVEAL;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.RISE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.WHITE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.smooth;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.screenfx.GlowBloom;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.ultimate.HeroFinisherTimeline;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * 最后一击在世界里的那一半（所有看得见的人都有），按 {@link HeroFinisherTimeline} 走：
 * 掌心的火 → 交汇到掌心的金光 → 参战同伴的光流灌进来 → 魔弹（白芯、按参战同伴主色转的外环、星芒、电弧）→ 冲刺的拖光 →
 * 落点的冲击环 → 通天火柱（外缠同伴主色的螺旋、顶上捅开的云洞）→ 漫天落下的星火和地上的余烬。
 *
 * <p>这一帧 {@link PostVfx} 在收时交给它（在后期调暗之后画、不受雾和光影包影响，几百格外都看得见），不收时照常在世界里画
 * （和 Logos 的 {@code ShaderPackOverlay} 共用收集器，在它 begin 之后交）。贴图都是 Logos 已有的 {@code logos:textures/vfx/...}。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class HeroFinisherVfx {
    /** 火柱多高、多粗（格）。 */
    static final float HEIGHT = 110, RADIUS = 5;

    /**
     * 一层贴图：PostVfx 在收时交给它，否则在世界里画（照曦誓的 PostLayer）。发光的是加法；同伴主色的光带底下垫一层半透明混合的深色，
     * 白天的亮天上加法的淡色会被冲成白的，垫了深色主色才看得出来。
     */
    private record Layer(RenderType world, Identifier texture, boolean additive) {
        static Layer of(String path) {
            return new Layer(VfxParts.add(path), PostVfx.logos(path), true);
        }

        static Layer blend(String path) {
            return new Layer(VfxParts.blend(path), PostVfx.logos(path), false);
        }

        void submit(PoseStack pose, SubmitNodeCollector out, SubmitNodeCollector.CustomGeometryRenderer draw) {
            if (PostVfx.collecting()) draw.render(pose.last(), additive ? PostVfx.add(texture, false) : PostVfx.blend(texture, false));
            else out.submitCustomGeometry(pose, world, draw);
        }
    }

    private static final Layer GLOW = Layer.of("heal/glow"), RING = Layer.of("heal/ring"),
            CIRCLE = Layer.of("fire_beam/circle"), STAR = Layer.of("arcane_impact/star"), FLASH = Layer.of("arcane_impact/flash"),
            PLASMA = Layer.of("charge/plasma"), COLUMN = Layer.of("charge/pillar"), FLAME = Layer.of("pillar_burst/flame"),
            RAYS = Layer.of("lightning_beam/rays"), ORBIT = Layer.of("void_ray/ring"), STREAK = Layer.of("force/streak"),
            BURST = Layer.of("fire_impact/flash"), FIRE_RING = Layer.of("fire_ray/ring"), WIND = Layer.of("physical_ray/wind"),
            LASER = Layer.of("fire_beam/laser");
    private static final Layer BODY = Layer.blend("force/streak"), BODY_RING = Layer.blend("void_ray/ring"), GLOW_BODY = Layer.blend("heal/glow");
    /** 炽白偏暖（魔弹的芯）、暖白（火柱的芯）。 */
    private static final int HOT = 0xFFF4D8, WARM = 0xFFE7B0, EMBER = 0xFFB347, DEEP_GOLD = 0xC98A1E;

    /** 星火：从火柱四周的高处落下来。 */
    private record Spark(float born, Vec3 from, float speed, Vec3 drift, float size, int color) {
        Vec3 at(float t) {
            float a = t - born;
            return from.add(drift.scale(a)).add(0, -speed * a, 0);
        }
    }

    /** 地上的余烬：落点周围一闪一闪地暗下去。 */
    private record Ember(Vec3 offset, float size, float phase, int color) {}

    private final HeroFinisherShow show;
    private final List<Spark> sparks = new ArrayList<>();
    private final List<Ember> embers = new ArrayList<>();

    HeroFinisherVfx(HeroFinisherShow show) {
        this.show = show;
        RandomSource random = RandomSource.create(show.cast.seed());
        int[] accents = show.accents;
        for (int i = 0; i < 150; i++) {
            double r = 4 + 28 * Math.sqrt(random.nextDouble()), th = random.nextDouble() * Math.PI * 2;
            Vec3 from = new Vec3(Math.cos(th) * r, 26 + random.nextDouble() * 60, Math.sin(th) * r);
            int color = accents.length > 0 && random.nextInt(10) < 3 ? accents[random.nextInt(accents.length)]
                    : random.nextBoolean() ? GOLD : WARM;
            sparks.add(new Spark(FALL - 8 + random.nextFloat() * 12, from, 1.1f + random.nextFloat() * .9f,
                    new Vec3((random.nextDouble() - .5) * .12, 0, (random.nextDouble() - .5) * .12), .14f + random.nextFloat() * .14f, color));
        }
        for (int i = 0; i < 46; i++) {
            double r = 1.5 + 18 * Math.sqrt(random.nextDouble()), th = random.nextDouble() * Math.PI * 2;
            int color = accents.length > 0 && random.nextInt(4) == 0 ? accents[random.nextInt(accents.length)] : GOLD;
            embers.add(new Ember(new Vec3(Math.cos(th) * r, .06, Math.sin(th) * r), .2f + random.nextFloat() * .3f, random.nextFloat() * 6.28f, color));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        if (HeroFinisherShow.ACTIVE.isEmpty() || Minecraft.getInstance().level == null) return;
        var camera = event.getLevelRenderState().cameraRenderState;
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
        PoseStack stack = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        VfxParts.View view = VfxParts.View.of(camera);
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (HeroFinisherShow show : List.copyOf(HeroFinisherShow.ACTIVE.values())) show.vfx.draw(stack, out, view, partial);
        stack.popPose();
    }

    private void draw(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float partial) {
        float age = show.age(partial);
        if (age < 0 || age > LENGTH + 1) return;
        Vec3 feet = show.feet(partial);
        beams(stack, out, view, age, feet);
        streams(stack, out, view, age, feet, partial);
        swirl(stack, out, age, feet);
        orb(stack, out, view, age, feet);
        trail(stack, out, view, age, feet);
        if (age >= IMPACT - .5f) {
            Vec3 p = show.burst(age, partial);
            blast(stack, out, view, age, p);
            pillar(stack, out, view, age, p);
            sparks(stack, out, view, age, p);
        }
    }

    // ---------------------------------------------------------------- Cut 2：交汇的金光

    /** 几道斜向的金色光束从主角前方的天上、地下交汇到掌心：一道接一道射进来，光流来了以后淡掉。 */
    private void beams(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 feet) {
        if (age < REVEAL || age > CONVERGE + 14) return;
        Vec3 palm = show.palm(feet);
        Vec3 f = show.fwd, r = show.right, up = new Vec3(0, 1, 0);
        Vec3[] dirs = {f.scale(.75).add(up.scale(.85)).add(r.scale(-.25)), f.scale(.9).add(up.scale(.2)).add(r.scale(-.7)),
                f.scale(.8).add(up.scale(-.45)).add(r.scale(.35))};
        float fade = 1 - smooth(CONVERGE + 4, CONVERGE + 14, age);
        STREAK.submit(stack, out, (p, buf) -> {
            for (int i = 0; i < dirs.length; i++) {
                float k = smooth(REVEAL + 2 + i * 4, REVEAL + 8 + i * 4, age);
                if (k <= 0) continue;
                Vec3 far = palm.add(dirs[i].normalize().scale(18));
                Vec3 head = far.lerp(palm, k);
                VfxParts.streak(p, buf, view, far, head, .22f, VfxParts.argb(.75f * fade, GOLD));
                VfxParts.streak(p, buf, view, far.lerp(head, .55), head, .08f, VfxParts.argb(.9f * fade, WHITE));
            }
        });
        GLOW.submit(stack, out, (p, buf) -> {
            for (int i = 0; i < dirs.length; i++) {
                float k = smooth(REVEAL + 2 + i * 4, REVEAL + 8 + i * 4, age);
                if (k <= 0) continue;
                Vec3 far = palm.add(dirs[i].normalize().scale(18));
                VfxParts.streak(p, buf, view, far, far.lerp(palm, k), .9f, VfxParts.argb(.22f * fade, GOLD));
            }
        });
    }

    // ---------------------------------------------------------------- Cut 3：同伴的光流

    /** 每位参战同伴三股光流，从她身上（看不到她时从四周的天上）弯弯地灌进魔弹；颜色是她的主色，一路有光点往里流。 */
    private void streams(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 feet, float partial) {
        if (age < CONVERGE || age > APEX + 5) return;
        int n = Math.max(1, show.accents.length);
        Vec3 end = show.orb(age, feet);
        float tail = smooth(APEX - 3, APEX + 4, age);
        var level = Minecraft.getInstance().level;
        List<Vec3[]> curves = new ArrayList<>();
        List<Integer> colors = new ArrayList<>(), darks = new ArrayList<>();
        List<float[]> spans = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Entity c = level == null || i >= show.members.length ? null : level.getEntity(show.members[i]);
            // 从画外四面八方涌来：顺着她所在的方向，从十几格外的高处起
            Vec3 toward = c == null ? null : c.getPosition(partial).subtract(feet);
            Vec3 origin = toward != null && toward.horizontalDistanceSqr() > .25
                    ? feet.add(new Vec3(toward.x, 0, toward.z).normalize().scale(16)).add(0, 6 + 2 * (i % 2), 0)
                    : show.orbit(feet, -150 + i * 300f / Math.max(1, n - 1), 16, 7);
            int color = show.accents.length == 0 ? GOLD : show.accents[i];
            int dark = show.deep.length == 0 ? DEEP_GOLD : show.deep[i];
            float head = smooth(CONVERGE + i * 2, CONVERGE + 6 + i * 2, age);
            for (int j = 0; j < 3; j++) {
                float side = (j - 1) * 2.4f + (i % 2 == 0 ? 1 : -1);
                Vec3 out1 = origin.add(0, 4 + j * 1.5, 0).add(show.right.scale(side));
                Vec3 in1 = end.add(end.subtract(origin).normalize().scale(-5)).add(0, 3 - j * 2, 0).add(show.fwd.scale(side * .6));
                curves.add(new Vec3[]{origin, out1, in1, end});
                colors.add(color);
                darks.add(dark);
                spans.add(new float[]{tail, head, i * 1.7f + j * 2.3f});
            }
        }
        for (Layer layer : new Layer[]{BODY, STREAK}) layer.submit(stack, out, (p, buf) -> {
            for (int s = 0; s < curves.size(); s++) {
                Vec3[] q = curves.get(s);
                float[] sp = spans.get(s);
                if (sp[1] <= sp[0]) continue;
                Vec3 prev = bezier(q, sp[0]);
                int steps = 18;
                for (int k = 1; k <= steps; k++) {
                    float u = Mth.lerp(k / (float) steps, sp[0], sp[1]);
                    Vec3 at = bezier(q, u);
                    float flow = .55f + .45f * Mth.sin(u * 16 - age * 1.1f + sp[2]);
                    float width = (.6f - .3f * u) * (.8f + .4f * flow);
                    if (layer == BODY) VfxParts.streak(p, buf, view, prev, at, width, VfxParts.argb(.7f * (.6f + .4f * flow), darks.get(s)));
                    else {
                        VfxParts.streak(p, buf, view, prev, at, width * .55f, VfxParts.argb(.7f * flow, colors.get(s)));
                        VfxParts.streak(p, buf, view, prev, at, width * .18f, VfxParts.argb(.6f * flow, WHITE));
                    }
                    prev = at;
                }
            }
        });
        STAR.submit(stack, out, (p, buf) -> {
            for (int s = 0; s < curves.size(); s++) {
                float[] sp = spans.get(s);
                for (int m = 0; m < 4; m++) {
                    float u = (age * .06f + m / 4f + sp[2] * .1f) % 1f;
                    if (u < sp[0] || u > sp[1]) continue;
                    VfxParts.sprite(p, buf, view, bezier(curves.get(s), u), .35f, age * .2f, VfxParts.argb(.9f, HeroFinisherTimeline.lerpColor(colors.get(s), WHITE, .5f)));
                }
            }
        });
    }

    private static Vec3 bezier(Vec3[] q, float u) {
        float v = 1 - u;
        return q[0].scale(v * v * v).add(q[1].scale(3 * v * v * u)).add(q[2].scale(3 * v * u * u)).add(q[3].scale(u * u * u));
    }

    /** Cut 3：主角四周转的白色弧光，脚下一圈圈荡开的气浪。 */
    private void swirl(PoseStack stack, SubmitNodeCollector out, float age, Vec3 feet) {
        if (age < CONVERGE || age > APEX + 6) return;
        float fade = smooth(CONVERGE, CONVERGE + 4, age) * (1 - smooth(APEX, APEX + 6, age));
        Vec3 chest = show.chest(feet);
        ORBIT.submit(stack, out, (p, buf) -> {
            for (int i = 0; i < 3; i++) {
                float th = age * (.09f + .03f * i) + i * 2.1f;
                Vec3 n = new Vec3(Mth.cos(th) * .4f, 1, Mth.sin(th * 1.3f) * .4f).normalize();
                Vec3[] b = basis(n);
                VfxParts.flat(p, buf, chest, b[0], b[1], 2.4f + i * .9f, age * (.12f + .05f * i), VfxParts.argb(.22f * fade, WHITE));
            }
        });
        RING.submit(stack, out, (p, buf) -> {
            Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1);
            for (int w = 0; w < 4; w++) {
                float k = (age - (CONVERGE + 1 + w * 6)) / 11;
                if (k < 0 || k > 1) continue;
                VfxParts.flat(p, buf, feet.add(0, .08, 0), x, z, .8f + 5.2f * (1 - (1 - k) * (1 - k)), 0, VfxParts.argb((1 - k) * .7f, WARM));
            }
        });
    }

    // ---------------------------------------------------------------- 魔弹

    /** 掌心的火一路长成魔弹：白芯、暖金的等离子、按参战同伴主色转的外环、几何星芒、电弧。 */
    private void orb(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 feet) {
        float r = show.radius(age);
        if (r <= 0) return;
        Vec3 c = show.orb(age, feet);
        float flicker = .88f + .12f * Mth.sin(age * 1.9f) * Mth.sin(age * .7f + 1);
        float big = smooth(.3f, 1.2f, r);
        int mix = HeroFinisherTimeline.mix(show.accents, age * .02f);
        float halo = r < .4f ? r * 5 : r * 1.9f;
        // 底下垫一层半透明的暖金：白天的亮天上加法的光会被冲淡，垫了才看得出是一团金色的火
        if (big > 0) GLOW_BODY.submit(stack, out, (p, buf) -> VfxParts.sprite(p, buf, view, c, r * 1.35f, 0, VfxParts.argb(.55f * big, EMBER)));
        GLOW.submit(stack, out, (p, buf) -> {
            VfxParts.sprite(p, buf, view, c, halo, 0, VfxParts.argb(.3f * flicker, HeroFinisherTimeline.lerpColor(GOLD, mix, .35f * big)));
            // 白芯：用边缘透明的柔光（fire_beam/dot 的边缘是黑色不透明的，在世界里画会被雾染成一块方的）
            VfxParts.sprite(p, buf, view, c, r * .75f, 0, VfxParts.argb(.95f, WHITE));
            VfxParts.sprite(p, buf, view, c, r * .45f, 0, VfxParts.argb(1, WHITE));
        });
        RAYS.submit(stack, out, (p, buf) -> VfxParts.sprite(p, buf, view, c, r * (r < .4f ? 6 : 3f), age * .02f, VfxParts.argb(.3f * flicker, GOLD)));
        STAR.submit(stack, out, (p, buf) -> {
            float tw = .8f + .2f * Mth.sin(age * .9f);
            VfxParts.sprite(p, buf, view, c, r * (r < .4f ? 4.5f : 3.6f) * tw, 0, VfxParts.argb(.55f, WARM));
            // 几何星芒阵：六颗星绕着魔弹转（成型以后）
            if (big > 0) for (int i = 0; i < 6; i++) {
                double th = age * .05 + i * Math.PI / 3;
                Vec3 at = c.add(show.right.scale(Math.cos(th) * r * 1.9)).add(0, Math.sin(th) * r * 1.9, 0).add(show.fwd.scale(Math.sin(th * 2) * r * .4));
                VfxParts.sprite(p, buf, view, at, r * .45f * tw, 0, VfxParts.argb(.8f * big, HeroFinisherTimeline.mix(show.accents, i / 6f)));
            }
        });
        FLASH.submit(stack, out, (p, buf) -> VfxParts.sprite(p, buf, view, c, r * 1.25f, 0, VfxParts.argb(.6f, HOT)));
        PLASMA.submit(stack, out, (p, buf) -> {
            VfxParts.sprite(p, buf, view, c, r * 1.05f, age * .05f, VfxParts.argb(.7f, GOLD));
            VfxParts.sprite(p, buf, view, c, r * .98f, -age * .07f + 1, VfxParts.argb(.55f * big, mix));
        });
        if (big > 0) {
            int n = Math.max(1, show.accents.length);
            for (Layer layer : new Layer[]{BODY_RING, ORBIT}) layer.submit(stack, out, (p, buf) -> {
                for (int i = 0; i < n; i++) {
                    float th = age * (.04f + .013f * i) + i * 2.4f;
                    Vec3 axis = new Vec3(Mth.cos(th), 1.4f + Mth.sin(th * 1.3f + i), Mth.sin(th)).normalize();
                    Vec3[] b = basis(axis);
                    boolean body = layer == BODY_RING;
                    int color = body ? (show.deep.length == 0 ? DEEP_GOLD : show.deep[i]) : show.accents.length == 0 ? GOLD : show.accents[i];
                    VfxParts.flat(p, buf, c, b[0], b[1], r * (1.45f + .16f * i), -age * (.1f + .03f * i), VfxParts.argb((body ? .65f : .8f) * big, color));
                }
            });
            arcs(stack, out, view, age, c, r, big);
        }
        // 不给魔弹交 GlowBloom：它泛光的是一块朝镜头的方块，盖在白芯上会看出一块方的白
    }

    /** 魔弹表面跳的电弧：每两刻换一次形状，按参战同伴的主色。 */
    private void arcs(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 c, float r, float big) {
        if (age < APEX - 2) return;
        RandomSource random = RandomSource.create(show.cast.seed() * 31 + Mth.floor(age / 2));
        LASER.submit(stack, out, (p, buf) -> {
            for (int i = 0; i < 7; i++) {
                Vec3 d = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
                Vec3[] b = basis(d);
                Vec3 prev = c.add(d.scale(r * .95));
                int color = HeroFinisherTimeline.mix(show.accents, i / 7f + age * .01f);
                for (int k = 1; k <= 4; k++) {
                    double ang = k * .32;
                    Vec3 dir = d.scale(Math.cos(ang)).add(b[0].scale(Math.sin(ang)));
                    Vec3 at = c.add(dir.scale(r * (1.02 + .22 * random.nextFloat()))).add(b[1].scale((random.nextFloat() - .5) * r * .25));
                    VfxParts.streak(p, buf, view, prev, at, .07f + r * .025f, VfxParts.argb(.9f * big, color));
                    prev = at;
                }
            }
        });
    }

    /** Cut 5：握着魔弹冲过去时身后拖出的长长的炽金残影和同伴主色的光带。 */
    private void trail(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 feet) {
        if (age < LUNGE || age > IMPACT + 2) return;
        Vec3 c = show.orb(age, feet);
        float r = show.radius(Math.min(age, IMPACT - .01f));
        float a = smooth(LUNGE, LUNGE + 3, age) * (1 - smooth(IMPACT, IMPACT + 2, age));
        Vec3 back = show.fwd.scale(-1);
        GLOW.submit(stack, out, (p, buf) -> VfxParts.streak(p, buf, view, c.add(back.scale(10)).add(0, 1, 0), c, r * 2.2f, VfxParts.argb(.3f * a, GOLD)));
        STREAK.submit(stack, out, (p, buf) -> {
            VfxParts.streak(p, buf, view, c.add(back.scale(9)).add(0, .6, 0), c, r * 1.3f, VfxParts.argb(.65f * a, GOLD));
        });
        int n = Math.max(1, show.accents.length);
        for (Layer layer : new Layer[]{BODY, STREAK}) layer.submit(stack, out, (p, buf) -> {
            for (int i = 0; i < n; i++) {
                double th = i * Math.PI * 2 / n + age * .3;
                Vec3 off = show.right.scale(Math.cos(th) * r * .9).add(0, Math.sin(th) * r * .9, 0);
                boolean body = layer == BODY;
                int color = body ? (show.deep.length == 0 ? DEEP_GOLD : show.deep[i]) : show.accents.length == 0 ? WARM : show.accents[i];
                VfxParts.streak(p, buf, view, c.add(back.scale(7.5)).add(off.scale(1.4)), c.add(off), body ? .7f : .35f, VfxParts.argb(.8f * a, color));
            }
        });
        Vec3 hero = show.chest(feet);
        STREAK.submit(stack, out, (p, buf) -> VfxParts.streak(p, buf, view, hero.add(back.scale(6)), hero, .9f, VfxParts.argb(.45f * a, WARM)));
    }

    // ---------------------------------------------------------------- 砸下、火柱

    /** 砸下那一刻：落点一团炽白的光，地上一圈亮光，三道冲击环、风圈飞快推开。 */
    private void blast(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 p0) {
        float t = age - IMPACT;
        if (t < 0 || t > 40) return;
        float scale = show.far(age).cave() ? .4f : 1;
        Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1), ground = p0.add(0, .25, 0);
        float k = t / 9;
        if (k < 1) BURST.submit(stack, out, (p, buf) -> VfxParts.sprite(p, buf, view, p0.add(0, 2, 0), (4 + 16 * (1 - (1 - k) * (1 - k))) * scale, t * .05f,
                VfxParts.argb(.8f * (1 - k) * (1 - k * .5f), HOT)));
        float glow = (1 - smooth(14, 36, t));
        GLOW.submit(stack, out, (p, buf) -> {
            VfxParts.flat(p, buf, ground, x, z, (14 + 8 * smooth(0, 10, t)) * scale, 0, VfxParts.argb(.45f * glow, EMBER));
            if (k < 1.4f) VfxParts.sprite(p, buf, view, p0.add(0, 3, 0), 16 * scale, 0, VfxParts.argb(.3f * (1 - smooth(0, 12, t)), WARM));
        });
        CIRCLE.submit(stack, out, (p, buf) -> VfxParts.flat(p, buf, ground.add(0, .05, 0), x, z, 11 * scale, age * .03f, VfxParts.argb(.8f * glow, GOLD)));
        for (float start : new float[]{0, 4, 9}) {
            float w = (t - start) / 18;
            if (w < 0 || w > 1) continue;
            float r = (3 + 58 * (1 - (1 - w) * (1 - w))) * scale;
            float a = (1 - w) * smooth(0, .05f, w);
            RING.submit(stack, out, (p, buf) -> VfxParts.flat(p, buf, ground.add(0, .1, 0), x, z, r, 0, VfxParts.argb(a * .7f, WHITE)));
            FIRE_RING.submit(stack, out, (p, buf) -> VfxParts.flat(p, buf, ground.add(0, .15, 0), x, z, r * .93f, w * 2, VfxParts.argb(a * .7f, GOLD)));
            WIND.submit(stack, out, (p, buf) -> VfxParts.cylinder(p, buf, ground, r * .97f, 5 * (1 - w) + 1, 64, age * .02f, 10, 0, 1,
                    VfxParts.argb(a * .35f, WHITE), VfxParts.argb(0, WHITE)));
        }
    }

    /**
     * 通天火柱：炽白的芯、暖金的火、外面一层混了同伴主色的光；外缠每位参战同伴一条螺旋光带往上转；冲到顶在云层上捅开一圈云洞；
     * 地上再推开一圈白色气浪。Cut 8 化作星火散掉。
     */
    private void pillar(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 p0) {
        float rise = HeroFinisherTimeline.pillar(age), fade = HeroFinisherTimeline.pillarFade(age);
        if (rise <= 0 || fade <= .003f) return;
        float vis = smooth(RISE, RISE + 2, age) * fade;
        boolean cave = show.far(age).cave();
        float rp = RADIUS * (cave ? .4f : 1) * (1 + .35f * smooth(RISE, RISE + 6, age) - .25f * smooth(RISE + 10, FALL, age));
        float h = HEIGHT * rise;
        Vec3 base = p0.add(0, -2, 0), top = p0.add(0, h, 0);
        float v = -age * .09f;
        int outer = HeroFinisherTimeline.blend(show.accents);
        COLUMN.submit(stack, out, (p, buf) -> VfxParts.cylinder(p, buf, base, rp * 1.35f, h + 2, 40, age * .004f, 4, v * .6f, 3,
                VfxParts.argb(vis * .55f, outer), VfxParts.argb(vis * .25f, GOLD)));
        FLAME.submit(stack, out, (p, buf) -> VfxParts.cylinder(p, buf, base, rp, h + 2, 40, age * .01f, 3, v * 1.6f, 4,
                VfxParts.argb(vis * .9f, EMBER), VfxParts.argb(vis * .6f, GOLD)));
        COLUMN.submit(stack, out, (p, buf) -> VfxParts.cylinder(p, buf, base, rp * .55f, h + 2, 32, -age * .006f, 3, v * 2.2f, 3,
                VfxParts.argb(vis, WHITE), VfxParts.argb(vis * .85f, WARM)));
        GLOW.submit(stack, out, (p, buf) -> {
            VfxParts.streak(p, buf, view, base, top, rp * 2.9f, VfxParts.argb(vis * .5f, EMBER));
            VfxParts.streak(p, buf, view, base, top, rp * 1.5f, VfxParts.argb(vis * .8f, GOLD));
            VfxParts.streak(p, buf, view, base, top, rp * .7f, VfxParts.argb(vis * .9f, WHITE));
        });
        // 往上冲的头
        if (rise < 1) BURST.submit(stack, out, (p, buf) -> VfxParts.sprite(p, buf, view, top, rp * 2.6f, age * .1f, VfxParts.argb(vis * .85f, HOT)));
        helix(stack, out, view, age, p0, rp, h, vis);
        if (!cave) cloudHole(stack, out, view, age, p0, vis);
        Vec3 cam = view.cam();
        for (int i = 0; i < 14; i++) {
            Vec3 at = base.lerp(top, i / 13.0);
            GlowBloom.sprite((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z), rp * .55f, VfxParts.argb(Math.min(1, vis * .7f), GOLD));
        }
    }

    /** 火柱外面每位参战同伴一条螺旋光带（没有同伴时两条暖金的），一边转一边往上爬。 */
    private void helix(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 p0, float rp, float h, float vis) {
        int n = show.accents.length == 0 ? 2 : show.accents.length;
        float radius = rp * 1.75f, pitch = 26;
        for (Layer layer : new Layer[]{BODY, STREAK}) layer.submit(stack, out, (p, buf) -> {
            for (int i = 0; i < n; i++) {
                boolean body = layer == BODY;
                int color = body ? (show.deep.length == 0 ? DEEP_GOLD : show.deep[i]) : show.accents.length == 0 ? GOLD : show.accents[i];
                Vec3 prev = null;
                for (float y = 0; y <= h; y += 2.2f) {
                    double th = Math.PI * 2 * (y / pitch) + i * Math.PI * 2 / n - age * .16;
                    Vec3 at = p0.add(Math.cos(th) * radius, y, Math.sin(th) * radius);
                    if (prev != null) {
                        float a = vis * (.55f + .45f * Mth.sin(y * .25f - age * .6f)) * (1 - smooth(h - 12, h, y));
                        if (body) VfxParts.streak(p, buf, view, prev, at, 1.5f, VfxParts.argb(.75f * a, color));
                        else {
                            VfxParts.streak(p, buf, view, prev, at, .8f, VfxParts.argb(.85f * a, color));
                            VfxParts.streak(p, buf, view, prev, at, .25f, VfxParts.argb(.7f * a, WHITE));
                        }
                    }
                    prev = at;
                }
            }
        });
    }

    /** 火柱捅穿云层：顶上一圈白色的冲击云环往外推，中间一团亮光。 */
    private void cloudHole(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 p0, float vis) {
        float k = (age - (RISE + 7)) / 26;
        if (k < 0) return;
        k = Math.min(k, 1);
        Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1), at = p0.add(0, HEIGHT * .9, 0);
        float r = 8 + 44 * (1 - (1 - k) * (1 - k));
        float a = vis * (1 - .55f * k);
        RING.submit(stack, out, (p, buf) -> {
            VfxParts.flat(p, buf, at, x, z, r, 0, VfxParts.argb(a, WHITE));
            VfxParts.flat(p, buf, at.add(0, -2, 0), x, z, r * .7f, 0, VfxParts.argb(a * .6f, WARM));
        });
        FIRE_RING.submit(stack, out, (p, buf) -> VfxParts.flat(p, buf, at.add(0, .5, 0), x, z, r * .9f, age * .01f, VfxParts.argb(a * .6f, GOLD)));
        GLOW.submit(stack, out, (p, buf) -> VfxParts.flat(p, buf, at, x, z, r * .75f, 0, VfxParts.argb(a * .35f, WARM)));
        WIND.submit(stack, out, (p, buf) -> VfxParts.cylinder(p, buf, at.add(0, -3, 0), r * .95f, 6, 64, age * .02f, 10, 0, 1,
                VfxParts.argb(a * .45f, WHITE), VfxParts.argb(0, WHITE)));
    }

    // ---------------------------------------------------------------- Cut 8：星火

    /** 火柱化作漫天落下的暖金星火和同伴主色的光屑；地上的星芒余烬一闪一闪地暗下去。 */
    private void sparks(PoseStack stack, SubmitNodeCollector out, VfxParts.View view, float age, Vec3 p0) {
        if (age < FALL - 8) return;
        float end = 1 - smooth(LENGTH - 6, LENGTH, age);
        // 洞里：星火从屋顶底下落、只落在近处
        boolean cave = show.far(age).cave();
        float spread = cave ? .35f : 1, lift = cave ? Math.max(1.5f, show.headroom - .5f) / 86 : 1;
        STREAK.submit(stack, out, (p, buf) -> {
            for (Spark s : sparks) {
                float t = age - s.born;
                if (t < 0) continue;
                Vec3 at = at(s, age, p0, spread, lift);
                if (at.y < p0.y) continue;
                float a = smooth(0, 2, t) * end * smooth((float) p0.y, (float) p0.y + 3, (float) at.y) * near(view, at);
                VfxParts.streak(p, buf, view, at.add(0, s.speed * 2.5, 0), at, s.size, VfxParts.argb(.85f * a, s.color));
            }
        });
        STAR.submit(stack, out, (p, buf) -> {
            for (int i = 0; i < sparks.size(); i += 3) {
                Spark s = sparks.get(i);
                float t = age - s.born;
                if (t < 0) continue;
                Vec3 at = at(s, age, p0, spread, lift);
                if (at.y < p0.y) continue;
                float a = smooth(0, 2, t) * end * near(view, at);
                VfxParts.sprite(p, buf, view, at, s.size * 1.5f, age * .1f, VfxParts.argb(.8f * a, s.color));
            }
            float glow = smooth(FALL - 4, FALL + 4, age) * end;
            if (glow > 0) for (Ember e : embers) {
                float tw = .5f + .5f * Mth.sin(age * .5f + e.phase);
                Vec3 at = p0.add(e.offset.scale(spread));
                VfxParts.flat(p, buf, at, new Vec3(1, 0, 0), new Vec3(0, 0, 1), e.size * (.7f + .3f * tw), e.phase, VfxParts.argb(.85f * glow * tw * near(view, at), e.color));
            }
        });
    }

    // ---------------------------------------------------------------- 工具

    /** 这一颗星火此刻在哪：{@code spread} 收拢水平的范围，{@code lift} 压低起落的高度（洞里）。 */
    private static Vec3 at(Spark s, float age, Vec3 p0, float spread, float lift) {
        Vec3 rel = s.at(age);
        return p0.add(rel.x * spread, rel.y * lift, rel.z * spread);
    }

    /** 贴着镜头的星火淡掉（镜头从星火里飞过时不糊一脸）。 */
    private static float near(VfxParts.View view, Vec3 at) {
        return smooth(2, 9, (float) at.distanceTo(view.cam()));
    }

    /** 和 {@code n} 垂直的两条单位向量。 */
    private static Vec3[] basis(Vec3 n) {
        Vec3 a = Math.abs(n.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = a.cross(n).normalize();
        return new Vec3[]{u, n.cross(u).normalize()};
    }
}
