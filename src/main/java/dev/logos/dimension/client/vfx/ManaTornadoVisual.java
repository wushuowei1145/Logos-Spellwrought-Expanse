package dev.logos.dimension.client.vfx;

import static dev.logos.dimension.client.vfx.VfxParts.argb;
import static dev.logos.dimension.client.vfx.VfxParts.smooth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.dimension.client.vfx.VfxParts.View;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 魔力围着施法者盘旋上升卷成的螺旋龙卷（参考 NiagaeaBigVFXPack 的 NS_Tornado_Mesh_red 与 NS_Tornado_under，逐层照搬）：
 * 实心、亮暗分明的红芯，绕着芯盘上去的几条刀刃状亮带，贴着芯的深蓝与红色细线、蓝点和稀疏的红色裂纹，脚下往上张开的白色裙边和旋转的地涡，
 * 加上贴着脚边打转上溅的光点、绕着转的烟尘和被卷起的碎石（用脚下方块自己的贴图）。
 * 原版的层都是 Masked 材质，这里大部分层也用硬边镂空（{@link VfxParts#cutOwn}）：不透明、边缘锐利、可以有暗部，白天也不会被冲淡；
 * 只有芯的高光、刀刃的辉光和裙边的光晕是加法发光。跟着施法者走；施法者身高范围内朝镜头的那一面被切开一个窗，
 * 镜头贴近或进到某一层里面时那一层也被切掉，看得见施法者。整体颜色可以随时间转色相。只在客户端绘制，大小随 {@code scale} 缩放。
 */
public final class ManaTornadoVisual {
    /** 卷起用 GROW 刻（各层由里到外错开一点）；收掉以后 FADE 刻内从底部往上散掉。 */
    static final float GROW = 14, FADE = 24;

    /**
     * UE 各材质的颜色（按亮度归一）：芯的红与粉白高光、刀刃的白蓝与辉光、深蓝细线、天蓝的点、红色细线、裙边与地涡的白、底光的深蓝。
     * 镂空层的贴图 RGB 是亮度，乘上这些颜色，暗处自然就是深红、深蓝。
     */
    private static final int CORE = 0xFF4A66, CORE_HOT = 0xFFB0C4, BLADE = 0xC4D0FF, BLADE_GLOW = 0x8FA4FF,
            BLADE_OUT = 0x8C9CFF, LINE_DEEP = 0x1B2690, AZURE = 0x3A8CFF, CRIMSON = 0xFF2448, FOAM = 0xD6E4FF, NAVY = 0x1C2C96;

    /**
     * 三种网格的轮廓：STRAIGHT 是 SM_Tornado_Tatumaki（腰略细的直筒），HOURGLASS 是 SM_Tornado（中段平直、两头外翻，
     * 下半截原本埋在地下），SKIRT 是 SM_Tornado_en2（往上张开的锥面裙边，顶部半径是底部的 4/3）。
     */
    private enum Shape { STRAIGHT, HOURGLASS, SKIRT }

    /**
     * 一层网格：材质、轮廓、腰部半径（SKIRT 为底部半径）、底和顶离地多少格（可以在地下，只画露出地面的部分）、
     * 从下到上扭转几圈、贴图绕一圈和从下到上各铺几格、每刻绕转与上滚多少格贴图、透明度、颜色、
     * Fresnel 程度（1 为正对镜头的一面几乎透明，只剩轮廓；镂空层不用）、表面起伏和相位。
     */
    private record Tube(RenderType material, Shape shape, float radius, float bottom, float top, float twist,
            float around, float tall, float spin, float rise, float alpha, int rgb, float fresnel, float lumps, float phase) {}

    /**
     * 镂空层，由里到外。原版高约 25 米，这里芯高 12 格。原版外面几层大得多，但大部分被材质切掉，看上去是一根紧凑的柱子；
     * 这里把它们都收到 2.6 格以内。
     */
    private static final Tube[] CUTS = {
            // Center（Tatumaki × MI_Tornado_main_Tatumaki）：实心的红芯，竖向条纹亮暗分明；扭得最紧，表面起伏跟着扭成绳状
            new Tube(VfxParts.cutOwn("tornado/core"), Shape.STRAIGHT, 1.15f, 0, 12, 2.5f, 2, 2, .006f, .05f, 1, CORE, 0, .15f, 0),
            // Center_Side（SM_Tornado × MI_Tornado_under2）：贴着芯的深蓝细线
            new Tube(VfxParts.cutOwn("tornado/streaks"), Shape.HOURGLASS, 1.3f, -1.9f, 11.9f, 1.6f, 4, 2, .01f, .04f, 1, LINE_DEEP, 0, .12f, 3.1f),
            // Center_Side2（Tatumaki × MI_Tornado_Sander）：绕着芯盘上去的刀刃，中间白亮、边缘深蓝
            new Tube(VfxParts.cutOwn("tornado/blades"), Shape.STRAIGHT, 1.45f, 0, 12, 1.8f, 2, 1.3f, .014f, .045f, 1, BLADE, 0, .12f, 1.7f),
            // Tornado3（SM_Tornado × MI_Tornado_Sander2）：红色细线
            new Tube(VfxParts.cutOwn("tornado/streaks"), Shape.HOURGLASS, 1.75f, -1.9f, 11.9f, 1.3f, 3, 2, -.008f, .035f, .45f, CRIMSON, 0, .1f, 5.2f),
            // Tornado8（Tatumaki × MI_Tornado_Sander）：外面一圈稀疏一些的蓝色刀刃
            new Tube(VfxParts.cutOwn("tornado/blades"), Shape.STRAIGHT, 2, 0, 11, 1.4f, 1, 1, .01f, .035f, .85f, BLADE_OUT, 0, .1f, 2.3f),
            // Tornado2（SM_Tornado × MI_Tornado_under）：天蓝的点
            new Tube(VfxParts.cutOwn("tornado/specks"), Shape.HOURGLASS, 2.2f, -2.8f, 8.2f, 1, 5, 3, -.006f, .04f, .9f, AZURE, 0, .1f, 4.4f),
            // OutSide（SM_Tornado × MI_Tornado_Sander2）：最外面稀疏的红色裂纹
            new Tube(VfxParts.cutOwn("pillar_burst/veins"), Shape.HOURGLASS, 2.6f, -4, 11, 1, 3, 1.5f, -.005f, .025f, .25f, CRIMSON, 0, .12f, .6f),
            // Under、MeshMain004（en2 × under2 / under1）：芯脚往上张开的白色裙边，外面再一圈白蓝刀刃
            new Tube(VfxParts.cutOwn("tornado/core"), Shape.SKIRT, 1.4f, -.3f, 1.1f, .4f, 3, 1, .02f, .04f, 1, FOAM, 0, .06f, 0),
            new Tube(VfxParts.cutOwn("tornado/blades"), Shape.SKIRT, 2, -.2f, .7f, .3f, 3, .6f, -.02f, .03f, .9f, BLADE, 0, .05f, 1)};

    /** 加法发光，排在镂空层之后：芯的粉白高光、贴着刀刃的辉光（参数同刀刃，略大一点）、裙边的光晕。 */
    private static final Tube[] GLOWS = {
            new Tube(VfxParts.addOwn("tornado/streaks"), Shape.STRAIGHT, 1.17f, 0, 12, 2.5f, 3, 2, .006f, .06f, .7f, CORE_HOT, .6f, .15f, 0),
            new Tube(VfxParts.addOwn("tornado/blades"), Shape.STRAIGHT, 1.48f, 0, 12, 1.8f, 2, 1.3f, .014f, .045f, .85f, BLADE_GLOW, 0, .12f, 1.7f),
            new Tube(VfxParts.addOwn("tornado/wisp"), Shape.SKIRT, 1.5f, -.3f, 1.3f, .4f, 3, 1, .02f, .04f, .6f, FOAM, 0, .06f, 0)};

    /**
     * 地上的圆盘（SM_Tornado_en，极坐标 UV）：材质、半径、越靠中心多转几圈（卷成旋涡）、贴图绕一圈几格、每刻转多少格、
     * 每刻往中心流多少格、透明度、颜色。
     */
    private record Disc(RenderType material, float radius, float swirl, float around, float spin, float inflow, float alpha, int rgb) {}

    private static final Disc[] DISCS = {
            // MeshMain006（en × MI_Tornado_Sander1）：白色的地涡，斑块加烟絮两层
            new Disc(VfxParts.addOwn("pillar_burst/cells"), 3, 2.2f, 6, .01f, .02f, .35f, FOAM),
            new Disc(VfxParts.addOwn("tornado/wisp"), 3.2f, 2.6f, 5, .014f, .03f, .4f, FOAM),
            // MeshMain005（en × MI_Tornado_under1）：反着转的蓝色底光
            new Disc(VfxParts.addOwn("tornado/wisp"), 4, .8f, 4, -.008f, .015f, .35f, NAVY)};

    /** 贴着脚边打转、上溅又落下的光点（PointLight001），或绕着转的烟尘（Smoke）：都相对龙卷中心记位置。 */
    private static final class Mote {
        float angle, radius, y, vy, size;
        final float spin, born, life, drift;
        final int tint;

        Mote(float angle, float radius, float y, float vy, float spin, float drift, float size, float born, float life, int tint) {
            this.angle = angle; this.radius = radius; this.y = y; this.vy = vy; this.spin = spin; this.drift = drift;
            this.size = size; this.born = born; this.life = life; this.tint = tint;
        }
    }

    /** 被卷起的碎石（Gareki）：绕着中心转，先被抛起再落回地面，落地后停一会儿淡掉。 */
    private static final class Rock {
        float angle, radius, y, vy, prevAngle, prevRadius, prevY, landed = -1;
        final float spin, drift, size, born;
        final Quaternionf turn = new Quaternionf(), tumble;
        final TextureAtlasSprite sprite;

        Rock(float angle, float radius, float vy, float spin, float drift, float size, float born, Quaternionf tumble, TextureAtlasSprite sprite) {
            this.angle = prevAngle = angle; this.radius = prevRadius = radius; this.vy = vy;
            this.spin = spin; this.drift = drift; this.size = size; this.born = born; this.tumble = tumble; this.sprite = sprite;
        }
    }

    private final Level level;
    private final int entity;
    private final float scale;
    private final int cycle;
    private final RandomSource random;
    private final List<Mote> dots = new ArrayList<>(), smoke = new ArrayList<>();
    private final List<Rock> rocks = new ArrayList<>();
    private Vec3 base, prevBase;
    private int age;
    /** 开始散掉的那一刻；一直卷的话是无穷大，收掉时才定下来。 */
    private float end;

    ManaTornadoVisual(Level level, int entity, Vec3 at, int ticks, float scale, int cycle, long seed) {
        this.level = level;
        this.entity = entity;
        this.scale = scale;
        this.cycle = cycle;
        this.random = RandomSource.create(seed);
        this.base = this.prevBase = at;
        this.end = ticks < 0 ? Float.MAX_VALUE : Math.max(GROW, ticks - FADE);
    }

    int entity() { return entity; }

    /** 收掉：从现在起散开淡出。 */
    void release() { end = Math.min(end, age); }

    /** 一个客户端刻。散完返回 false。 */
    boolean tick() {
        age++;
        prevBase = base;
        if (entity >= 0) {
            Entity e = level.getEntity(entity);
            if (e == null || e.isRemoved()) release();
            else base = e.position();
        }
        boolean spawning = age < end;
        float s = scale;
        // PointLight001：每秒三百多个，这里每刻四个，贴着脚边飞快地打转，上溅一两格又落下
        if (spawning) for (int i = 0; i < 4; i++) {
            dots.add(new Mote(random.nextFloat() * Mth.TWO_PI, (.9f + 1.5f * random.nextFloat()) * s, .05f * s, (.14f + .14f * random.nextFloat()) * s,
                    (.14f + .2f * random.nextFloat()) * (random.nextBoolean() ? 1 : .8f), .01f * s, (.025f + .025f * random.nextFloat()) * s,
                    age, 18 + random.nextInt(18), random.nextInt(3) == 0 ? BLADE : 0xFFFFFF));
        }
        // Smoke：每刻一团，绕着转着往上飘
        if (spawning && age % 2 == 0) {
            smoke.add(new Mote(random.nextFloat() * Mth.TWO_PI, (1 + 1.2f * random.nextFloat()) * s, .2f * s, (.03f + .03f * random.nextFloat()) * s,
                    .06f + .06f * random.nextFloat(), .02f * s, (.7f + .5f * random.nextFloat()) * s, age, 16 + random.nextInt(24),
                    VfxParts.groundTint(level, base)));
        }
        // Gareki：每三刻从脚边一圈的地面掀起一块
        if (spawning && age % 3 == 0) rock();
        for (Mote d : dots) {
            d.angle += d.spin;
            d.radius += d.drift;
            d.y = Math.max(0, d.y + d.vy);
            d.vy -= .022f * s;
        }
        for (Mote m : smoke) {
            m.angle += m.spin;
            m.radius += m.drift;
            m.y += m.vy;
        }
        for (Rock r : rocks) {
            r.prevAngle = r.angle; r.prevRadius = r.radius; r.prevY = r.y;
            if (r.landed >= 0) continue;
            r.angle += r.spin;
            r.radius = Math.max(.6f * s, r.radius + r.drift);
            r.y += r.vy;
            r.vy -= .02f * s;
            r.turn.mul(r.tumble);
            if (r.vy < 0 && r.y <= r.size) { r.y = r.prevY = r.size; r.landed = age; }
        }
        dots.removeIf(d -> age - d.born > d.life);
        smoke.removeIf(m -> age - m.born > m.life);
        rocks.removeIf(r -> r.landed >= 0 ? age - r.landed > 22 : age - r.born > 120);
        return age < end + FADE || !dots.isEmpty() && age < end + FADE + 40;
    }

    private void rock() {
        float a = random.nextFloat() * Mth.TWO_PI, r = (1.4f + 3.4f * random.nextFloat()) * scale;
        TextureAtlasSprite sprite = VfxParts.blockSprite(level, base.x + Mth.cos(a) * r, base.y, base.z + Mth.sin(a) * r);
        if (sprite == null) return;
        Quaternionf tumble = new Quaternionf().rotateXYZ((random.nextFloat() - .5f) * .5f, (random.nextFloat() - .5f) * .5f, (random.nextFloat() - .5f) * .5f);
        // 约 32° 的锥形向上抛起（Gareki 的 Cone Angle 32），同时被卷着绕中心转、慢慢往里收
        Rock rock = new Rock(a, r, (.22f + .2f * random.nextFloat()) * scale, .07f + .07f * random.nextFloat(), -.015f * scale,
                (.06f + .1f * random.nextFloat()) * scale, age, tumble, sprite);
        rock.turn.rotateXYZ(random.nextFloat() * 6.3f, random.nextFloat() * 6.3f, random.nextFloat() * 6.3f);
        rocks.add(rock);
    }

    /**
     * {@code layered}：镂空层排在前一批（写深度），加法发光排在后一批，这样芯后面的辉光会被芯挡住（见 {@link VfxParts#layered}）。
     * 地涡、光点、烟尘和碎石照常走 {@code out}。
     */
    void submit(View view, float pt, PoseStack stack, SubmitNodeCollector out, IntFunction<OrderedSubmitNodeCollector> layered) {
        float t = age + pt;
        Vec3 at = prevBase.lerp(base, pt);
        // 收掉以后：整体往上飘一点，从底部往上散
        float gone = t <= end ? 0 : smooth(end, end + FADE, t);
        float strength = (1 - gone * gone) * (1 + .05f * Mth.sin(t * .31f) + .03f * Mth.sin(t * .83f));
        float turns = cycle > 0 ? t / cycle : 0;
        if (strength > 0) {
            tubes(stack, layered.apply(0), CUTS, view, at, t, gone, strength, turns);
            tubes(stack, layered.apply(1), GLOWS, view, at, t, gone, strength, turns);
            // 地上被照亮的一大片蓝白光（UE 里点光源打在地上的样子）
            int glow = VfxParts.hue(BLADE_GLOW, turns);
            float spreadGlow = Mth.clamp(t / GROW, 0, 1);
            out.submitCustomGeometry(stack, VfxParts.add("heal/glow"), (pose, buf) ->
                    VfxParts.ground(pose, buf, at.add(0, .03, 0), 4.5f * scale * (.5f + .5f * spreadGlow), 0, argb(strength * .55f, glow)));
            float spread = Mth.clamp(t / GROW, 0, 1);
            for (Disc d : DISCS) {
                int rgb = VfxParts.hue(d.rgb(), turns);
                out.submitCustomGeometry(stack, d.material(), (pose, buf) -> disc(pose, buf, at, d, t, spread, strength, rgb));
            }
        }
        motes(stack, out, view, at, t, pt, turns);
        rocks(stack, out, view, at, t, pt);
    }

    /** 提交一组网格层，第 {@code i} 层比前一层晚 0.8 刻开始往上长。 */
    private void tubes(PoseStack stack, OrderedSubmitNodeCollector to, Tube[] layers, View view, Vec3 at, float t, float gone,
            float strength, float turns) {
        for (int i = 0; i < layers.length; i++) {
            Tube l = layers[i];
            float grow = 1 - (float) Math.pow(1 - Mth.clamp((t - i * .8f) / GROW, 0, 1), 3);
            if (grow <= 0) continue;
            int rgb = VfxParts.hue(l.rgb(), turns);
            to.submitCustomGeometry(stack, l.material(), (pose, buf) -> tube(pose, buf, view, at, l, t, grow, gone, strength, rgb));
        }
    }

    /** 轮廓：离底 {@code f}（占全高的比例）处的半径倍数。 */
    private static float profile(Shape shape, float f) {
        return switch (shape) {
            case STRAIGHT -> 1 + .38f * Mth.square((f - .45f) / .55f);
            case HOURGLASS -> 1 + 1.3f * (float) Math.pow(Math.max(0, Math.abs(f - .5f) - .25f) / .25f, 1.6);
            case SKIRT -> 1 + f / 3;
        };
    }

    /** 透明度沿高度的变化：底部渐显，顶部渐隐（UE 材质里的 T_Gradation 遮罩）。{@code f0} 是露出地面那一截的起点。 */
    private static float gradient(Shape shape, float f, float f0) {
        if (shape == Shape.SKIRT) return smooth(f0, f0 + .25f, f) * (1 - smooth(.35f, 1, f));
        return smooth(f0, f0 + .08f, f) * (1 - smooth(.62f, 1, f));
    }

    /**
     * 画一层网格：竖向切成若干圈，每圈按轮廓、表面起伏和整体轻微摇摆排开顶点。贴图绕一圈取整数格，
     * 越往上转得越多（扭转），随时间绕转、上滚。卷起时从地面往上长出来，收掉时从底部往上散。
     */
    private void tube(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 at, Tube l, float t, float grow, float gone,
            float strength, int rgb) {
        float s = scale, height = l.top() - l.bottom();
        float yLo = Math.max(0, l.bottom()) * s, yHi = l.top() * s;
        float yTop = yLo + (yHi - yLo) * grow;
        float f0 = (Math.max(0, l.bottom()) - l.bottom()) / height;
        float lift = gone * 2.5f * s;
        float near = (float) Math.hypot(view.cam().x - at.x, view.cam().z - at.z);
        int rings = 20, segments = Mth.clamp(Math.round(l.radius() * s * 12), 24, 64);
        Vec3[][] p = new Vec3[rings + 1][segments + 1];
        int[][] c = new int[rings + 1][segments + 1];
        float[] v = new float[rings + 1];
        for (int j = 0; j <= rings; j++) {
            float y = Mth.lerp((float) j / rings, yLo, yTop);
            float f = (y / s - l.bottom()) / height;
            float r = l.radius() * s * profile(l.shape(), f);
            // 镜头在这一圈里面（施法者自己的视角）时整层淡到四成，外面几层大筒不糊满视野
            float a = strength * l.alpha() * gradient(l.shape(), f, f0) * Mth.lerp(smooth(r * .8f, r * 1.3f, near), .4f, 1);
            // 往上长的时候前沿是软的；散的时候底部先没
            if (grow < 1) a *= 1 - smooth(yTop - 1.5f * s, yTop + .01f, y);
            a *= smooth(gone * 1.2f - .2f, gone * 1.2f, f);
            // 整根往一边轻轻弯、慢慢摆，越往上摆得越开，像真的龙卷
            float bend = .1f + .25f * f;
            double sx = bend * r * Mth.sin(f * 2.6f + t * .03f + l.phase()), sz = bend * r * Mth.cos(f * 2.1f + t * .026f + l.phase());
            v[j] = t * l.rise() - f * l.tall();
            // 表面起伏顺着扭转的方向盘上去，轮廓不再是笔直的竖线
            float spiral = Mth.TWO_PI * l.twist() * f;
            for (int i = 0; i <= segments; i++) {
                float th = Mth.TWO_PI * i / segments;
                float lump = 1 + l.lumps() * (.6f * Mth.sin(3 * (th - spiral) + t * .05f + l.phase()) + .4f * Mth.sin(5 * (th - spiral) - f * 6 + t * .08f + l.phase() * 2));
                float cos = Mth.cos(th), sin = Mth.sin(th);
                Vec3 q = at.add(sx + cos * r * lump, y + lift, sz + sin * r * lump);
                p[j][i] = q;
                float alpha = a;
                Vec3 to = view.cam().subtract(q);
                double len = to.length();
                float facing = len < 1e-4 ? 1 : (float) Math.abs((to.x * cos + to.z * sin) / len);
                if (l.fresnel() > 0) {
                    // 正对镜头的一面几乎透明，斜着看的地方最亮，正好擦着轮廓的那一圈又收一点，不画出生硬的竖边
                    float e = 1 - facing;
                    float edge = .1f + .9f * smooth(0, .75f, e) * (1 - .45f * smooth(.88f, 1, e));
                    alpha *= Mth.lerp(l.fresnel(), 1, edge);
                }
                // 里面几层在施法者身高以内、朝向镜头的那一面切开一个窗（镂空层从贴图 alpha 低的地方先切掉，窗边参差），看得清人
                if (r < 3.2f * s) {
                    float front = len < 1e-4 ? 0 : (float) Math.max(0, (to.x * cos + to.z * sin) / len);
                    alpha *= 1 - .97f * (float) Math.pow(front, 1.2) * (1 - smooth(1.9f * s, 2.7f * s, y));
                }
                alpha *= VfxParts.fade(view.cam(), q, .7, 2.6);
                c[j][i] = argb(alpha, rgb);
            }
        }
        float uTwist = l.twist() * l.around(), u0 = t * l.spin();
        for (int j = 0; j < rings; j++) {
            float fa = ((float) j / rings * (yTop - yLo) + yLo) / s, fb = ((float) (j + 1) / rings * (yTop - yLo) + yLo) / s;
            float ua = u0 + uTwist * (fa - l.bottom()) / height, ub = u0 + uTwist * (fb - l.bottom()) / height;
            for (int i = 0; i < segments; i++) {
                if ((c[j][i] | c[j][i + 1] | c[j + 1][i] | c[j + 1][i + 1]) >>> 24 == 0) continue;
                float s0 = l.around() * i / segments, s1 = l.around() * (i + 1) / segments;
                VfxParts.vertex(pose, buf, p[j][i], ua + s0, v[j], c[j][i]);
                VfxParts.vertex(pose, buf, p[j + 1][i], ub + s0, v[j + 1], c[j + 1][i]);
                VfxParts.vertex(pose, buf, p[j + 1][i + 1], ub + s1, v[j + 1], c[j + 1][i + 1]);
                VfxParts.vertex(pose, buf, p[j][i + 1], ua + s1, v[j], c[j][i + 1]);
            }
        }
    }

    /** 地上的圆盘：极坐标铺贴图，越靠中心转得越多，卷成旋涡；随时间旋转、往中心流。中心和边缘都淡。 */
    private void disc(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, Disc d, float t, float spread, float strength, int rgb) {
        int rings = 10, segments = 48;
        float big = d.radius() * scale * (.4f + .6f * spread);
        Vec3 center = at.add(0, .04, 0);
        for (int j = 0; j < rings; j++) {
            float k0 = (float) j / rings, k1 = (float) (j + 1) / rings;
            int c0 = argb(strength * d.alpha() * smooth(.05f, .25f, k0) * (1 - smooth(.6f, 1, k0)), rgb);
            int c1 = argb(strength * d.alpha() * smooth(.05f, .25f, k1) * (1 - smooth(.6f, 1, k1)), rgb);
            if ((c0 | c1) >>> 24 == 0) continue;
            float w0 = d.swirl() * (1 - k0), w1 = d.swirl() * (1 - k1), v0 = k0 * 2 + t * d.inflow(), v1 = k1 * 2 + t * d.inflow();
            for (int i = 0; i < segments; i++) {
                float a0 = Mth.TWO_PI * i / segments, a1 = Mth.TWO_PI * (i + 1) / segments;
                float s0 = d.around() * ((float) i / segments) + t * d.spin(), s1 = d.around() * ((float) (i + 1) / segments) + t * d.spin();
                VfxParts.vertex(pose, buf, center.add(Mth.cos(a0) * big * k0, 0, Mth.sin(a0) * big * k0), s0 + w0 * d.around(), v0, c0);
                VfxParts.vertex(pose, buf, center.add(Mth.cos(a0) * big * k1, 0, Mth.sin(a0) * big * k1), s0 + w1 * d.around(), v1, c1);
                VfxParts.vertex(pose, buf, center.add(Mth.cos(a1) * big * k1, 0, Mth.sin(a1) * big * k1), s1 + w1 * d.around(), v1, c1);
                VfxParts.vertex(pose, buf, center.add(Mth.cos(a1) * big * k0, 0, Mth.sin(a1) * big * k0), s1 + w0 * d.around(), v0, c0);
            }
        }
    }

    private void motes(PoseStack stack, SubmitNodeCollector out, View view, Vec3 at, float t, float pt, float turns) {
        if (!smoke.isEmpty()) out.submitCustomGeometry(stack, VfxParts.blend("physical_ray/smoke"), (pose, buf) -> {
            for (Mote m : smoke) {
                float k = (t - m.born) / m.life;
                if (k < 0 || k >= 1) continue;
                float angle = m.angle + m.spin * pt;
                Vec3 q = at.add(Mth.cos(angle) * m.radius, m.y + m.vy * pt, Mth.sin(angle) * m.radius);
                float a = Math.min(k / .15f, 1) * (1 - k) * (1 - k) * .4f * VfxParts.fade(view.cam(), q, 1.5, 4);
                if (a > 0) VfxParts.sprite(pose, buf, view, q, m.size * (1 + 1.5f * k), m.born + k, argb(a, m.tint));
            }
        });
        if (!dots.isEmpty()) out.submitCustomGeometry(stack, VfxParts.add("heal/glow"), (pose, buf) -> {
            for (Mote d : dots) {
                float k = (t - d.born) / d.life;
                if (k < 0 || k >= 1) continue;
                float angle = d.angle + d.spin * pt;
                Vec3 q = at.add(Mth.cos(angle) * d.radius, Math.max(0, d.y + d.vy * pt), Mth.sin(angle) * d.radius);
                float a = Math.min(k / .1f, 1) * (1 - k * k) * VfxParts.fade(view.cam(), q, .5, 1.5);
                if (a > 0) VfxParts.sprite(pose, buf, view, q, d.size * 2.5f, 0, argb(a, d.tint == 0xFFFFFF ? 0xFFFFFF : VfxParts.hue(d.tint, turns)));
            }
        });
    }

    private void rocks(PoseStack stack, SubmitNodeCollector out, View view, Vec3 at, float t, float pt) {
        if (rocks.isEmpty()) return;
        out.submitCustomGeometry(stack, VfxParts.BLOCKS, (pose, buf) -> {
            for (Rock r : rocks) {
                float gone = r.landed >= 0 ? (t - r.landed - 12) / 10 : 0;
                float angle = Mth.lerp(pt, r.prevAngle, r.angle), radius = Mth.lerp(pt, r.prevRadius, r.radius), y = Mth.lerp(pt, r.prevY, r.y);
                Vec3 q = at.add(Mth.cos(angle) * radius, y, Mth.sin(angle) * radius);
                float a = (1 - Mth.clamp(gone, 0, 1)) * Math.min(1, (t - r.born) / 3) * VfxParts.fade(view.cam(), q, 1, 2.5);
                if (a <= 0) continue;
                TextureAtlasSprite s = r.sprite;
                float du = (s.getU1() - s.getU0()) / 4, dv = (s.getV1() - s.getV0()) / 4;
                VfxParts.cube(pose, buf, q, r.size, r.turn, s.getU0() + du, s.getV0() + dv, s.getU0() + du * 2.5f, s.getV0() + dv * 2.5f, 0xFFFFFF, a);
            }
        });
    }
}
