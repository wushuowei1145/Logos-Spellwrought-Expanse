package dev.logos.dimension.client.explosion;

import static dev.logos.dimension.client.explosion.Draw.GOLD;
import static dev.logos.dimension.client.explosion.Draw.PALE_GOLD;
import static dev.logos.dimension.client.explosion.Draw.RED;
import static dev.logos.dimension.client.explosion.Draw.WHITE;
import static dev.logos.dimension.client.explosion.Draw.argb;
import static dev.logos.dimension.client.explosion.Draw.bell;
import static dev.logos.dimension.client.explosion.Draw.lerp;
import static dev.logos.dimension.client.explosion.Draw.smooth;
import static dev.logos.dimension.explosion.ExplosionTimeline.CROSS_END;
import static dev.logos.dimension.explosion.ExplosionTimeline.FULL_AT;
import static dev.logos.dimension.explosion.ExplosionTimeline.GONE_AT;
import static dev.logos.dimension.explosion.ExplosionTimeline.PILLAR_TICKS;
import static dev.logos.dimension.explosion.ExplosionTimeline.SPHERE_AT;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.client.screenfx.GlowBloom;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.client.screenfx.ScreenEffectRenderer;
import dev.logos.dimension.explosion.ExplosionTimeline;
import dev.logos.dimension.screenfx.ScreenFlash;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 一次爆裂魔法在本机的样子，按 {@link ExplosionTimeline}：
 * <ol>
 *   <li>法阵一下更亮，一根巨大的火柱从最高的法阵往下贯穿到目的地（后期里画，几百格外都看得见）；</li>
 *   <li>火柱落地：地面一圈冲击环、风圈飞快推开，空气的折射由 {@link ExplosionScreen} 画；落地点往上喷出一束火花，
 *       之后这些火花被吸回中心（Unreal 爆炸里的 grain 与吸引）；</li>
 *   <li>红热、白热、转蓝、十字闪都是全屏后期（{@link ExplosionScreen}），之后放一次加长的黑白闪；</li>
 *   <li>黑白闪还没结束，白热的球体出现并扩大（也在 {@link ExplosionScreen}），球面掀起的地面碎块（用那里方块自己的贴图）向外向上飞，
 *       地面卷起尘浪，彩色的十字闪向四周炸开；</li>
 *   <li>球体散去后留下升腾的烟柱、地面的尘环、余烬和慢慢飘起的彩色光点。</li>
 * </ol>
 * 声音按距离晚到（声速约每刻 17 格），远处的人先看到火光再听到轰鸣。
 */
final class BlastView {
    /** 本机把一次爆炸留多久（烟散完）。 */
    static final int END = GONE_AT + 460;
    /** 加长的黑白闪：剪影两拍、吞没、翻转两拍、吞没。比默认的少了彩色发光（前面已经有红热和十字闪），多了停顿。 */
    private static final ScreenFlash.Frame[] FLASH = {ScreenFlash.Frame.SILHOUETTE, ScreenFlash.Frame.SILHOUETTE, ScreenFlash.Frame.ENGULF,
            ScreenFlash.Frame.SWAP, ScreenFlash.Frame.SWAP, ScreenFlash.Frame.ENGULF};
    /** 声速：格每刻。 */
    private static final float SOUND_SPEED = 17;
    /** 烟团最多同时有几个：烟是半透明的大方片，层层叠叠最费填充率（实测四百多个时远处看帧时间翻倍）。 */
    private static final int SMOKE_CAP = 220;

    private record Debris(float born, float life, Vec3 from, Vec3 velocity, float size, Quaternionf turn, Vec3 spin, TextureAtlasSprite sprite) {
        Vec3 at(float age) {
            // 抛物线，带一点空气阻力
            float drag = (1 - (float) Math.exp(-age * .03)) / .03f;
            return from.add(velocity.x * drag, velocity.y * drag - .045 * age * age, velocity.z * drag);
        }
    }

    private record Puff(float born, float life, Vec3 from, Vec3 velocity, float size, float grow, float roll, int frame, int color, float alpha) {
        Vec3 at(float age) {
            float drag = (1 - (float) Math.exp(-age * .02)) / .02f;
            return from.add(velocity.scale(drag));
        }
    }

    private record Spark(float born, float life, Vec3 from, Vec3 velocity, float size, int color, boolean drawnIn, boolean star) {}

    private record Sound(float at, SoundEvent sound, float volume, float pitch) {}

    final Vec3 center;
    final float radius, scale;
    final int caster;
    private final float born;
    final SkyCircles circles;
    private final RandomSource random;
    private final List<Debris> debris = new ArrayList<>();
    private final List<Puff> smoke = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final List<Sound> sounds = new ArrayList<>();
    private boolean flashed, shookImpact, shookBoom;
    private float lastFront;

    BlastView(Vec3 center, float radius, long seed, int caster, @Nullable SkyCircles circles, float now) {
        this.center = center;
        this.radius = radius;
        this.scale = ExplosionTimeline.scale(radius);
        this.caster = caster;
        this.born = now;
        this.random = RandomSource.create(seed);
        this.circles = circles != null ? circles : new SkyCircles();
        this.circles.all(now);
        sound(0, SoundEvents.BLAZE_SHOOT, .5f);
        sound(0, SoundEvents.FIRECHARGE_USE, .45f);
        sound(PILLAR_TICKS, SoundEvents.GENERIC_EXPLODE.value(), .6f);
        sound(PILLAR_TICKS, SoundEvents.LIGHTNING_BOLT_IMPACT, .6f);
        sound(ExplosionTimeline.BLUE_END, SoundEvents.TRIDENT_THUNDER.value(), 1.3f);
        sound(SPHERE_AT, SoundEvents.END_GATEWAY_SPAWN, .5f);
        sound(SPHERE_AT, SoundEvents.GENERIC_EXPLODE.value(), .4f);
        sound(SPHERE_AT, SoundEvents.LIGHTNING_BOLT_THUNDER, .55f);
        sound(SPHERE_AT + 1, SoundEvents.WARDEN_SONIC_BOOM, .55f);
        sound(SPHERE_AT + 2, SoundEvents.DRAGON_FIREBALL_EXPLODE, .5f);
        for (int t = SPHERE_AT + 12; t < FULL_AT + 20; t += 11) sound(t, SoundEvents.GENERIC_EXPLODE.value(), .32f + .1f * random.nextFloat());
        sound(FULL_AT, SoundEvents.LIGHTNING_BOLT_THUNDER, .4f);
    }

    private void sound(float at, SoundEvent sound, float pitch) {
        sounds.add(new Sound(at, sound, 4, pitch));
    }

    float age(float now, float pt) {
        return now + pt - born;
    }

    /** 每个客户端刻。返回 false 时丢掉。 */
    boolean tick(Level level, float now) {
        float age = now - born;
        debris.removeIf(d -> now - d.born > d.life);
        smoke.removeIf(p -> now - p.born > p.life);
        sparks.removeIf(s -> now - s.born > s.life);
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = mc.gameRenderer.getMainCamera().position();
        double distance = eye.distanceTo(center);
        // 声音：按距离晚到
        float delay = (float) (distance / SOUND_SPEED);
        sounds.removeIf(s -> {
            if (age < s.at + delay) return false;
            play(level, eye, distance, s);
            return true;
        });
        if (age >= PILLAR_TICKS && !shookImpact) {
            shookImpact = true;
            ExplosionClient.shake(center, radius, .5f);
        }
        if (age >= SPHERE_AT && !shookBoom) {
            shookBoom = true;
            ExplosionClient.shake(center, radius, 1.2f);
            kira(now);
        }
        if (age >= SPHERE_AT && age < FULL_AT && ((int) age) % 6 == 0) ExplosionClient.shake(center, radius, .35f);
        if (age >= CROSS_END && !flashed) {
            flashed = true;
            ScreenEffectRenderer.play(new ScreenFlash(CameraPoint.world(center), List.of(FLASH)));
        }
        if (age >= PILLAR_TICKS && age < 26) grain(now, 6);
        if (age >= SPHERE_AT && age <= FULL_AT + 6) front(level, now, age);
        if (age >= FULL_AT - 20 && age < FULL_AT + 160) aftermath(now, age);
        return age < END;
    }

    private void play(Level level, Vec3 eye, double distance, Sound s) {
        // 远处的爆炸声放在朝着爆点、离耳朵不远的地方，听得出方向，也不会因为太远被原版的衰减吃掉
        Vec3 dir = distance < 1e-3 ? new Vec3(0, 0, 1) : center.subtract(eye).scale(1 / distance);
        Vec3 at = distance > 24 ? eye.add(dir.scale(24)) : center;
        float fade = (float) (1 - smooth(radius * 4, radius * 12, (float) distance) * .6f);
        level.playLocalSound(at.x, at.y, at.z, s.sound, SoundSource.PLAYERS, s.volume * fade, s.pitch, false);
    }

    /** 火柱落地后往上喷的火花，之后被吸回中心。 */
    private void grain(float now, int n) {
        for (int i = 0; i < n; i++) {
            Vec3 dir = Draw.unit(random);
            dir = new Vec3(dir.x * .35, Math.abs(dir.y) + .6, dir.z * .35).normalize();
            float speed = (1.6f + 2.9f * random.nextFloat()) * scale;
            sparks.add(new Spark(now + random.nextFloat(), 26 + random.nextInt(10), center.add(0, 1, 0), dir.scale(speed),
                    (.7f + .6f * random.nextFloat()) * scale, random.nextInt(4) == 0 ? WHITE : lerp(GOLD, RED, random.nextFloat()), true, false));
        }
    }

    /** 球体出现的一瞬，彩色的十字闪向四周炸开。爆炸常在百来格外看，闪光要够大。 */
    private void kira(float now) {
        for (int i = 0; i < 170; i++) {
            Vec3 dir = Draw.unit(random);
            dir = new Vec3(dir.x, Math.abs(dir.y) * .8 + .05, dir.z).normalize();
            float speed = (1.2f + 3.4f * random.nextFloat()) * scale;
            int color = Draw.SPARKLE[random.nextInt(Draw.SPARKLE.length)];
            sparks.add(new Spark(now + random.nextFloat() * 4, 40 + random.nextInt(70), center.add(0, 2 * scale, 0), dir.scale(speed),
                    (2.6f + 3.6f * random.nextFloat()) * scale, color, false, true));
        }
    }

    /** 球面扫过的地方：地面碎块被掀起来（大块的，远处也看得见），地面卷起一圈贴着球面往外滚的尘浪。 */
    private void front(Level level, float now, float age) {
        float r = radius * ExplosionTimeline.front(age) * 1.02f;
        if (r <= lastFront) return;
        float ring = r - lastFront;
        lastFront = r;
        int pieces = Math.min(22, 3 + (int) (ring * r * .08f));
        for (int i = 0; i < pieces; i++) {
            if (debris.size() > 360) break;
            float a = random.nextFloat() * Mth.TWO_PI, d = r - ring * random.nextFloat();
            double x = center.x + Mth.cos(a) * d, z = center.z + Mth.sin(a) * d;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
            if (ground <= level.getMinY() + 1) continue;
            TextureAtlasSprite sprite = VfxParts.blockSprite(level, x, ground, z);
            if (sprite == null) continue;
            Vec3 out = new Vec3(Mth.cos(a), 0, Mth.sin(a));
            float speed = (1 + 1.8f * random.nextFloat()) * (1.3f - .5f * d / radius);
            Vec3 v = out.scale(speed).add(0, 1.2 + 1.8 * random.nextFloat(), 0);
            Quaternionf turn = new Quaternionf().rotateXYZ(random.nextFloat() * 6.3f, random.nextFloat() * 6.3f, random.nextFloat() * 6.3f);
            Vec3 spin = new Vec3(random.nextFloat() - .5, random.nextFloat() - .5, random.nextFloat() - .5).scale(.3);
            float size = (.35f + .75f * random.nextFloat() * random.nextFloat()) * (1 + scale);
            debris.add(new Debris(now, 55 + random.nextInt(40), new Vec3(x, ground + .5, z), v, size, turn, spin, sprite));
        }
        // 尘浪：生在球面外一点，往外的速度跟球面扩张一样快，球面慢下来后靠惯性继续滚
        float speed = radius * (ExplosionTimeline.front(age + 1) - ExplosionTimeline.front(age)) + .25f;
        int puffs = Math.min(7, 2 + (int) (ring * .4f));
        for (int i = 0; i < puffs; i++) {
            if (smoke.size() > SMOKE_CAP) break;
            float a = random.nextFloat() * Mth.TWO_PI;
            double x = center.x + Mth.cos(a) * r * 1.04, z = center.z + Mth.sin(a) * r * 1.04;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
            double y = Math.max(ground, center.y - 4);
            Vec3 out = new Vec3(Mth.cos(a), 0, Mth.sin(a));
            float size = (4 + 5 * random.nextFloat()) * scale;
            smoke.add(new Puff(now, 80 + random.nextInt(60), new Vec3(x, y + size * .4, z),
                    out.scale(speed * (.8 + .3 * random.nextFloat())).add(0, .04 + .08 * random.nextFloat(), 0),
                    size, .06f * scale, random.nextFloat() * 6.3f, random.nextInt(4), 0x9A8C7C, .62f));
        }
    }

    /** 爆炸之后：升腾的烟柱和顶上的烟团、余烬、慢慢飘起的彩色光点。 */
    private void aftermath(float now, float age) {
        if (smoke.size() < SMOKE_CAP && random.nextFloat() < .55f) {
            // 烟柱：从坑里往上冒，越往上越大
            float a = random.nextFloat() * Mth.TWO_PI, d = radius * .45f * random.nextFloat();
            Vec3 at = center.add(Mth.cos(a) * d, 2, Mth.sin(a) * d);
            Vec3 v = new Vec3(Mth.cos(a) * .12, .7 + .6 * random.nextFloat(), Mth.sin(a) * .12).scale(scale);
            int color = age < FULL_AT + 30 ? 0x6E5446 : 0x5E5856;
            smoke.add(new Puff(now, 130 + random.nextInt(100), at, v, (8 + 8 * random.nextFloat()) * scale, .13f * scale, random.nextFloat() * 6.3f,
                    random.nextInt(4), color, .62f));
            // 顶上的烟团：在高处往四周摊开，像一朵蘑菇云的顶
            if (age > FULL_AT && random.nextFloat() < .4f) {
                float b = random.nextFloat() * Mth.TWO_PI;
                Vec3 cap = center.add(Mth.cos(b) * radius * .2f, (46 + 16 * random.nextFloat()) * scale, Mth.sin(b) * radius * .2f);
                Vec3 out = new Vec3(Mth.cos(b), 0, Mth.sin(b)).scale((.3 + .35 * random.nextFloat()) * scale).add(0, .08 * scale, 0);
                smoke.add(new Puff(now, 150 + random.nextInt(90), cap, out, (10 + 10 * random.nextFloat()) * scale, .1f * scale,
                        random.nextFloat() * 6.3f, random.nextInt(4), 0x655E5A, .5f));
            }
        }
        if (sparks.size() < 420) {
            for (int i = 0; i < 2; i++) {
                float a = random.nextFloat() * Mth.TWO_PI, d = radius * random.nextFloat();
                Vec3 at = center.add(Mth.cos(a) * d, random.nextFloat() * 10 * scale, Mth.sin(a) * d);
                boolean ember = random.nextInt(3) > 0;
                int color = ember ? lerp(GOLD, RED, random.nextFloat()) : Draw.SPARKLE[random.nextInt(Draw.SPARKLE.length)];
                Vec3 v = new Vec3((random.nextFloat() - .5) * .1, .08 + .12 * random.nextFloat(), (random.nextFloat() - .5) * .1);
                sparks.add(new Spark(now, 60 + random.nextInt(80), at, v, (ember ? .25f : .9f) * (.6f + .8f * random.nextFloat()), color, false, !ember));
            }
        }
    }

    // ---- 绘制 ------------------------------------------------------------------------------------

    void submit(Level level, VfxParts.View view, float pt, float now, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers) {
        float age = age(now, pt), t = now + pt;
        PoseStack.Pose pose = stack.last();
        // 法阵：放出去的一刻更亮；画面转蓝的一瞬被闪光吞没，之后是全屏的十字闪和黑白闪，再出现球体
        float fade = 1 - smooth(ExplosionTimeline.HEAT_END - 1, ExplosionTimeline.HEAT_END + .5f, age);
        float bright = 1.3f + .8f * smooth(0, 6, age) * (1 - smooth(PILLAR_TICKS, PILLAR_TICKS + 20, age) * .4f);
        circles.submit(stack, view, center, scale, t, bright, fade);
        pillar(pose, view, age, t);
        impact(pose, view, age, t);
        sparks(pose, view, age, t);
        debris(view, t, layers, stack);
        smoke(view, t, layers, stack);
    }

    /** 最高那个法阵的高度（相对目的地）。 */
    private float top() {
        float[] h = ExplosionTimeline.CIRCLE_HEIGHT;
        return (h[h.length - 1] + 6) * scale;
    }

    /** 火柱：从最高的法阵往下贯穿，落地后变粗，球体出现时散去。 */
    private void pillar(PoseStack.Pose pose, VfxParts.View view, float age, float t) {
        float show = smooth(0, 2, age) * (1 - smooth(ExplosionTimeline.HEAT_END - 1, ExplosionTimeline.HEAT_END + .5f, age));
        if (show <= .003f) return;
        float down = smooth(0, PILLAR_TICKS, age);
        down = 1 - (1 - down) * (1 - down);
        float topY = top(), frontY = topY * (1 - down);
        float rp = (float) ExplosionTimeline.PILLAR_RADIUS * scale * (1 + .45f * smooth(PILLAR_TICKS, SPHERE_AT, age));
        Vec3 base = center.add(0, frontY - 2 * scale, 0);
        float h = topY - frontY + 2 * scale;
        float v = -t * .09f;
        VfxParts.cylinder(pose, PostVfx.add(PostVfx.logos("charge/pillar"), false), base, rp * 1.3f, h, 40, t * .004f, 4, v * .6f, 2,
                argb(show * .55f, RED), argb(show * .25f, Draw.RED_DEEP));
        VfxParts.cylinder(pose, PostVfx.add(PostVfx.logos("pillar_burst/flame"), false), base, rp, h, 40, t * .01f, 3, v * 1.6f, 3,
                argb(show * .9f, 0xFF7A22), argb(show * .6f, RED));
        VfxParts.cylinder(pose, PostVfx.add(PostVfx.logos("charge/pillar"), false), base, rp * .55f, h, 32, -t * .006f, 3, v * 2.2f, 2,
                argb(show, PALE_GOLD), argb(show * .8f, GOLD));
        // 从哪个方向看都有一根实心的亮芯：白芯外面裹一层橙金，火柱读起来是火不是光
        Vec3 top = center.add(0, topY, 0), bottom = center.add(0, frontY, 0);
        VfxParts.streak(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), view, bottom.add(0, -3 * scale, 0), top, rp * 2.8f, argb(show * .6f, 0xFF8A2A));
        VfxParts.streak(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), view, bottom, top, rp * 1.5f, argb(show * .8f, GOLD));
        VfxParts.streak(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), view, bottom, top, rp * .7f, argb(show * .85f, WHITE));
        // 往下冲的头
        if (age < PILLAR_TICKS + 2) VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("fire_impact/flash"), false), view, bottom, rp * 2.4f, t * .1f,
                argb(show * .8f, GOLD));
        for (int i = 0; i < 5; i++) {
            Vec3 at = bottom.add(top.subtract(bottom).scale(i / 4.0));
            GlowBloom.sprite((float) (at.x - view.cam().x), (float) (at.y - view.cam().y), (float) (at.z - view.cam().z), rp * 1.6f, argb(show * .8f, GOLD));
        }
    }

    /** 火柱落地：地上一圈亮光、飞快推开的冲击环和风圈。 */
    private void impact(PoseStack.Pose pose, VfxParts.View view, float age, float t) {
        float k = (age - PILLAR_TICKS) / 26;
        if (k < 0 || age > SPHERE_AT + 12) return;
        Vec3 x = new Vec3(1, 0, 0), z = new Vec3(0, 0, 1), ground = center.add(0, .3, 0);
        float glow = smooth(0, .1f, k) * (1 - smooth(ExplosionTimeline.HEAT_END - 1, ExplosionTimeline.HEAT_END + .5f, age));
        VfxParts.flat(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), ground, x, z, (14 + 10 * Math.min(1, k)) * scale, 0, argb(glow * .8f, 0xFF8A30));
        VfxParts.flat(pose, PostVfx.add(PostVfx.logos("fire_beam/circle"), false), ground.add(0, .05, 0), x, z, (9 + 6 * Math.min(1, k)) * scale, t * .03f,
                argb(glow * .8f, PALE_GOLD));
        if (k <= 1) {
            float r = (6 + 90 * (1 - (1 - k) * (1 - k))) * scale;
            float a = (1 - k) * smooth(0, .06f, k);
            VfxParts.flat(pose, PostVfx.add(PostVfx.logos("heal/ring"), false), ground.add(0, .1, 0), x, z, r, 0, argb(a, PALE_GOLD));
            VfxParts.flat(pose, PostVfx.add(PostVfx.logos("fire_ray/ring"), false), ground.add(0, .15, 0), x, z, r * .92f, k * 2, argb(a * .7f, GOLD));
            VfxParts.cylinder(pose, PostVfx.add(PostVfx.logos("physical_ray/wind"), false), ground, r * .97f, (5 * (1 - k) + 1) * scale, 64, t * .02f, 10, 0, 1,
                    argb(a * .6f, 0xFFE8C8), argb(0, WHITE));
        }
    }

    private void sparks(PoseStack.Pose pose, VfxParts.View view, float age, float t) {
        if (sparks.isEmpty()) return;
        VertexConsumer streak = PostVfx.add(PostVfx.logos("force/streak"), false);
        VertexConsumer star = PostVfx.add(PostVfx.logos("void_ray/star"), false);
        VertexConsumer flare = PostVfx.add(PostVfx.logos("arcane_impact/star"), false);
        VertexConsumer glow = PostVfx.add(PostVfx.logos("heal/glow"), false);
        // 火花被吸回中心的程度：落地后十五刻开始吸，球体出现前吸干净
        float pull = smooth(PILLAR_TICKS + 15, ExplosionTimeline.BLUE_END, age);
        for (Spark s : sparks) {
            float a = t - s.born;
            float k = a / s.life;
            if (k < 0 || k > 1) continue;
            Vec3 at, prev;
            if (s.drawnIn) {
                at = sparkAt(s, a, pull);
                prev = sparkAt(s, Math.max(0, a - 3), pull);
                float alpha = Math.min(1, a / 2) * (1 - smooth(.85f, 1, k)) * (1 - pull * pull);
                VfxParts.streak(pose, streak, view, prev, at, s.size, argb(alpha, s.color));
                continue;
            }
            float drag = (1 - (float) Math.exp(-a * .05)) / .05f;
            at = s.from.add(s.velocity.scale(drag));
            float twinkle = .65f + .35f * Mth.sin(t * .9f + s.born * 3);
            float alpha = bell(Math.min(1, k * 1.3f)) * twinkle * VfxParts.fade(view.cam(), at, 1, 4);
            if (s.star) {
                VfxParts.sprite(pose, star, view, at, s.size * .35f, 0, argb(alpha, lerp(s.color, WHITE, .4f)));
                VfxParts.sprite(pose, flare, view, at, s.size * 1.4f, 0, argb(alpha * .9f, s.color));
            } else {
                VfxParts.sprite(pose, glow, view, at, s.size, 0, argb(alpha, s.color));
            }
        }
    }

    /** 喷出去的火花：先往上飞、减速，然后被吸回中心。 */
    private Vec3 sparkAt(Spark s, float a, float pull) {
        float drag = (1 - (float) Math.exp(-a * .08)) / .08f;
        Vec3 free = s.from.add(s.velocity.scale(drag));
        return free.add(center.add(0, 2 * scale, 0).subtract(free).scale(pull * pull * (3 - 2 * pull)));
    }

    /** 掀起的地面碎块：实物，在世界里画。 */
    private void debris(VfxParts.View view, float t, IntFunction<OrderedSubmitNodeCollector> layers, PoseStack stack) {
        if (debris.isEmpty()) return;
        layers.apply(0).submitCustomGeometry(stack, VfxParts.BLOCKS, (pose, buf) -> {
            for (Debris d : debris) {
                float a = t - d.born;
                float k = a / d.life;
                if (k < 0 || k > 1) continue;
                Vec3 at = d.at(a);
                float alpha = (1 - smooth(.8f, 1, k)) * VfxParts.fade(view.cam(), at, 1.5, 4);
                if (alpha <= 0) continue;
                Quaternionf turn = new Quaternionf(d.turn).rotateXYZ((float) d.spin.x * a, (float) d.spin.y * a, (float) d.spin.z * a);
                TextureAtlasSprite s = d.sprite;
                float du = (s.getU1() - s.getU0()) / 4, dv = (s.getV1() - s.getV0()) / 4;
                // 刚被掀起的碎块还是红热的
                int tint = lerp(0xFFB070, 0xFFFFFF, smooth(0, .4f, k));
                VfxParts.cube(pose, buf, at, d.size, turn, s.getU0() + du, s.getV0() + dv, s.getU0() + du * 3, s.getV0() + dv * 3, tint, alpha);
            }
        });
    }

    /** 烟：半透明，在世界里画，跟着周围的光照和雾。 */
    private void smoke(VfxParts.View view, float t, IntFunction<OrderedSubmitNodeCollector> layers, PoseStack stack) {
        if (smoke.isEmpty()) return;
        layers.apply(1).submitCustomGeometry(stack, VfxParts.blend("potion/smoke"), (pose, buf) -> {
            for (Puff p : smoke) {
                float a = t - p.born;
                float k = a / p.life;
                if (k < 0 || k > 1) continue;
                Vec3 at = p.at(a);
                float size = p.size + p.grow * a;
                float alpha = p.alpha * smooth(0, .08f, k) * (1 - smooth(.5f, 1, k)) * VfxParts.fade(view.cam(), at, size * .5, size * 1.5);
                if (alpha <= .003f) continue;
                float u0 = (p.frame % 2) * .5f, v0 = (p.frame / 2) * .5f;
                // 刚卷起来时下面映着火光
                int color = lerp(0xFF9A50, p.color, smooth(0, .25f, k));
                VfxParts.sprite(pose, buf, view, at, size, size, p.roll + a * .004f, u0, v0, u0 + .5f, v0 + .5f, argb(alpha, color));
            }
        });
    }

    // ---- 交给全屏后期的参数 ---------------------------------------------------------------------------

    /** 爆点周围红热的程度 0~1（火柱落地后渐强；球体出现后剩一点余温）。 */
    float heat(float age) {
        float main = smooth(PILLAR_TICKS, PILLAR_TICKS + 10, age) * (1 - smooth(ExplosionTimeline.HEAT_END, ExplosionTimeline.BLUE_END, age));
        float after = .4f * smooth(SPHERE_AT + 4, SPHERE_AT + 14, age) * (1 - smooth(FULL_AT, FULL_AT + 140, age));
        return Math.max(main, after);
    }

    /** 红热转白热 0~1。 */
    float white(float age) {
        return smooth(PILLAR_TICKS + 10, ExplosionTimeline.HEAT_END, age) * (1 - smooth(SPHERE_AT, SPHERE_AT + 8, age));
    }

    /** 一瞬间转蓝 0~1：十字闪光整个落在蓝底上，到黑白闪才褪。 */
    float blue(float age) {
        return smooth(ExplosionTimeline.HEAT_END - .5f, ExplosionTimeline.HEAT_END + .5f, age) * (1 - smooth(CROSS_END - 1, CROSS_END + .5f, age));
    }

    /** 十字闪光 0~1。 */
    float cross(float age) {
        return smooth(ExplosionTimeline.BLUE_END - .5f, ExplosionTimeline.BLUE_END + .5f, age) * (1 - smooth(CROSS_END - 1.5f, CROSS_END + .5f, age));
    }

    /** 球体的半径（格），还没出现时为 0。 */
    float sphere(float age) {
        if (age < SPHERE_AT) return 0;
        return radius * (.05f + 1.0f * ExplosionTimeline.front(age));
    }

    /** 球体的不透明度：出现时一下就满，到最大后散去。 */
    float sphereAlpha(float age) {
        return smooth(SPHERE_AT, SPHERE_AT + 1, age) * (1 - smooth(FULL_AT, GONE_AT, age));
    }

    /** 火柱落地时那一圈空气冲击：半径和强度。 */
    float[] airShock(float age) {
        float k = (age - PILLAR_TICKS) / 22;
        if (k < 0 || k > 1) return new float[] {0, 0};
        return new float[] {(4 + 110 * (1 - (1 - k) * (1 - k))) * scale, (1 - k) * smooth(0, .05f, k)};
    }

    /** 球体扩大时推出去的气浪：半径和强度。 */
    float[] blastWave(float age) {
        if (age < SPHERE_AT) return new float[] {0, 0};
        float k = (age - SPHERE_AT) / (FULL_AT + 30 - SPHERE_AT);
        if (k > 1) return new float[] {0, 0};
        float r = sphere(age) * 1.06f + (age - SPHERE_AT) * .45f * scale;
        return new float[] {r, (1 - k) * smooth(0, .03f, k)};
    }
}
