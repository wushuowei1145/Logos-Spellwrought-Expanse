package dev.logos.dimension.client.explosion;

import static dev.logos.dimension.client.explosion.Draw.DEEP;
import static dev.logos.dimension.client.explosion.Draw.LIGHT;
import static dev.logos.dimension.client.explosion.Draw.MAIN;
import static dev.logos.dimension.client.explosion.Draw.WHITE;
import static dev.logos.dimension.client.explosion.Draw.argb;
import static dev.logos.dimension.client.explosion.Draw.bell;
import static dev.logos.dimension.client.explosion.Draw.lerp;
import static dev.logos.dimension.client.explosion.Draw.smooth;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.CastPoints;
import dev.logos.client.VfxParts;
import dev.logos.dimension.client.screenfx.GlowBloom;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.explosion.ExplosionMagic;
import dev.logos.dimension.explosion.ExplosionTimeline;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 一个人的爆裂魔法吟唱。照着 Logos 巨型蓄力的结构做（杖头：汇聚的光点、旋入的光痕、一阵阵冲击波、从远处汇来的条带、收拢的高能球；
 * 身上：多层法阵、光柱、上升的光痕、扩散的波浪、被卷上天的地面碎块），换成动画里的深蓝，并且：
 * <ul>
 *   <li>条带从远得多的地方汇来（30~55 格），只弯 20~40 度，越远越宽越淡，动画里那种蓝；</li>
 *   <li>目的地上空一个接一个出现红色法阵（{@link SkyCircles}）；</li>
 *   <li>后半程出现各色的十字闪，和条带一样快地飞向杖头，身边也有几颗在原地闪；</li>
 *   <li>蓄满的那一下，一大股魔力从身上冲出去：杖头一闪，球形的冲击和地面的风墙推开，镜头一震；
 *       之后魔力不停地从身上往外往上涌（更高更快的光柱、上升的光痕、往外冲的光点，施法者自己画面四周往上流的蓝光）。</li>
 * </ul>
 * 杖头就是爆裂魔杖上的红宝珠（模型里的 {@code wand_tip}，由 Logos 的 {@link CastPoints} 给出）。发光的层都交给 {@link PostVfx}，
 * 在周围变暗之后才画，所以施法者看上去很亮；地面碎块是实物，照常在世界里画。
 */
final class ChargeView {
    enum Phase { CHARGING, HELD, FIZZLED, RELEASED }

    /** 杖头汇聚的半径、蓄满时能量球的半径。 */
    private static final float RADIUS = 1.4f, BALL = .3f;
    private static final int STRIPS = 14, FIZZLE = 24, RELEASE = 26;

    private enum Kind { SPARK, SPIRAL, SPARKLE, TWINKLE, OUT, LEAK, GUSH }

    private record Mote(Kind kind, float born, float life, Vec3 dir, Vec3 a, Vec3 b, float theta0, float omega, float r0, float size, int color) {
        float k(float now) { return (now - born) / life; }

        /** 相对杖头（或施法者脚下，TWINKLE）的位置。 */
        Vec3 at(float age) {
            float k = Mth.clamp(age / life, 0, 1);
            return switch (kind) {
                case SPARK -> dir.scale(r0 * (1 - k));
                case SPIRAL -> orbit(age, r0 * (1 - k));
                // 十字闪：和条带一样快，越靠近越快
                case SPARKLE -> dir.scale(r0 * (1 - k) * (1 - k));
                case TWINKLE -> dir.scale(r0);
                case OUT -> dir.scale(r0 + omega * (1 - Math.exp(-age / 5)));
                case LEAK -> dir.scale(r0 + .2f * k).add(0, .6 * k, 0);
                // 从身上涌出的魔力：往外往上冲，越走越慢，再被热气托着往上飘
                case GUSH -> dir.scale(r0 + omega * age * (1 - .4f * k)).add(0, .012 * age * age, 0);
            };
        }

        private Vec3 orbit(float age, float r) {
            float th = theta0 + omega * age;
            return a.scale(Mth.cos(th) * r).add(b.scale(Mth.sin(th) * r));
        }
    }

    private record Strip(Vec3 a, Vec3 b, float theta0, float twist, float length, float swirl, float width, float wave) {}

    /** 被卷上天的一块地面（{@code sprite} 为 null 时是一个光点）。 */
    private record Lifted(float born, float life, float angle, float omega, float radius, float rise, float size, Quaternionf turn,
            @Nullable TextureAtlasSprite sprite, int color) {
        Vec3 at(Vec3 feet, float now) {
            float a = now - born, k = Mth.clamp(a / life, 0, 1);
            float th = angle + omega * a;
            float r = radius * (1 - .3f * k);
            float y = rise * (1 - (float) Math.exp(-a / (life * .45f)));
            return feet.add(Mth.cos(th) * r, y + .05, Mth.sin(th) * r);
        }
    }

    final int caster;
    Phase phase = Phase.CHARGING;
    private float from, progress;
    private float updated;
    boolean full;
    Vec3 target;
    private Vec3 shownTarget;
    float radius;
    private final float born;
    private float since;
    final SkyCircles circles = new SkyCircles();
    private final List<Strip> strips = new ArrayList<>();
    private final List<Mote> motes = new ArrayList<>();
    private final List<Lifted> lifted = new ArrayList<>();
    private final float[] owed = new float[Kind.values().length];
    private float owedPieces, owedLights;
    private final RandomSource random = RandomSource.create();
    private Vec3 tip = Vec3.ZERO, feet = Vec3.ZERO, look = new Vec3(0, 0, 1);
    private float height = 1.8f;

    ChargeView(int caster, Vec3 target, float radius, float now) {
        this.caster = caster;
        this.target = this.shownTarget = target;
        this.radius = radius;
        this.born = this.since = this.updated = now;
        for (int i = 0; i < STRIPS; i++) {
            // 大多从四周和上方来；每条在路上只弯 20~40 度（条带有几十格长，弯得多了就成了绕着人转的圈），长的那几条一直伸到几十格外
            Vec3 n = Draw.unit(random).add(0, .55, 0).normalize();
            Vec3[] plane = Draw.basis(n);
            strips.add(new Strip(plane[0], plane[1], random.nextFloat() * Mth.TWO_PI,
                    (random.nextBoolean() ? 1 : -1) * (Mth.PI / 9 + Mth.PI / 9 * random.nextFloat()),
                    30 + 25 * random.nextFloat(), (random.nextBoolean() ? 1 : -1) * (.001f + .0015f * random.nextFloat()),
                    1.1f + .7f * random.nextFloat(), random.nextFloat() * Mth.TWO_PI));
        }
    }

    /** 服务端的一次进度。 */
    void update(float p, boolean full, Vec3 target, float radius, float now) {
        this.from = progress(0, now);
        this.progress = p;
        this.updated = now;
        this.target = target;
        this.radius = radius;
        if (full && !this.full) {
            this.full = true;
            enter(Phase.HELD, now);
        }
    }

    void enter(Phase next, float now) {
        if (phase == next || phase == Phase.FIZZLED || phase == Phase.RELEASED) return;
        phase = next;
        since = now;
        if (next == Phase.FIZZLED) for (int i = 0; i < 8; i++) motes.add(mote(now + random.nextFloat() * 6, Kind.LEAK));
        if (next == Phase.RELEASED) for (int i = 0; i < 46; i++) motes.add(mote(now + random.nextFloat() * 2, Kind.OUT));
        if (next == Phase.HELD) {
            // 蓄满：一大股魔力从身上冲出去，附近的镜头跟着一震
            for (int i = 0; i < 64; i++) motes.add(gush(now + random.nextFloat() * 2, true));
            ExplosionClient.shake(feet.add(0, height * .5, 0), 6, .3f);
        }
    }

    /** 注入的进度，两次更新之间插值。 */
    float progress(float pt, float now) {
        if (full) return 1;
        float t = Mth.clamp((now + pt - updated) / 2f, 0, 1);
        return Mth.lerp(t, from, progress);
    }

    float phaseAge(float now) {
        return now - since;
    }

    Vec3 tip() {
        return tip;
    }

    Vec3 shownTarget() {
        return shownTarget;
    }

    /** 每个客户端刻。返回 false 时丢掉。 */
    boolean tick(Level level, float now) {
        motes.removeIf(m -> now - m.born > m.life);
        lifted.removeIf(l -> now - l.born > l.life);
        Entity e = level.getEntity(caster);
        if (e instanceof LivingEntity living) {
            feet = living.position();
            look = living.getLookAngle();
            height = living.getBbHeight();
        }
        // 目的地慢慢滑过去，法阵不会一下跳走
        shownTarget = shownTarget.add(target.subtract(shownTarget).scale(.25));
        float p = progress(0, now);
        circles.show(phase == Phase.RELEASED ? ExplosionTimeline.CIRCLE_HEIGHT.length : ExplosionMagic.circles(p), now);
        if (phase == Phase.CHARGING || phase == Phase.HELD) {
            boolean held = phase == Phase.HELD;
            float rate = held ? .4f : 1 - .5f * smooth(.75f, 1, p);
            spawn(now, Kind.SPARK, 6 * rate);
            spawn(now, Kind.SPIRAL, 1.8f * rate);
            // 后半程的十字闪：越往后越多
            float late = held ? .8f : smooth(.55f, .8f, p);
            spawn(now, Kind.SPARKLE, 3.2f * late);
            spawn(now, Kind.TWINKLE, 1.6f * late);
            // 蓄满后魔力不停地从身上往外往上涌
            if (held) spawn(now, Kind.GUSH, 5);
            float more = held ? 1 : smooth(.05f, .4f, p);
            for (owedPieces += 1.3f * more; owedPieces >= 1; owedPieces--) piece(level, now);
            for (owedLights += 2.4f * more; owedLights >= 1; owedLights--) light(now);
            return true;
        }
        float age = now - since;
        return !motes.isEmpty() || age < (phase == Phase.RELEASED ? RELEASE : FIZZLE);
    }

    private void spawn(float now, Kind kind, float perTick) {
        float n = owed[kind.ordinal()] += perTick;
        for (; n >= 1; n--) motes.add(mote(now + random.nextFloat(), kind));
        owed[kind.ordinal()] = n;
    }

    private Mote mote(float born, Kind kind) {
        RandomSource r = random;
        Vec3 dir = Draw.unit(r);
        Vec3[] plane = Draw.basis(Draw.unit(r));
        float theta = r.nextFloat() * Mth.TWO_PI, spin = r.nextBoolean() ? 1 : -1;
        int color = Draw.SPARKLE[r.nextInt(Draw.SPARKLE.length)];
        return switch (kind) {
            case SPARK -> new Mote(kind, born, 6 + r.nextInt(4), dir, plane[0], plane[1], 0, 0, .55f + .45f * r.nextFloat(), .03f + .04f * r.nextFloat(), WHITE);
            case SPIRAL -> new Mote(kind, born, 10 + r.nextInt(5), dir, plane[0], plane[1], theta, spin * (.12f + .1f * r.nextFloat()),
                    .8f + .2f * r.nextFloat(), .05f + .03f * r.nextFloat(), WHITE);
            case SPARKLE -> {
                // 从远处（多在上方）飞来，十几刻到杖头
                Vec3 from = dir.add(0, .5, 0).normalize();
                yield new Mote(kind, born, 9 + r.nextInt(7), from, plane[0], plane[1], theta, 0, 9 + 18 * r.nextFloat(), .35f + .35f * r.nextFloat(), color);
            }
            case TWINKLE -> {
                Vec3 flat = new Vec3(dir.x, Math.abs(dir.y) * .8 + .1, dir.z).normalize();
                yield new Mote(kind, born, 8 + r.nextInt(8), flat, plane[0], plane[1], theta, 0, 1.5f + 6 * r.nextFloat(), .25f + .3f * r.nextFloat(), color);
            }
            case OUT -> new Mote(kind, born, 14 + r.nextInt(10), dir, plane[0], plane[1], 0, 2.2f + 2.6f * r.nextFloat(), .2f, .05f + .05f * r.nextFloat(), color);
            case LEAK -> new Mote(kind, born, 14 + r.nextInt(10), dir, plane[0], plane[1], theta, 0, .1f + .1f * r.nextFloat(), .03f + .03f * r.nextFloat(), MAIN);
            case GUSH -> gush(born, false);
        };
    }

    /**
     * 从身上涌出的一股魔力：起点在身体周围的某个高度（{@code theta0} 是占身高的比例），往外往上冲。
     * {@code burst} 是蓄满那一下：快得多、粗得多、几乎是水平地冲出去。
     */
    private Mote gush(float born, boolean burst) {
        RandomSource r = random;
        float a = r.nextFloat() * Mth.TWO_PI;
        float up = burst ? -.1f + .5f * r.nextFloat() : .5f + .9f * r.nextFloat();
        Vec3 dir = new Vec3(Mth.cos(a), up, Mth.sin(a)).normalize();
        float scale = height / 1.8f;
        int color = r.nextInt(4) == 0 ? WHITE : r.nextBoolean() ? LIGHT : MAIN;
        return new Mote(Kind.GUSH, born, burst ? 10 + r.nextInt(8) : 12 + r.nextInt(10), dir, Vec3.ZERO, Vec3.ZERO, r.nextFloat(),
                (burst ? 1.1f + .9f * r.nextFloat() : .25f + .3f * r.nextFloat()) * scale, .35f * scale,
                (burst ? .14f + .1f * r.nextFloat() : .07f + .06f * r.nextFloat()) * scale, color);
    }

    /** 蓄满了多久（刻）；还没蓄满或已经结束时为 -1。 */
    float heldAge(float now) {
        return phase == Phase.HELD ? now - since : -1;
    }

    /** 胸口：蓄满时推开的空气从这里扩出去。 */
    Vec3 chest() {
        return feet.add(0, height * .55f, 0);
    }

    float bodyScale() {
        return height / 1.8f;
    }

    /** 脚下周围的一块地面被卷起来，带着它自己的方块贴图。 */
    private void piece(Level level, float now) {
        if (lifted.size() > 90) return;
        float angle = random.nextFloat() * Mth.TWO_PI, r = 1.6f + 4.4f * random.nextFloat();
        float scale = height / 1.8f;
        TextureAtlasSprite sprite = VfxParts.blockSprite(level, feet.x + Mth.cos(angle) * r * scale, feet.y, feet.z + Mth.sin(angle) * r * scale);
        if (sprite == null) return;
        Quaternionf turn = new Quaternionf().rotateXYZ(random.nextFloat() * 6.3f, random.nextFloat() * 6.3f, random.nextFloat() * 6.3f);
        lifted.add(new Lifted(now + random.nextFloat(), 50 + random.nextInt(30), angle, .025f + .02f * random.nextFloat(), r * scale,
                (1.6f + 3f * random.nextFloat()) * scale, (.05f + .08f * random.nextFloat()) * scale, turn, sprite, 0));
    }

    private void light(float now) {
        float scale = height / 1.8f;
        lifted.add(new Lifted(now + random.nextFloat(), 26 + random.nextInt(22), random.nextFloat() * Mth.TWO_PI, .05f + .05f * random.nextFloat(),
                (.7f + 3.2f * random.nextFloat()) * scale, (2.4f + 2.8f * random.nextFloat()) * scale, (.04f + .05f * random.nextFloat()) * scale,
                new Quaternionf(), null, random.nextInt(3) == 0 ? LIGHT : MAIN));
    }

    // ---- 绘制 ------------------------------------------------------------------------------------

    /**
     * 每帧。{@code layers} 是世界里画实物（碎块）的收集器，其余全交 {@link PostVfx}。
     */
    void submit(Level level, VfxParts.View view, float pt, float now, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        Entity e = level.getEntity(caster);
        if (e != null && e.isAlive()) {
            tip = CastPoints.of(e, pt);
            feet = e.getPosition(pt);
            look = e.getViewVector(pt);
        }
        float t = now + pt;
        Minecraft mc = Minecraft.getInstance();
        boolean firstPerson = e != null && e == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
        float near = firstPerson ? .5f : 1;
        float p = progress(pt, now);
        float age = t - since;
        float scale = ExplosionTimeline.scale(radius);
        // 法阵：吟唱中随进度变亮，蓄满再亮一截；中断时淡出；放出去之后交给 BlastView
        float bright = switch (phase) {
            case CHARGING -> .55f + .45f * p;
            case HELD -> 1.2f + .1f * Mth.sin(t * .3f);
            default -> 1;
        };
        float fade = phase == Phase.FIZZLED ? Math.max(0, 1 - age / FIZZLE) : phase == Phase.RELEASED ? 0 : 1;
        circles.submit(stack, view, shownTarget, scale, t, bright, fade);

        // 离得太远就只看得到天上的法阵
        if (view.cam().distanceTo(feet) > 192) return;
        float clear = VfxParts.fade(view.cam(), tip, .15, .45);
        PoseStack.Pose pose = stack.last();
        body(pose, view, t, p, age, firstPerson, layers, stack);
        motes(pose, view, t, near, clear, firstPerson);
        if (phase == Phase.CHARGING) {
            shockwaves(pose, view, t, p, near, clear);
            strips(pose, view, t, p, near, 1);
            closingSphere(pose, view, t, p, near, firstPerson);
        }
        if (phase == Phase.FIZZLED && age < 8) strips(pose, view, t, p, near, 1 - age / 8);
        if (phase == Phase.HELD && age < 30) fullBurst(pose, view, age, near, clear, firstPerson);
        ball(pose, view, t, p, age, near, clear, firstPerson);
    }

    /**
     * 蓄满的那一下：杖头一闪；两圈面向镜头的光环从胸口扩开，是一个球形的冲击（别人还看得到后期里空气被推开的折射，
     * 见 {@link ExplosionScreen}）。地面上卷开的风墙由 {@link #waves} 画，冲出去的魔力是 GUSH 光点。
     */
    private void fullBurst(PoseStack.Pose pose, VfxParts.View view, float age, float near, float clear, boolean firstPerson) {
        float fk = age / 9;
        if (fk < 1) {
            float f = (1.4f + 5 * fk) * near;
            if (firstPerson) f = Math.min(f, (float) view.cam().distanceTo(tip) * .8f);
            float a = (1 - fk) * (1 - fk) * clear * (firstPerson ? .45f : 1);
            VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("fire_impact/flash"), firstPerson), view, tip, f, age * .04f, argb(a, LIGHT));
            VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("arcane_impact/flash"), firstPerson), view, tip, f * .55f, 0, argb(a, WHITE));
            if (!firstPerson) GlowBloom.sprite((float) (tip.x - view.cam().x), (float) (tip.y - view.cam().y), (float) (tip.z - view.cam().z),
                    f * 1.3f, argb(Math.min(1, a), LIGHT));
        }
        // 施法者自己就在球心：这两圈只给别人看
        if (firstPerson) return;
        float scale = height / 1.8f;
        Vec3 chest = chest();
        VertexConsumer soft = PostVfx.add(PostVfx.logos("heal/ring"), false);
        VertexConsumer sharp = PostVfx.add(PostVfx.logos("void_ray/ring"), false);
        for (int i = 0; i < 2; i++) {
            float k = (age - i * 3) / 20;
            if (k < 0 || k > 1) continue;
            float size = (1 + 15 * (1 - (1 - k) * (1 - k))) * scale;
            float a = (1 - k) * smooth(0, .08f, k) * VfxParts.fade(view.cam(), chest, .5, size * .6 + .5);
            VfxParts.sprite(pose, soft, view, chest, size * 1.05f, i * 1.3f, argb(a * .55f, i == 0 ? LIGHT : MAIN));
            VfxParts.sprite(pose, sharp, view, chest, size, -i * .7f, argb(a * .7f, lerp(WHITE, LIGHT, .4f)));
        }
    }

    private void motes(PoseStack.Pose pose, VfxParts.View view, float now, float near, float clear, boolean firstPerson) {
        if (motes.isEmpty()) return;
        float R = RADIUS * near;
        VertexConsumer glow = PostVfx.add(PostVfx.logos("heal/glow"), false);
        VertexConsumer streak = PostVfx.add(PostVfx.logos("force/streak"), false);
        VertexConsumer star = PostVfx.add(PostVfx.logos("void_ray/star"), false);
        VertexConsumer flare = PostVfx.add(PostVfx.logos("arcane_impact/star"), false);
        for (Mote m : motes) {
            float k = m.k(now);
            if (k < 0 || k > 1) continue;
            float age = now - m.born;
            switch (m.kind) {
                case SPARK -> {
                    Vec3 at = tip.add(m.at(age).scale(R));
                    VfxParts.sprite(pose, glow, view, at, m.size * near * bell(k) * 1.6f, 0, argb(clear * .95f, lerp(WHITE, LIGHT, k * .7f)));
                }
                case SPIRAL -> trail(pose, streak, view, m, now, R, near, clear);
                case SPARKLE -> {
                    Vec3 head = tip.add(m.at(age));
                    Vec3 tail = tip.add(m.at(Math.max(0, age - 2.2f)));
                    float a = Math.min(1, k * 6) * (1 - smooth(.85f, 1, k)) * VfxParts.fade(view.cam(), head, .4, 1.5);
                    float s = m.size * (firstPerson ? .6f : 1);
                    VfxParts.streak(pose, streak, view, tail, head, s * .35f, argb(a * .8f, m.color));
                    VfxParts.sprite(pose, star, view, head, s * .45f, 0, argb(a, lerp(m.color, WHITE, .35f)));
                    VfxParts.sprite(pose, flare, view, head, s * 1.3f, 0, argb(a * .9f, m.color));
                }
                case TWINKLE -> {
                    Vec3 at = feet.add(0, 1.2, 0).add(m.at(age));
                    float a = bell(k) * VfxParts.fade(view.cam(), at, .6, 2);
                    float s = m.size * (.6f + .4f * bell(k));
                    VfxParts.sprite(pose, star, view, at, s * .4f, 0, argb(a, lerp(m.color, WHITE, .4f)));
                    VfxParts.sprite(pose, flare, view, at, s * 1.5f, 0, argb(a * .85f, m.color));
                }
                case OUT -> {
                    Vec3 at = tip.add(m.at(age).scale(near));
                    Vec3 tail = tip.add(m.at(Math.max(0, age - 2.5f)).scale(near));
                    VfxParts.streak(pose, streak, view, tail, at, m.size * near * 1.4f, argb((1 - k) * clear, m.color));
                    VfxParts.sprite(pose, glow, view, at, m.size * near * 1.8f * (1 - k), 0, argb((1 - k) * clear, lerp(WHITE, m.color, k)));
                }
                case LEAK -> {
                    Vec3 at = tip.add(m.at(age).scale(near));
                    float flicker = .55f + .45f * Mth.sin(now * 1.7f + m.theta0 * 9);
                    VfxParts.sprite(pose, glow, view, at, m.size * near * (1 - k * .5f), 0, argb(.6f * (1 - k) * flicker * clear, lerp(MAIN, DEEP, .5f)));
                }
                case GUSH -> {
                    Vec3 base = feet.add(0, m.theta0 * height, 0);
                    Vec3 head = base.add(m.at(age));
                    Vec3 tail = base.add(m.at(Math.max(0, age - 2.4f)));
                    float a = Math.min(1, k * 5) * (1 - k) * VfxParts.fade(view.cam(), head, .5, 2) * (firstPerson ? .6f : 1);
                    VfxParts.streak(pose, streak, view, tail, head, m.size, argb(a * .85f, lerp(m.color, DEEP, k * .6f)));
                    VfxParts.sprite(pose, glow, view, head, m.size * 2.2f, 0, argb(a * .7f, lerp(WHITE, m.color, .5f + .5f * k)));
                }
            }
        }
    }

    /** 一个光点的拖尾：过去 {@code length} 刻走过的地方，头上又亮又宽，往后变细。 */
    private void trail(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Mote m, float now, float R, float near, float clear) {
        int n = 8;
        Vec3[] pts = new Vec3[n];
        float[] half = new float[n], u = new float[n];
        int[] colors = new int[n];
        float age = now - m.born, life = bell(Mth.clamp(m.k(now), 0, 1));
        for (int i = 0; i < n; i++) {
            float f = i / (float) (n - 1);
            pts[i] = tip.add(m.at(Math.max(0, age - 5 * (1 - f))).scale(R));
            half[i] = m.size * near * (.15f + .85f * f) * .5f;
            u[i] = f;
            colors[i] = argb(clear * life * f * f, lerp(MAIN, LIGHT, f));
        }
        Draw.ribbon(pose, buf, view, pts, half, colors, u, n);
    }

    /** 一阵阵从杖头扩出去的冲击波（Star aura 的 ShockWave，8 帧图集），进度过六成渐少。 */
    private void shockwaves(PoseStack.Pose pose, VfxParts.View view, float now, float p, float near, float clear) {
        float life = 24, dwindle = 1 - smooth(.6f, .85f, p);
        if (dwindle <= 0) return;
        VertexConsumer buf = PostVfx.add(PostVfx.logos("charge/shockwave"), false);
        int color = lerp(LIGHT, MAIN, .5f);
        for (int i = (int) Math.floor((now - life) / life) + 1; i <= Math.floor(now / life); i++) {
            float k = (now - i * life) / life;
            if (i < 0 || k < 0 || k > 1) continue;
            float size = RADIUS * near * 1.1f * (1 + .15f * k);
            float alpha = clear * dwindle * smooth(0, .18f, k) * (1 - smooth(.85f, 1, k)) * .9f;
            float frame = Math.min(k * 8, 7.999f);
            int f0 = (int) frame, f1 = Math.min(f0 + 1, 7);
            float mix = frame - f0;
            float roll = i * 2.1f;
            flipbook(pose, buf, view, tip, size, roll, f0, argb(alpha * (1 - mix), color));
            if (mix > 0) flipbook(pose, buf, view, tip, size, roll, f1, argb(alpha * mix, color));
        }
    }

    private static void flipbook(PoseStack.Pose pose, VertexConsumer buf, VfxParts.View view, Vec3 c, float r, float roll, int frame, int color) {
        float u0 = (frame % 4) / 4f, v0 = (frame / 4) / 2f;
        VfxParts.sprite(pose, buf, view, c, r, r, roll, u0, v0, u0 + .25f, v0 + .5f, color);
    }

    /** 从很远处汇来的条带：越远越宽越淡，进度后段收细成线，蓄满时消失。四层各自往杖头流。 */
    private void strips(PoseStack.Pose pose, VfxParts.View view, float now, float p, float near, float fadeOut) {
        float intro = smooth(0, .12f, p) * fadeOut;
        float thin = 1 - smooth(.7f, .97f, p);
        float gone = 1 - smooth(.95f, 1, p);
        if (intro * gone <= 0) return;
        int n = 30;
        Vec3[] pts = new Vec3[n];
        float[] half = new float[n], u = new float[n];
        int[] colors = new int[n];
        String[] texture = {"physical_ray/wind", "ward/noise", "void_beam/flow", "physical_ray/wind"};
        float[] tiles = {4, 3, 7, 5}, speed = {.32f, .26f, .8f, .55f};
        // 整条慢慢转动，靠近杖头的一端稍快一点；按吟唱开始后的时间算（不能用客户端的总刻数：玩得越久，两端差得越多，条带就卷成了圈）
        float spun = now - born;
        for (int layer = 0; layer < 4; layer++) {
            VertexConsumer buf = PostVfx.add(PostVfx.logos(texture[layer]), false);
            for (Strip s : strips) {
                for (int i = 0; i < n; i++) {
                    float f = i / (float) (n - 1);
                    float reach = s.length * (float) Math.pow(f, 1.2) + .02f;
                    float th = s.theta0 + s.twist * f + s.swirl * spun * (1 - .3f * f);
                    // 动画里条带像水流一样起伏
                    float wave = Mth.sin(f * 7 + now * .12f + s.wave) * f * f * 2.2f;
                    pts[i] = tip.add(s.a.scale(Mth.cos(th) * reach)).add(s.b.scale(Mth.sin(th) * reach)).add(0, wave, 0);
                    float width = (.015f + 2.1f * (float) Math.pow(f, 1.25f)) * s.width * (.06f + .94f * thin);
                    u[i] = f * tiles[layer] + now * speed[layer] + s.theta0;
                    float alpha = (float) (Math.pow(1 - f, 1.3) * smooth(0, .03f, f)) * intro * gone * VfxParts.fade(view.cam(), pts[i], .25, 1.5);
                    switch (layer) {
                        case 0 -> {
                            half[i] = width;
                            colors[i] = argb(alpha * .5f, lerp(MAIN, DEEP, f));
                        }
                        case 1 -> {
                            half[i] = width * 1.15f;
                            colors[i] = argb(alpha * .4f, DEEP);
                        }
                        case 2 -> {
                            half[i] = width * .75f;
                            colors[i] = argb(alpha * .85f, lerp(LIGHT, MAIN, f));
                        }
                        default -> {
                            half[i] = Math.max(.008f * near, width * .2f);
                            colors[i] = argb(alpha * (.65f + .35f * (1 - thin)), lerp(WHITE, LIGHT, f * 1.5f));
                        }
                    }
                }
                Draw.ribbon(pose, buf, view, pts, half, colors, u, n);
            }
        }
    }

    /** 后半程：一个高能的球在杖头周围显现，向中心收拢，蓄满时收成能量球。 */
    private void closingSphere(PoseStack.Pose pose, VfxParts.View view, float now, float p, float near, boolean firstPerson) {
        float show = smooth(.45f, .65f, p);
        if (show <= 0) return;
        float k = smooth(.45f, 1, p);
        float r = Mth.lerp((float) Math.pow(k, 1.4), 4.6f, BALL) * (near < 1 ? .7f : 1);
        float seen = show * VfxParts.fade(view.cam(), tip, r, r * 2 + .1);
        if (seen <= 0) return;
        float spin = now * .05f;
        if (!firstPerson) VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), view, tip, r * 1.9f, 0, argb(seen * .4f, DEEP));
        VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("arcane_impact/flash"), false), view, tip, r, 0, argb(seen * .6f, MAIN));
        VertexConsumer plasma = PostVfx.add(PostVfx.logos("charge/plasma"), false);
        VfxParts.sprite(pose, plasma, view, tip, r * .96f, spin, argb(seen * .55f, LIGHT));
        VfxParts.sprite(pose, plasma, view, tip, r * .9f, -spin * 1.4f + 1, argb(seen * .4f, MAIN));
    }

    /** 杖头的能量球：吟唱中长大，蓄满后脉动；释放时一闪炸开；中断时暗下去漏光熄灭。 */
    private void ball(PoseStack.Pose pose, VfxParts.View view, float now, float p, float age, float near, float clear, boolean firstPerson) {
        float r = BALL * near, alpha = 1;
        switch (phase) {
            case CHARGING -> {
                float grow = smooth(.8f, 1, p);
                r *= .3f + .7f * grow;
                alpha = .35f + .65f * grow;
                float soft = BALL * near * (3 + 3 * p);
                VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("heal/glow"), firstPerson), view, tip, soft, 0, argb(clear * (.3f + .4f * p), DEEP));
            }
            case HELD -> r *= 1 + .07f * Mth.sin(now * .5f);
            case RELEASED -> {
                float k = age / 7;
                if (k > 1) {
                    burst(pose, view, age - 5, near, clear, firstPerson);
                    return;
                }
                r = Mth.lerp(1 - (1 - k) * (1 - k), r, 1.6f * near);
                alpha = 1 - k * k;
                if (age > 5) burst(pose, view, age - 5, near, clear, firstPerson);
            }
            case FIZZLED -> {
                float k = age / FIZZLE;
                if (k > 1) return;
                float flicker = .6f + .4f * Mth.sin(age * 2.3f);
                r *= Math.max(.05f, (.25f + .75f * p) * (1 - k * .8f));
                alpha = (1 - k) * flicker * .7f;
            }
        }
        if (firstPerson) r = Math.min(r * .65f, (float) view.cam().distanceTo(tip) * .3f);
        float a = alpha * clear;
        if (a <= .003f || r <= 0) return;
        boolean dim = phase == Phase.FIZZLED;
        int light = dim ? lerp(LIGHT, DEEP, .5f) : LIGHT, main = dim ? DEEP : MAIN;
        float spin = now * .08f;
        // 第一人称杖头上的光画在手前面：红宝珠本身就在发光
        boolean front = firstPerson;
        // 第一人称杖头就在眼前：光晕和星芒收小，免得糊住半个画面
        float halo = firstPerson ? r * 2.4f : Math.min(r * 4.2f, r * 1.6f + .6f);
        VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("heal/glow"), front), view, tip, halo, 0, argb(a * (firstPerson ? .3f : .45f), main));
        float twinkle = .75f + .25f * Mth.sin(now * .7f);
        VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("lightning_beam/rays"), front), view, tip, r * (firstPerson ? 2.6f : 3.6f), spin * .5f, argb(a * .45f, main));
        VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("arcane_impact/star"), front), view, tip, r * (firstPerson ? 4 : 6) * twinkle, 0, argb(a * .7f, light));
        VertexConsumer ring = PostVfx.add(PostVfx.logos("void_ray/ring"), front);
        for (int i = 0; i < 3; i++) {
            float th = now * (.05f + .015f * i) + i * 2.1f;
            Vec3 n = new Vec3(Mth.cos(th), Mth.sin(th * 1.3f + i), Mth.sin(th)).normalize();
            Vec3[] q = Draw.basis(n);
            VfxParts.flat(pose, ring, tip, q[0], q[1], r * (1.55f + .2f * i), -spin * (1 + i * .3f), argb(a * .55f, lerp(light, main, .5f)));
        }
        VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("arcane_impact/flash"), front), view, tip, r * 1.15f, 0, argb(a * .9f, main));
        VertexConsumer plasma = PostVfx.add(PostVfx.logos("charge/plasma"), front);
        VfxParts.sprite(pose, plasma, view, tip, r * 1.02f, spin * 2, argb(a * .7f, light));
        VfxParts.sprite(pose, plasma, view, tip, r * .95f, -spin * 2.7f + 1, argb(a * .5f, main));
        VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("fire_beam/dot"), front), view, tip, r * .62f, 0, argb(a, WHITE));
        if (!firstPerson) GlowBloom.sprite((float) (tip.x - view.cam().x), (float) (tip.y - view.cam().y), (float) (tip.z - view.cam().z),
                r * 2.2f, argb(Math.min(1, a), lerp(LIGHT, MAIN, .4f)));
    }

    /** 放出时杖头的一闪：两圈光环飞快扩开。 */
    private void burst(PoseStack.Pose pose, VfxParts.View view, float t, float near, float clear, boolean firstPerson) {
        if (t < 0 || t > 22) return;
        float flashK = t / 7;
        if (flashK < 1) {
            float f = firstPerson ? Math.min(.35f, (1.2f + 1.8f * flashK) * near) : (1.2f + 1.8f * flashK) * near;
            float a = (1 - flashK) * (1 - flashK) * (firstPerson ? .35f : 1) * clear;
            VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("fire_impact/flash"), firstPerson), view, tip, f, t * .05f, argb(a, LIGHT));
        }
        VertexConsumer ring = PostVfx.add(PostVfx.logos("heal/ring"), false);
        for (int i = 0; i < 2; i++) {
            float k = (t - i * 3) / 16;
            if (k < 0 || k > 1) continue;
            float size = (.6f + 6.5f * (1 - (1 - k) * (1 - k))) * near;
            float a = (1 - k) * smooth(0, .1f, k) * VfxParts.fade(view.cam(), tip, .2, size * .8 + .2) * (firstPerson ? .3f : 1);
            VfxParts.sprite(pose, ring, view, tip, size, i, argb(a * .6f, i == 0 ? LIGHT : MAIN));
        }
    }

    // ---- 身上 ------------------------------------------------------------------------------------

    private void body(PoseStack.Pose pose, VfxParts.View view, float now, float p, float age, boolean firstPerson,
            IntFunction<OrderedSubmitNodeCollector> layers, PoseStack stack) {
        float scale = height / 1.8f;
        float show = switch (phase) {
            case CHARGING -> smooth(0, .2f, p) * smooth(0, 6, now - born);
            case HELD -> 1;
            case RELEASED -> {
                float k = age / 10;
                yield k < .2f ? 1 + k * 1.5f : Math.max(0, 1.3f * (1 - k) / .8f);
            }
            default -> Math.max(0, 1 - age / 12) * (.6f + .4f * Mth.sin(age * 2.1f));
        };
        if (show > 0) {
            circles(pose, now, scale, Math.min(1.2f, show));
            // 蓄满后魔力往上涌：光柱更高、流得更快，身上不停冒出上升的光痕
            float held = phase == Phase.HELD ? age : 0, boost = smooth(0, 10, held);
            if (!firstPerson) pillar(pose, now, scale, Math.min(1.2f, show) * smooth(.05f, .45f, Math.max(p, phase == Phase.HELD ? 1 : 0)), boost, held);
            if (phase == Phase.CHARGING) rising(pose, view, now, scale, show);
            if (phase == Phase.HELD) outflow(pose, view, now, scale, boost, firstPerson);
            swirl(pose, now, scale, Math.min(1, show));
        }
        waves(pose, now, scale, p, age);
        lifted(view, now, scale, phase == Phase.CHARGING || phase == Phase.HELD ? 1 : Math.max(0, 1 - age / 12), layers, stack);
    }

    private void circles(PoseStack.Pose pose, float now, float scale, float show) {
        Vec3 ground = feet.add(0, .03, 0), x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1);
        int main = lerp(MAIN, LIGHT, .25f);
        float a = show * (.85f + .15f * Mth.sin(now * .2f));
        float turn = now * .012f;
        VfxParts.flat(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), ground, x, z, 4.2f * scale, 0, argb(a * .35f, DEEP));
        VfxParts.flat(pose, PostVfx.add(PostVfx.logos("charge/circle_03"), false), ground, x, z, 3.6f * scale, turn * .7f, argb(a * .85f, main));
        VfxParts.flat(pose, PostVfx.add(PostVfx.logos("charge/circle_11"), false), ground.add(0, .01, 0), x, z, 2.5f * scale, -turn * 1.3f, argb(a * .75f, main));
        VfxParts.flat(pose, PostVfx.add(PostVfx.logos("charge/circle_15"), false), ground.add(0, .02, 0), x, z, 1.4f * scale, turn * 2.2f, argb(a * .8f, LIGHT));
        float bob = .04f * Mth.sin(now * .1f);
        VfxParts.flat(pose, PostVfx.add(PostVfx.logos("charge/circle_17"), false), feet.add(0, height * .5f + bob, 0), x, z, 1.2f * scale, -turn * 3,
                argb(a * .55f, main));
        VfxParts.flat(pose, PostVfx.add(PostVfx.logos("charge/circle_17"), false), feet.add(0, height * 1.18f - bob, 0), x, z, .75f * scale, turn * 4,
                argb(a * .5f, LIGHT));
    }

    /** {@code boost} 0~1 是蓄满后的加强，{@code held} 是蓄满了多少刻（多出来的流速按它累计，速度变化时贴图不会跳）。 */
    private void pillar(PoseStack.Pose pose, float now, float scale, float s, float boost, float held) {
        if (s <= .003f) return;
        float r = 1.3f * scale * (1 + .25f * boost), h = 4.8f * scale * (1 + 1.3f * boost), v = -now * .035f - .06f * held;
        VertexConsumer buf = PostVfx.add(PostVfx.logos("charge/pillar"), false);
        VfxParts.cylinder(pose, buf, feet, r, h, 28, now * .004f, 3, v, 1.2f, argb(s * .55f, MAIN), argb(0, DEEP));
        VfxParts.cylinder(pose, buf, feet, r * .82f, h * .8f, 24, .5f - now * .006f, 2, v * 1.6f, 1, argb(s * .45f, LIGHT), argb(0, MAIN));
        VfxParts.cylinder(pose, buf, feet, r * 1.35f, h * 1.15f, 32, now * .002f, 4, v * .7f, 1.4f, argb(s * .3f, DEEP), argb(0, DEEP));
        VfxParts.flat(pose, PostVfx.add(PostVfx.logos("heal/ring"), false), feet.add(0, .04, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r * 1.15f, 0,
                argb(s * .5f, LIGHT));
    }

    private void rising(PoseStack.Pose pose, VfxParts.View view, float now, float scale, float show) {
        float h = 4.8f * scale, r = 1.25f * scale;
        VertexConsumer buf = PostVfx.add(PostVfx.logos("force/streak"), false);
        for (int i = 0; i < 16; i++) {
            float period = 22 + (i * 7 % 11);
            float k = ((now + i * 13.7f) % period) / period;
            float th = i * 2.399f + now * .01f;
            float rr = r * (.55f + .4f * ((i * 37 % 10) / 10f));
            Vec3 head = feet.add(Mth.cos(th) * rr, h * k, Mth.sin(th) * rr);
            VfxParts.streak(pose, buf, view, head.add(0, -.5 * scale, 0), head, .07f * scale, argb(show * Mth.sin(k * Mth.PI) * .8f, LIGHT));
        }
    }

    private void waves(PoseStack.Pose pose, float now, float scale, float p, float age) {
        boolean running = phase == Phase.HELD || phase == Phase.CHARGING && p > .15f;
        boolean held = phase == Phase.HELD;
        List<float[]> live = new ArrayList<>();
        // 蓄满后一阵阵涌得更勤、推得更远
        float period = held ? 11 : 18, life = 24;
        if (running) {
            for (int i = (int) Math.floor((now - life) / period) + 1; i <= Math.floor(now / period); i++) {
                float k = (now - i * period) / life;
                if (i >= 1 && k >= 0 && k <= 1) live.add(new float[] {k, held ? 1.4f : 1});
            }
        }
        if (phase == Phase.RELEASED && age < 30) live.add(new float[] {age / 30, 1.6f});
        // 蓄满那一下的气浪：一堵风墙从脚下卷到二十几格外
        if (held && age < 34) live.add(new float[] {age / 34, 2.9f});
        if (live.isEmpty()) return;
        VertexConsumer wind = PostVfx.add(PostVfx.logos("physical_ray/wind"), false);
        VertexConsumer ring = PostVfx.add(PostVfx.logos("heal/ring"), false);
        for (float[] w : live) {
            float k = w[0], big = w[1];
            float r = (1 + 7.5f * big * (1 - (1 - k) * (1 - k))) * scale;
            float h = (.15f + .75f * (1 - k)) * scale * big;
            float a = (1 - k) * smooth(0, .12f, k) * .7f;
            VfxParts.cylinder(pose, wind, feet.add(0, .02, 0), r, h, 40, now * .02f, 6, 0, 1, argb(a, MAIN), argb(0, LIGHT));
            VfxParts.flat(pose, ring, feet.add(0, .05, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r * 1.08f, k, argb(a * 1.1f, LIGHT));
        }
    }

    /** 蓄满后魔力从身上不停往上涌：比吟唱时多、快、高的上升光痕，越往上散得越开；身边一团呼吸的光（第一人称不画，免得糊住画面）。 */
    private void outflow(PoseStack.Pose pose, VfxParts.View view, float now, float scale, float boost, boolean firstPerson) {
        if (boost <= 0) return;
        float h = 9 * scale, r = 1.5f * scale;
        VertexConsumer buf = PostVfx.add(PostVfx.logos("force/streak"), false);
        for (int i = 0; i < 26; i++) {
            float period = 12 + (i * 7 % 9);
            float k = ((now + i * 11.3f) % period) / period;
            float th = i * 2.399f + now * .02f;
            float rr = r * (.4f + .6f * ((i * 37 % 10) / 10f)) * (1 + .6f * k);
            Vec3 head = feet.add(Mth.cos(th) * rr, h * k, Mth.sin(th) * rr);
            float len = (.8f + .8f * k) * scale;
            VfxParts.streak(pose, buf, view, head.add(0, -len, 0), head, .09f * scale, argb(boost * Mth.sin(k * Mth.PI) * .85f, i % 3 == 0 ? WHITE : LIGHT));
        }
        if (firstPerson) return;
        float breathe = .75f + .25f * Mth.sin(now * .25f);
        VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), view, chest(), 3.4f * scale * breathe, 0, argb(boost * .3f * breathe, MAIN));
    }

    private void swirl(PoseStack.Pose pose, float now, float scale, float show) {
        VertexConsumer buf = PostVfx.add(PostVfx.logos("heal/swirl"), false);
        VfxParts.flat(pose, buf, feet.add(0, .08, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), 2.8f * scale, now * .09f, argb(show * .35f, MAIN));
        VfxParts.flat(pose, buf, feet.add(0, .1, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), 1.8f * scale, now * .13f + 1, argb(show * .3f, LIGHT));
    }

    private void lifted(VfxParts.View view, float now, float scale, float fadeOut, IntFunction<OrderedSubmitNodeCollector> layers, PoseStack stack) {
        if (lifted.isEmpty()) return;
        // 碎块是实物：在世界里画，跟着周围一起变暗
        layers.apply(0).submitCustomGeometry(stack, VfxParts.BLOCKS, (pose, buf) -> {
            for (Lifted l : lifted) {
                if (l.sprite == null) continue;
                Vec3 at = l.at(feet, now);
                float a = alpha(l, now) * fadeOut * VfxParts.fade(view.cam(), at, 1.2, 3);
                if (a <= 0) continue;
                Quaternionf turn = new Quaternionf(l.turn);
                float age = now - l.born;
                turn.rotateXYZ(age * .05f, age * .07f, age * .03f);
                TextureAtlasSprite s = l.sprite;
                float du = (s.getU1() - s.getU0()) / 4, dv = (s.getV1() - s.getV0()) / 4;
                VfxParts.cube(pose, buf, at, l.size, turn, s.getU0() + du, s.getV0() + dv, s.getU0() + du * 2.5f, s.getV0() + dv * 2.5f, 0xFFFFFF, a);
            }
        });
        VertexConsumer glow = PostVfx.add(PostVfx.logos("heal/glow"), false);
        for (Lifted l : lifted) {
            if (l.sprite != null) continue;
            Vec3 at = l.at(feet, now);
            float a = alpha(l, now) * fadeOut * VfxParts.fade(view.cam(), at, .8, 2.5);
            if (a > 0) VfxParts.sprite(stack.last(), glow, view, at, l.size * 1.6f, 0, argb(a, l.color));
        }
    }

    private static float alpha(Lifted l, float now) {
        float k = (now - l.born) / l.life;
        if (k <= 0 || k >= 1) return 0;
        return Math.min(1, Math.min(k / .1f, (1 - k) / .2f));
    }

    /** 周围变暗多少（0~0.6）：吟唱越久越暗；离施法者和目的地都远时不暗。 */
    float dim(Vec3 eye, float pt, float now) {
        float p = progress(pt, now);
        float base = switch (phase) {
            case CHARGING -> .6f * smooth(.02f, .85f, p);
            case HELD -> .6f;
            case FIZZLED -> .6f * p * Math.max(0, 1 - phaseAge(now) / 20);
            case RELEASED -> 0;
        };
        double d = Math.min(eye.distanceTo(feet), eye.distanceTo(shownTarget));
        return base * (1 - smooth(64, 192, (float) d));
    }
}
