package dev.logos.dimension.client.vfx;

import static dev.logos.dimension.client.vfx.VfxParts.argb;
import static dev.logos.dimension.client.vfx.VfxParts.lerpColor;
import static dev.logos.dimension.client.vfx.VfxParts.smooth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.dimension.client.vfx.VfxParts.View;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 从某处爆发、直冲天际的金色光柱（参考 NiagaeaBigVFXPack 的 NS_FlamePillar 与 NS_FlamePillar_Stone）：
 * 亮面与暗斑块交错的几层同心光柱在六刻内冲上 64 格，保持约一秒半，随后底部贴着地面收细成一条线再熄灭；脚下有地面光晕与旋转光环，
 * 两道贴地扩散的冲击波和持续荡开的涟漪，柱脚甩出的两圈火舌，柱内上冲的光痕与四周升起的光点，被掀起的尘土（按地面方块的地图色染色）和
 * 用周围方块自己贴图的碎石。只在客户端绘制，大小随 {@code scale} 缩放，全程约四秒。
 */
public final class PillarBurstVisual {
    static final int LIFE = 84;
    private static final int WHITE = 0xFFF4D0, LIGHT = 0xFFD873, MAIN = 0xFFB52E, DEEP = 0xE08A1A, DARK = 0x2B1606;
    /** 冲起用 RISE 刻；保持到 HOLD；之后收细，到 LINE 只剩一条线；GONE 时熄灭。 */
    private static final float RADIUS = 1.6f, HEIGHT = 64, RISE = 6, HOLD = 42, LINE = 66, GONE = 74;
    /** 亮面贴图一格绕柱子横向占多少格（暗斑块那层另有自己的），纵向再拉长一点。按世界尺寸铺，柱子再高也不会被竖着拉长。 */
    private static final float TILE = 3.2f, TALL = 1.35f;

    /**
     * 光柱的一层：材质、半径倍数、透明度、上冲速度（格/刻）、颜色、贴图一格的横向格数与纵向倍数，
     * 以及越往上纵向越被拉长的程度（{@code stretch}，0 为不拉）。照 M_FlamePillar_main 的思路，
     * 亮面（veins）和不透明的暗斑块（cells，同 main1 的暗红褐基色）交错，暗块挡住后面的亮层，上冲时更显速度。
     */
    private record Layer(net.minecraft.client.renderer.rendertype.RenderType material, float radius, float alpha, float speed, int rgb,
            float tile, float tall, float stretch) {
        Layer(net.minecraft.client.renderer.rendertype.RenderType material, float radius, float alpha, float speed, int rgb) {
            this(material, radius, alpha, speed, rgb, TILE, TALL, 0);
        }
    }

    /** 拉长随高度增长的指数：底部三成几乎不变，越近顶端越陡。 */
    private static final float STRETCH_POWER = 3;
    /** 有拉长的层，竖向切成多少段算 UV。 */
    private static final int BANDS = 24;

    /**
     * 按这个顺序画（每层一个 order，见 {@link VfxParts#layered}）。暗斑块最先画，里层的加法亮面叠在它上面，
     * 把斑块冲淡成零星的琥珀色暗块（维护者认可的样子）；金色罩层再盖在外面。
     */
    private static final Layer[] LAYERS = {
            // 暗斑块铺得更大、更高：UE 里的暗块比亮面的纹理粗得多。越往上被拉得越长，近顶端几乎成一条线
            new Layer(VfxParts.blendOwn("pillar_burst/cells"), 1, .75f, 1.4f, DARK, 4.5f, 1.8f, 40),
            new Layer(VfxParts.addOwn("pillar_burst/veins"), .45f, .55f, 2.2f, WHITE),
            new Layer(VfxParts.add("charge/pillar"), .25f, .35f, 2.6f, WHITE),
            new Layer(VfxParts.addOwn("pillar_burst/veins"), .85f, .75f, 1.7f, MAIN),
            // 白天的天空很亮，加法发光会被冲成白色：在亮层外面罩一层半透明的金色把它染回来
            new Layer(VfxParts.blend("charge/pillar"), 1.06f, .45f, 1.2f, MAIN),
            new Layer(VfxParts.addOwn("pillar_burst/veins"), 1.18f, .6f, 1.2f, MAIN),
            new Layer(VfxParts.add("charge/pillar"), 1.6f, .16f, .8f, DEEP)};

    private record Spark(float born, float speed, float radius, float angle, float length) {}

    /** 根部甩出的一条火舌：朝向（绕柱的角度）、离竖直的倾角、长度、宽度、起始帧、跳动相位、往外飘的速度。 */
    private record Flare(float angle, float tilt, float length, float width, int frame, float phase, float drift) {}

    /** 火舌：甩出（刻）、保持到、完全消失。 */
    private static final float FLARE_OUT = 4, FLARE_HOLD = 10, FLARE_GONE = 58;

    /** 碎石或尘土：带上一刻位置，绘制时插值。 */
    private static final class Bit {
        Vec3 pos, prev, vel;
        final float size, life, born;
        final Quaternionf turn = new Quaternionf(), spin;
        final TextureAtlasSprite sprite;
        final int tint;
        float landed = -1;

        Bit(Vec3 pos, Vec3 vel, float size, float life, float born, Quaternionf spin, TextureAtlasSprite sprite, int tint) {
            this.pos = this.prev = pos;
            this.vel = vel;
            this.size = size;
            this.life = life;
            this.born = born;
            this.spin = spin;
            this.sprite = sprite;
            this.tint = tint;
        }
    }

    private final Vec3 base;
    private final float scale;
    private final RandomSource random;
    private final int seed;
    private final List<Spark> sparks = new ArrayList<>(), motes = new ArrayList<>();
    private final List<Bit> rocks = new ArrayList<>(), dust = new ArrayList<>();
    private final List<Flare> flares = new ArrayList<>();
    private int age;

    PillarBurstVisual(Level level, Vec3 base, float scale, long seed) {
        this.base = base;
        this.scale = scale;
        this.random = RandomSource.create(seed);
        this.seed = (int) (seed ^ seed >>> 32);
        for (int i = 0; i < 48; i++) {
            sparks.add(new Spark(random.nextFloat() * 38, 1.8f + 1.8f * random.nextFloat(), RADIUS * .9f * Mth.sqrt(random.nextFloat()),
                    random.nextFloat() * Mth.TWO_PI, 1.6f + 2.2f * random.nextFloat()));
        }
        for (int i = 0; i < 30; i++) {
            motes.add(new Spark(random.nextFloat() * 34, .12f + .18f * random.nextFloat(), RADIUS * (1.4f + 2.6f * random.nextFloat()),
                    random.nextFloat() * Mth.TWO_PI, .05f + .07f * random.nextFloat()));
        }
        int ground = groundTint(level);
        for (int i = 0; i < 20; i++) {
            float a = random.nextFloat() * Mth.TWO_PI, r = RADIUS * (1.1f + 1.2f * random.nextFloat()) * scale;
            Vec3 out = new Vec3(Mth.cos(a), 0, Mth.sin(a));
            dust.add(new Bit(base.add(out.scale(r)).add(0, .3 * scale, 0), out.scale((.18 + .2 * random.nextFloat()) * scale).add(0, .03 * scale, 0),
                    .7f + .5f * random.nextFloat(), 40 + random.nextInt(24), random.nextInt(4), null, null, ground));
        }
        for (int i = 0; i < 40; i++) rock(level, i < 30 ? 0 : random.nextInt(4));
        // 上圈像王冠一样向外上方张开，下圈几乎贴地往外喷（参考图里红线与蓝箭头）
        for (int i = 0; i < 16; i++) {
            flares.add(new Flare((i + .6f * random.nextFloat()) / 16 * Mth.TWO_PI, (35 + 20 * random.nextFloat()) * Mth.DEG_TO_RAD,
                    3 + 2 * random.nextFloat(), 1.6f + .8f * random.nextFloat(), random.nextInt(4), random.nextFloat() * 6, .02f + .02f * random.nextFloat()));
        }
        for (int i = 0; i < 12; i++) {
            flares.add(new Flare((i + .3f + .6f * random.nextFloat()) / 12 * Mth.TWO_PI, (70 + 12 * random.nextFloat()) * Mth.DEG_TO_RAD,
                    3 + 2 * random.nextFloat(), 2 + .8f * random.nextFloat(), random.nextInt(4), random.nextFloat() * 6, .04f + .03f * random.nextFloat()));
        }
    }

    /** 尘土的颜色：脚下方块的地图色，往浅灰偏一些，免得草地扬起绿色的灰。 */
    private int groundTint(Level level) {
        BlockState state = level.getBlockState(BlockPos.containing(base.x, base.y - .5, base.z));
        int map = state.isAir() ? 0x9A8F80 : state.getMapColor(level, BlockPos.containing(base.x, base.y - .5, base.z)).col;
        return lerpColor(map, 0xB8AFA2, .45f);
    }

    /** 掀起一块碎石：在光柱周围取地面方块，用它自己的贴图；空气或特殊渲染的方块就不掀。 */
    private void rock(Level level, int born) {
        float a = random.nextFloat() * Mth.TWO_PI, r = RADIUS * (.6f + 1.9f * random.nextFloat()) * scale;
        Vec3 out = new Vec3(Mth.cos(a), 0, Mth.sin(a));
        Vec3 at = base.add(out.scale(r)).add(0, .15, 0);
        BlockState state = level.getBlockState(BlockPos.containing(at.x, base.y - .5, at.z));
        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) return;
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
        if (!sprite.atlasLocation().getPath().contains("blocks")) return;
        // 约 35° 的锥形向上抛出（NS_FlamePillar_Stone 的 Cone Angle 35.2）
        double up = (.45 + .5 * random.nextDouble()) * scale, side = (.1 + .3 * random.nextDouble()) * scale;
        Quaternionf spin = new Quaternionf().rotateXYZ((random.nextFloat() - .5f) * .5f, (random.nextFloat() - .5f) * .5f, (random.nextFloat() - .5f) * .5f);
        Bit b = new Bit(at, out.scale(side).add(0, up, 0), (.07f + .11f * random.nextFloat()) * scale, 46 + random.nextInt(14), born, spin, sprite, 0xFFFFFF);
        b.turn.rotateXYZ(random.nextFloat() * 6.3f, random.nextFloat() * 6.3f, random.nextFloat() * 6.3f);
        rocks.add(b);
    }

    /** 一个客户端刻。播完返回 false。 */
    boolean tick() {
        age++;
        for (Bit b : rocks) {
            b.prev = b.pos;
            if (age < b.born || b.landed >= 0) continue;
            b.vel = new Vec3(b.vel.x * .98, b.vel.y - .055 * scale, b.vel.z * .98);
            b.pos = b.pos.add(b.vel);
            b.turn.mul(b.spin);
            if (b.vel.y < 0 && b.pos.y <= base.y + b.size) {
                b.pos = new Vec3(b.pos.x, base.y + b.size, b.pos.z);
                b.prev = b.pos;
                b.landed = age;
            }
        }
        for (Bit d : dust) {
            d.prev = d.pos;
            if (age < d.born) continue;
            d.vel = d.vel.multiply(.9, .96, .9);
            d.pos = d.pos.add(d.vel);
        }
        return age < LIFE;
    }

    /** {@code layered}：光柱各层按序提交的入口（见 {@link VfxParts#layered}），其余部分照常走 {@code out}。 */
    void submit(View view, float pt, PoseStack stack, SubmitNodeCollector out, IntFunction<OrderedSubmitNodeCollector> layered) {
        float t = age + pt;
        // 整体强度：开头一闪，保持时轻微起伏，之后熄灭
        float shimmer = 1 + .06f * Mth.sin(t * .9f) + .04f * Mth.sin(t * 2.3f);
        float strength = t < 2 ? 1.4f * t / 2 : t < 10 ? Mth.lerp((t - 2) / 8, 1.4f, 1) : shimmer * (1 - smooth(HOLD, GONE, t));
        // 光柱自己：收细期间保持亮度，细成一条线以后才熄灭
        float beam = t < 10 ? strength : shimmer * (1 - smooth(LINE, GONE, t));
        pillar(stack, layered, view, t, beam);
        groundGlow(stack, out, t, strength);
        shockwaves(stack, out, t);
        ripples(stack, out, t);
        flares(stack, layered, view, t);
        sparks(stack, out, view, t, strength);
        dust(stack, out, view, t, pt);
        rocks(stack, out, view, t, pt);
    }

    private void pillar(PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layered, View view, float t, float strength) {
        if (strength <= 0) return;
        float k = Mth.clamp(t / RISE, 0, 1);
        float top = HEIGHT * scale * (1 - (1 - k) * (1 - k) * (1 - k));
        // 收尾时底部始终贴着地面，柱身越收越细，到 LINE 时只剩一条线，随后熄灭
        float thin = smooth(HOLD, LINE, t);
        float bottom = 0, leave = 0;
        float width = (t < 10 ? Mth.lerp(t / 10, 1.35f, 1) : 1) * (1 - .97f * thin);
        float mid = (top - bottom) * .3f;
        double dx = view.cam().x - base.x, dz = view.cam().z - base.z;
        float near = (float) Math.sqrt(dx * dx + dz * dz);
        for (int i = 0; i < LAYERS.length; i++) {
            Layer l = LAYERS[i];
            int index = i;
            layered.apply(i).submitCustomGeometry(stack, l.material(), (pose, buf) -> layer(pose, buf, l, index, t, strength, width, bottom, mid, top, leave, near));
        }
    }

    /**
     * 一层光柱：下三成从底部亮起，上七成渐隐到顶。贴图按世界尺寸铺（绕一圈取整数格，免得接缝），
     * 以每刻 {@code speed} 格的速度向上冲，相邻层反向缓转。
     */
    private void layer(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, Layer l, int i, float t, float strength,
            float width, float bottom, float mid, float top, float leave, float near) {
        float r = RADIUS * scale * l.radius() * width;
        // 镜头走近、进到光柱里面时淡出，不糊满视野，也不留一道生硬的竖边
        float a = strength * l.alpha() * smooth(r, r * 2 + 1.5f, near);
        if (a <= 0) return;
        // 绕一圈的格数按原本的半径算：柱子收细时贴图跟着横向压紧，不会因为取整一格一格地跳
        float around = Math.max(1, Math.round(Mth.TWO_PI * RADIUS * scale * l.radius() / (l.tile() * scale)));
        float tall = l.tile() * l.tall() * scale, v0 = t * l.speed() * scale / tall;
        float u0 = (i % 2 == 0 ? 1 : -1) * t * .003f * (i + 1);
        Vec3 foot = base.add(0, bottom, 0), waist = base.add(0, mid, 0);
        int segments = Math.max(24, (int) around * 8);
        if (l.stretch() > 0) {
            stretched(pose, buf, l, r, segments, u0, around, tall, v0, bottom, mid, top, a, leave);
            return;
        }
        VfxParts.cylinder(pose, buf, foot, r, mid - bottom, segments, u0, around, v0 + (top - mid) / tall, (mid - bottom) / tall,
                argb(a * (1 - leave), l.rgb()), argb(a, l.rgb()));
        VfxParts.cylinder(pose, buf, waist, r, top - mid, segments, u0, around, v0, (top - mid) / tall, argb(a, l.rgb()), argb(0, l.rgb()));
    }

    /**
     * 竖向切段画一层，贴图的纵向密度随高度连续减小：离地 {@code f}（占满高的比例）处一格贴图被拉长
     * {@code 1 + stretch·f^3} 倍。底部和其他层一样，越往上越长，近顶端几乎拉成一条线；滚动时上面也就冲得越快。
     */
    private void stretched(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, Layer l, float r, int segments, float u0,
            float around, float tall, float v0, float bottom, float mid, float top, float a, float leave) {
        float full = HEIGHT * scale, step = (top - bottom) / BANDS;
        // 从柱脚往上累计走过了多少格贴图；贴图 V 向上减小，所以 v = v0 - 累计
        float walked = 0;
        for (int b = 0; b < BANDS; b++) {
            float y0 = bottom + step * b, y1 = y0 + step;
            float f = (y0 + y1) * .5f / full;
            float length = step / (tall * (1 + l.stretch() * (float) Math.pow(f, STRETCH_POWER)));
            float lo = v0 - walked, hi = lo - length;
            VfxParts.cylinder(pose, buf, base.add(0, y0, 0), r, step, segments, u0, around, hi, lo - hi,
                    argb(profile(y0, bottom, mid, top, a, leave), l.rgb()), argb(profile(y1, bottom, mid, top, a, leave), l.rgb()));
            walked += length;
        }
    }

    /** 和普通层一样的透明度：柱脚到三成处亮起（离地时柱脚变淡），再往上渐隐到顶。 */
    private static float profile(float y, float bottom, float mid, float top, float a, float leave) {
        if (y <= mid) return Mth.lerp((y - bottom) / Math.max(1e-3f, mid - bottom), a * (1 - leave), a);
        return Mth.lerp((y - mid) / Math.max(1e-3f, top - mid), a, 0);
    }

    private void groundGlow(PoseStack stack, SubmitNodeCollector out, float t, float strength) {
        Vec3 at = base.add(0, .04, 0);
        float r = RADIUS * scale;
        float flash = Math.max(0, 1 - t / 9);
        out.submitCustomGeometry(stack, VfxParts.add("heal/glow"), (pose, buf) -> {
            if (strength > 0) VfxParts.ground(pose, buf, at, r * 4.5f, 0, argb(strength * .5f, MAIN));
            if (flash > 0) VfxParts.ground(pose, buf, at.add(0, .01, 0), r * (3 + 5 * (1 - flash)), 0, argb(flash, WHITE));
        });
        if (strength <= 0) return;
        out.submitCustomGeometry(stack, VfxParts.add("heal/ring"), (pose, buf) -> {
            // NS_FlamePillar 的 Under_ring：脚下旋转的光环（约 80°/秒）
            VfxParts.ground(pose, buf, at.add(0, .02, 0), r * 2.3f, t * .07f, argb(strength * .75f, LIGHT));
            VfxParts.ground(pose, buf, at.add(0, .03, 0), r * 1.45f, -t * .11f, argb(strength * .6f, WHITE));
        });
        out.submitCustomGeometry(stack, VfxParts.add("heal/swirl"), (pose, buf) ->
                VfxParts.ground(pose, buf, at.add(0, .015, 0), r * 3.2f, t * .05f, argb(strength * .35f, MAIN)));
    }

    /** 两道贴地向外扩散的冲击波：地面亮环加一圈低矮的风墙（同 Logos 蓄力巨型的波浪）。 */
    private void shockwaves(PoseStack stack, SubmitNodeCollector out, float t) {
        float[][] waves = {{0, 22, 11}, {4, 30, 15}};
        List<float[]> live = new ArrayList<>();
        for (float[] w : waves) {
            float k = (t - w[0]) / w[1];
            if (k >= 0 && k < 1) live.add(new float[] {k, w[2]});
        }
        if (live.isEmpty()) return;
        out.submitCustomGeometry(stack, VfxParts.add("heal/ring"), (pose, buf) -> {
            for (float[] w : live) {
                float k = w[0], r = RADIUS * scale * (1.2f + w[1] * (1 - (float) Math.pow(1 - k, 2.5)));
                VfxParts.ground(pose, buf, base.add(0, .06, 0), r, k, argb((1 - k) * smooth(0, .06f, k), LIGHT));
            }
        });
        out.submitCustomGeometry(stack, VfxParts.add("physical_ray/wind"), (pose, buf) -> {
            for (float[] w : live) {
                float k = w[0], r = RADIUS * scale * (1.2f + w[1] * (1 - (float) Math.pow(1 - k, 2.5)));
                float h = (.25f + 1.6f * (1 - k)) * scale;
                float a = (1 - k) * (1 - k) * smooth(0, .08f, k) * .75f;
                VfxParts.cylinder(pose, buf, base.add(0, .03, 0), r, h, 48, t * .02f, 8, 0, 1, argb(a, MAIN), argb(0, LIGHT));
            }
        });
    }

    /**
     * 光柱立着的整段时间里，脚下每隔几刻就向外荡开一圈白色气浪：边缘起伏、有断有续，往里拖着气流纹。
     * 每圈从三种形状里挑一种，随机转向、横纵各自略微压扁，不会一圈圈都一样。光柱开始离地后不再新生。
     * 按时间算出，不必保存状态。
     */
    private void ripples(PoseStack stack, SubmitNodeCollector out, float t) {
        int every = 4, life = 24;
        int first = Math.max(0, (int) Math.ceil((t - life - 2) / every)), last = (int) Math.floor((Math.min(t, HOLD + 6) - 2) / every);
        if (last < first) return;
        for (int variant = 0; variant < 3; variant++) {
            int shape = variant;
            out.submitCustomGeometry(stack, VfxParts.addOwn("pillar_burst/ripple_" + shape), (pose, buf) -> {
                for (int i = first; i <= last; i++) {
                    // 由序号散列出这圈的形状、转向和压扁，同一圈每帧都一样
                    int hash = mix(i * 0x9E3779B9 ^ seed);
                    if (Math.floorMod(hash, 3) != shape) continue;
                    float k = (t - 2 - i * every) / life;
                    if (k < 0 || k >= 1) continue;
                    float r = RADIUS * scale * (1.05f + 5.5f * (1 - (1 - k) * (1 - k)));
                    // 越晚生出的越弱，跟着光柱一起收
                    float a = (1 - k) * smooth(0, .1f, k) * .9f * (1 - smooth(HOLD - 10, HOLD + 6, 2 + i * every));
                    if (a <= 0) continue;
                    float turn = (hash >>> 8 & 1023) / 1023f * Mth.TWO_PI;
                    float squash = .88f + .24f * ((hash >>> 18 & 255) / 255f);
                    Vec3 along = new Vec3(Mth.cos(turn), 0, Mth.sin(turn)), across = new Vec3(-Mth.sin(turn), 0, Mth.cos(turn));
                    VfxParts.quad(pose, buf, base.add(0, .05 + .002 * (i % 5), 0), along.scale(r * squash), across.scale(r / squash), argb(a, 0xFFFFFF));
                }
            });
        }
    }

    /**
     * 爆发时从柱脚一起甩出的两圈火舌（参考《碧蓝幻想 Relink》夏洛特奥义光柱根部的火焰气浪）：四刻内甩到全长，
     * 亮着保持一会儿，之后约两秒半慢慢变淡、变细，同时继续往外飘。每条是一张绕自身轴面向镜头的长条，
     * 用 {@code pillar_burst/flame} 的四帧轮流切换着跳动。先画一层半透明的深金底色（白天的亮天空会把加法发光
     * 冲成白色），再叠金色发光和细一些的白芯；两种材质的先后用 {@code layered} 固定，排在光柱各层之后。
     */
    private void flares(PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layered, View view, float t) {
        if (t >= FLARE_GONE) return;
        float grow = 1 - (float) Math.pow(1 - Math.min(1, t / FLARE_OUT), 3);
        float fade = smooth(FLARE_HOLD, FLARE_GONE, t);
        float bright = (t < 2 ? .6f + .4f * t / 2 : 1) * (1 - fade);
        if (bright <= 0) return;
        layered.apply(LAYERS.length).submitCustomGeometry(stack, VfxParts.blendOwn("pillar_burst/flame"), (pose, buf) ->
                flareStrips(pose, buf, view, t, grow, fade, bright, true));
        layered.apply(LAYERS.length + 1).submitCustomGeometry(stack, VfxParts.addOwn("pillar_burst/flame"), (pose, buf) ->
                flareStrips(pose, buf, view, t, grow, fade, bright, false));
    }

    /** 所有火舌的长条：{@code tint} 为真时画深金底色，否则画金色发光加白芯。 */
    private void flareStrips(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buf, View view, float t, float grow, float fade,
            float bright, boolean tint) {
        {
            for (Flare f : flares) {
                // 火苗跳动：长度轻微伸缩，每两刻换一帧
                float lick = 1 + .08f * Mth.sin(t * .9f + f.phase) + .05f * Mth.sin(t * 2.1f + f.phase * 2);
                float length = f.length * scale * grow * lick * (1 + .25f * fade);
                Vec3 outward = new Vec3(Mth.cos(f.angle), 0, Mth.sin(f.angle));
                Vec3 dir = outward.scale(Mth.sin(f.tilt)).add(0, Mth.cos(f.tilt), 0);
                // 根部贴着柱脚，消退时整条往外飘
                Vec3 tail = base.add(outward.scale(RADIUS * scale * (1.05f + f.drift * t))).add(0, .15 * scale, 0);
                Vec3 head = tail.add(dir.scale(length));
                float near = VfxParts.fade(view.cam(), tail.add(head).scale(.5), 1.2, 3.5);
                float a = bright * near;
                if (a <= 0) continue;
                float width = f.width * scale * (1 - .4f * fade);
                int frame = (f.frame + (int) (t / 2)) % 4;
                float v0 = frame / 4f, v1 = v0 + .25f;
                if (tint) {
                    VfxParts.streak(pose, buf, view, tail, head, width, 0, v0, 1, v1, argb(a * .6f, DEEP));
                    continue;
                }
                VfxParts.streak(pose, buf, view, tail, head, width, 0, v0, 1, v1, argb(a * .6f, MAIN));
                VfxParts.streak(pose, buf, view, tail, tail.add(dir.scale(length * .75f)), width * .4f, 0, v0, 1, v1, argb(a * .35f, WHITE));
            }
        }
    }

    /** 把一个整数打散成看起来随机的整数（murmur3 的收尾步骤）。 */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        return h ^ h >>> 16;
    }

    /** 柱内向上冲的光痕（grain_up），以及四周慢慢升起的光点（grain2）。按时间算出，不必保存状态。 */
    private void sparks(PoseStack stack, SubmitNodeCollector out, View view, float t, float strength) {
        float top = HEIGHT * scale;
        out.submitCustomGeometry(stack, VfxParts.add("force/streak"), (pose, buf) -> {
            for (Spark s : sparks) {
                float y = (t - s.born) * s.speed * scale;
                if (y < 0 || y > top) continue;
                Vec3 head = base.add(Mth.cos(s.angle) * s.radius * scale, y, Mth.sin(s.angle) * s.radius * scale);
                float a = Math.min(1, strength + .3f) * (1 - y / top) * .9f;
                VfxParts.streak(pose, buf, view, head.add(0, -s.length * scale, 0), head, .1f * scale, argb(a, WHITE));
            }
        });
        out.submitCustomGeometry(stack, VfxParts.add("heal/glow"), (pose, buf) -> {
            for (Spark m : motes) {
                float life = 30, k = (t - m.born) / life;
                if (k < 0 || k >= 1) continue;
                float y = (t - m.born) * m.speed * scale;
                Vec3 at = base.add(Mth.cos(m.angle) * m.radius * scale, .2 + y, Mth.sin(m.angle) * m.radius * scale);
                float a = Math.min(k / .15f, (1 - k) / .4f) * VfxParts.fade(view.cam(), at, .8, 2.5);
                if (a > 0) VfxParts.sprite(pose, buf, view, at, m.length * scale * 2.2f, 0, argb(a, (int) m.born % 3 == 0 ? WHITE : LIGHT));
            }
        });
    }

    private void dust(PoseStack stack, SubmitNodeCollector out, View view, float t, float pt) {
        out.submitCustomGeometry(stack, VfxParts.blend("physical_ray/smoke"), (pose, buf) -> {
            for (Bit d : dust) {
                float k = (t - d.born) / d.life;
                if (k < 0 || k >= 1) continue;
                Vec3 at = d.prev.lerp(d.pos, pt);
                float a = Math.min(k / .08f, 1) * (1 - k) * (1 - k) * .42f * VfxParts.fade(view.cam(), at, 1.5, 4);
                float size = d.size * scale * (1 + 2.2f * (1 - (1 - k) * (1 - k)));
                if (a > 0) VfxParts.sprite(pose, buf, view, at, size, d.born + k * .8f, argb(a, d.tint));
            }
        });
    }

    private void rocks(PoseStack stack, SubmitNodeCollector out, View view, float t, float pt) {
        if (rocks.isEmpty()) return;
        out.submitCustomGeometry(stack, VfxParts.BLOCKS, (pose, buf) -> {
            for (Bit b : rocks) {
                if (t < b.born) continue;
                // 落地后再躺一会儿才淡掉
                float gone = b.landed >= 0 ? (t - b.landed - 12) / 10 : (t - b.born - b.life) / 8;
                float a = (1 - Mth.clamp(gone, 0, 1));
                Vec3 at = b.prev.lerp(b.pos, pt);
                a *= VfxParts.fade(view.cam(), at, 1, 2.5);
                if (a <= 0) continue;
                TextureAtlasSprite s = b.sprite;
                float du = (s.getU1() - s.getU0()) / 4, dv = (s.getV1() - s.getV0()) / 4;
                VfxParts.cube(pose, buf, at, b.size, b.turn, s.getU0() + du, s.getV0() + dv, s.getU0() + du * 2.5f, s.getV0() + dv * 2.5f, b.tint, a);
            }
        });
    }
}
