package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.VfxParts;
import dev.logos.dimension.dawnvow.DawnvowBurst;
import dev.logos.dimension.dawnvow.DawnvowCharge;
import dev.logos.dimension.dawnvow.SwordWave;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 蓄满后劈出的巨型金色剑气（客户端）：一面向前鼓起的金色弧形光墙，每刻 {@link DawnvowCharge#GRAND_SPEED} 格沿地面冲出去
 * （服务端定好的路线，贴着地形起伏），身后留下一条宽阔的光流，过一秒多淡去；剑气头四散金色火星。挥剑的一刻剑前一闪。
 * 走到头时剑气扎进地面：落点一闪、四溅的火星；地上那团光、穹顶、几何黑白闪和白光是全屏后期（{@link DawnvowScreen}），
 * 按 {@link DawnvowBurst} 的时间轴；光柱冲起时（服务端放 Logos 的金色光柱）镜头重重一震。
 * 光墙与光流用 Logos 物理射线的 {@code physical_ray/wind}（向前滚动），火星用 {@code force/streak}。
 */
final class ExcaliburWave {
    private static final PostLayer WIND_BODY = PostLayer.blend("physical_ray/wind"), WIND_GLOW = PostLayer.add("physical_ray/wind");
    private static final PostLayer FLASH = PostLayer.add("fire_impact/flash"), STREAK = PostLayer.add("force/streak");
    /** 光墙宽、高、向前鼓出多少。 */
    private static final float WIDTH = 10, HEIGHT = 9, BOW = 3.2f;
    /** 光流在剑气头过去后多少刻淡完。 */
    private static final int WAKE = 30;
    private static final int COLS = 18, ROWS = 6;
    /** 光柱冲起后，后期再用多少刻退回原样。 */
    static final int AFTER = 8;

    private record Spark(Vec3 at, Vec3 vel, long born, int life, float size) {}

    /** 剑气扎进地面后四溅的火星：相对爆点的起点、每刻的速度，到头后第几刻出现、活几刻、多宽、颜色。 */
    private record Ember(Vec3 at, Vec3 vel, float born, float life, float width, int rgb) {}

    private final Vec3 from, dir, side, up = new Vec3(0, 1, 0);
    private final float[] path;
    private final double length;
    private final long start;
    private final int entity;
    private final List<Spark> sparks = new ArrayList<>();
    private final List<Ember> embers = new ArrayList<>();
    private boolean landed, blacked, pillared;

    ExcaliburWave(int entity, Vec3 from, Vec3 dir, float[] path, long start) {
        this.entity = entity;
        this.from = from;
        this.dir = dir.normalize();
        this.side = new Vec3(-this.dir.z, 0, this.dir.x);
        this.path = path.length == 0 ? new float[]{(float) from.y} : path;
        this.length = (this.path.length - 1) * SwordWave.PATH_STEP;
        this.start = start;
        // 四溅的火星事先定好（按放出的那一刻取随机数）：哪一刻画出来都一样，客户端场景停住时钟截图也对得上
        RandomSource r = RandomSource.create(start * 31 + entity);
        for (int i = 0; i < 110; i++) {
            double a = r.nextDouble() * Mth.TWO_PI, d = r.nextDouble() * 2.2, out = .12 + .5 * r.nextDouble();
            embers.add(new Ember(new Vec3(Math.cos(a) * d, .2 + .6 * r.nextDouble(), Math.sin(a) * d),
                    new Vec3(Math.cos(a) * out, .15 + .45 * r.nextDouble(), Math.sin(a) * out),
                    (float) (r.nextDouble() * r.nextDouble() * DawnvowBurst.BLACK), 8 + r.nextInt(16), .05f + .07f * r.nextFloat(),
                    r.nextBoolean() ? DawnvowAura.HOT : DawnvowAura.PALE));
        }
    }

    /** 路线上离起点 {@code s} 处的地面点。 */
    private Vec3 point(double s) {
        Vec3 at = from.add(dir.scale(s));
        return new Vec3(at.x, SwordWave.heightAt(path, s), at.z);
    }

    int entity() { return entity; }

    /** 剑气头离起点多远（帧间插值）。 */
    private double head(double now) {
        return Mth.clamp((now - start) * DawnvowCharge.GRAND_SPEED, 0, length);
    }

    /** 到头的游戏刻（小数）。 */
    double arrive() {
        return start + length / DawnvowCharge.GRAND_SPEED;
    }

    /** 剑气停下的那一点（地面）：穹顶、光柱都在这里。 */
    Vec3 end() {
        return point(length);
    }

    /** 此刻剑气头所在的地面点。 */
    Vec3 headAt(double now) {
        return point(head(now));
    }

    /** 星星排布的种子：同一道剑气每次画出来都一样。 */
    int seed() {
        return (int) (start * 7 + entity * 13) & 0xFFFF;
    }

    /** 一个客户端刻；全部画完返回 false。 */
    boolean tick(Level level, long now) {
        RandomSource r = level.getRandom();
        // 扎进地面、黑色爆发时镜头各轻轻一震，光柱冲起时重重一震，离得越近越重
        if (!landed && now >= arrive()) { landed = true; DawnvowShake.at(end(), .35f); }
        if (!blacked && now >= arrive() + DawnvowBurst.BLACK) { blacked = true; DawnvowShake.at(end(), .3f); }
        if (!pillared && now >= arrive() + SwordWave.PILLAR_DELAY) { pillared = true; DawnvowShake.at(end(), 1); }
        if (now <= arrive()) {
            Vec3 head = point(head(now));
            for (int i = 0; i < 10; i++) {
                double across = (r.nextDouble() - .5) * WIDTH, high = r.nextDouble() * HEIGHT * .8;
                Vec3 at = head.add(side.scale(across)).add(0, high, 0);
                Vec3 vel = side.scale(Math.signum(across) * (.15 + .25 * r.nextDouble())).add(0, .1 + .2 * r.nextDouble(), 0)
                        .add(dir.scale(.3 + .5 * r.nextDouble()));
                sparks.add(new Spark(at, vel, now, 12 + r.nextInt(14), .1f + .12f * r.nextFloat()));
            }
        }
        for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) if (now - it.next().born() > 30) it.remove();
        return now < arrive() + Math.max(WAKE, SwordWave.PILLAR_DELAY + AFTER) || !sparks.isEmpty();
    }

    void submit(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, double now) {
        double head = head(now);
        boolean travelling = now < arrive();
        // 挥剑一刻剑前的一闪
        float swing = (float) (now - start);
        if (swing < 8) FLASH.submit(pose, out, (p, buf) -> VfxParts.sprite(p, buf, view, from.add(0, 1.4, 0).add(dir.scale(.5)),
                3.5f * (.5f + .5f * VfxParts.smooth(0, 2, swing)), swing * .1f, VfxParts.argb(.85f * (1 - VfxParts.smooth(0, 8, swing)), DawnvowAura.HOT)));
        // 身后的光流
        WIND_GLOW.submit(pose, out, (p, buf) -> wake(p, buf, now, head, false));
        DawnvowAura.LASER.submit(pose, out, (p, buf) -> wake(p, buf, now, head, true));
        if (travelling) {
            Vec3 at = point(head);
            float scroll = (float) now * .25f;
            WIND_BODY.submit(pose, out, (p, buf) -> wall(p, buf, at, scroll, .55f, DawnvowAura.GOLD));
            WIND_GLOW.submit(pose, out, (p, buf) -> wall(p, buf, at, scroll, .75f, DawnvowAura.PALE));
            DawnvowAura.GLOW.submit(pose, out, (p, buf) -> VfxParts.sprite(p, buf, view, at.add(0, HEIGHT * .45, 0).add(dir.scale(BOW * .6)),
                    7, 0, VfxParts.argb(.3f, DawnvowAura.PALE)));
        }
        if (!sparks.isEmpty()) DawnvowAura.DOT.submit(pose, out, (p, buf) -> {
            for (Spark s : sparks) {
                double age = now - s.born();
                if (age < 0 || age > s.life()) continue;
                Vec3 at = s.at().add(s.vel().scale(age)).add(0, -.012 * age * age, 0);
                float a = 1 - VfxParts.smooth(s.life() * .5f, s.life(), (float) age);
                VfxParts.sprite(p, buf, view, at, s.size(), 0, VfxParts.argb(.9f * a, DawnvowAura.HOT));
            }
        });
        double since = now - arrive();
        if (since >= 0 && since < DawnvowBurst.CONTRAST) landing(pose, out, view, (float) since);
    }

    /**
     * 剑气扎进地面之后、黑白闪之前：落点一闪，火星从那团光里四溅、拖着短短的光痕。
     * 地上那团光和穹顶由全屏后期画（{@link DawnvowScreen}），这里只画它四周的东西；黑白闪那几拍画面整个换掉，不画。
     */
    private void landing(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, float age) {
        Vec3 at = end();
        if (age < 6) FLASH.submit(pose, out, (p, buf) -> VfxParts.sprite(p, buf, view, at.add(0, 1, 0), 9 * (.5f + .5f * VfxParts.smooth(0, 2, age)),
                age * .05f, VfxParts.argb(.95f * (1 - VfxParts.smooth(0, 6, age)), DawnvowAura.HOT)));
        float gone = 1 - VfxParts.smooth(DawnvowBurst.CONTRAST - 2, DawnvowBurst.CONTRAST, age);
        STREAK.submit(pose, out, (p, buf) -> {
            for (Ember e : embers) {
                float t = age - e.born();
                if (t < 0 || t > e.life()) continue;
                float a = VfxParts.smooth(0, 1.5f, t) * (1 - VfxParts.smooth(e.life() * .55f, e.life(), t)) * gone;
                VfxParts.streak(p, buf, view, emberAt(at, e, Math.max(0, t - 1.6f)), emberAt(at, e, t), e.width(), VfxParts.argb(.95f * a, e.rgb()));
            }
        });
    }

    private static Vec3 emberAt(Vec3 burst, Ember e, float t) {
        return burst.add(e.at()).add(e.vel().scale(t)).add(0, -.018 * t * t, 0);
    }

    /**
     * 剑气头的光墙：横跨 {@link #WIDTH}、高 {@link #HEIGHT}，中间向前鼓出 {@link #BOW}，两边与顶上收窄变淡，
     * 贴图沿横向往外滚。{@code alpha} 是最亮处的透明度。
     */
    private void wall(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, float scroll, float alpha, int rgb) {
        Vec3[][] grid = new Vec3[COLS + 1][ROWS + 1];
        int[][] color = new int[COLS + 1][ROWS + 1];
        for (int i = 0; i <= COLS; i++) {
            float t = 2f * i / COLS - 1;
            for (int j = 0; j <= ROWS; j++) {
                float v = (float) j / ROWS;
                double high = v * HEIGHT * (1 - .45 * t * t);
                grid[i][j] = at.add(side.scale(t * WIDTH / 2)).add(0, high, 0).add(dir.scale(BOW * (1 - t * t) * (1 - .35 * v)));
                float a = (float) Math.pow(1 - t * t, .6) * (1 - v * v * .85f) * (v < .1f ? v / .1f * .6f + .4f : 1);
                color[i][j] = VfxParts.argb(alpha * a, rgb);
            }
        }
        for (int i = 0; i < COLS; i++) {
            float u0 = (float) i / COLS * 2 - scroll, u1 = (float) (i + 1) / COLS * 2 - scroll;
            for (int j = 0; j < ROWS; j++) {
                float v0 = (float) j / ROWS, v1 = (float) (j + 1) / ROWS;
                VfxParts.vertex(pose, buf, grid[i][j], u0, v0, color[i][j]);
                VfxParts.vertex(pose, buf, grid[i][j + 1], u0, v1, color[i][j + 1]);
                VfxParts.vertex(pose, buf, grid[i + 1][j + 1], u1, v1, color[i + 1][j + 1]);
                VfxParts.vertex(pose, buf, grid[i + 1][j], u1, v0, color[i + 1][j]);
            }
        }
    }

    /**
     * 剑气身后的光流：沿路线一横一竖两条宽带（十字截面，从哪个角度看都有宽度），{@code core} 时是中间一条白亮的细芯。
     * 每处在剑气头过去后 {@link #WAKE} 刻淡完。
     */
    private void wake(PoseStack.Pose pose, VertexConsumer buf, double now, double head, boolean core) {
        if (head <= 0) return;
        double step = 1;
        Vec3 mid = new Vec3(0, HEIGHT * .42, 0);
        float scroll = (float) now * .3f;
        for (double s0 = 0; s0 < head; s0 += step) {
            double s1 = Math.min(head, s0 + step);
            float a0 = wakeAlpha(now, s0, head), a1 = wakeAlpha(now, s1, head);
            if (a0 <= 0 && a1 <= 0) continue;
            Vec3 p0 = point(s0).add(mid), p1 = point(s1).add(mid);
            if (core) {
                quad(pose, buf, p0, p1, side.scale(.7), a0 * .8f, a1 * .8f, DawnvowAura.HOT, .5f, .5f);
                quad(pose, buf, p0, p1, up.scale(.7), a0 * .8f, a1 * .8f, DawnvowAura.HOT, .5f, .5f);
            } else {
                float u0 = (float) s0 / 12 - scroll, u1 = (float) s1 / 12 - scroll;
                quad(pose, buf, p0, p1, side.scale(WIDTH * .45), a0 * .6f, a1 * .6f, DawnvowAura.GOLD, u0, u1);
                quad(pose, buf, p0, p1, up.scale(HEIGHT * .5), a0 * .6f, a1 * .6f, DawnvowAura.GOLD, u0, u1);
            }
        }
    }

    /** 光流一段：从 {@code p0} 到 {@code p1}，横向半宽 {@code half}，中线最亮、两边淡到透明，两端透明度各自。 */
    private static void quad(PoseStack.Pose pose, VertexConsumer buf, Vec3 p0, Vec3 p1, Vec3 half, float a0, float a1, int rgb, float u0, float u1) {
        int c0 = VfxParts.argb(a0, rgb), c1 = VfxParts.argb(a1, rgb), e = VfxParts.argb(0, rgb);
        VfxParts.vertex(pose, buf, p0.add(half), u0, 0, e);
        VfxParts.vertex(pose, buf, p0, u0, .5f, c0);
        VfxParts.vertex(pose, buf, p1, u1, .5f, c1);
        VfxParts.vertex(pose, buf, p1.add(half), u1, 0, e);
        VfxParts.vertex(pose, buf, p0, u0, .5f, c0);
        VfxParts.vertex(pose, buf, p0.subtract(half), u0, 1, e);
        VfxParts.vertex(pose, buf, p1.subtract(half), u1, 1, e);
        VfxParts.vertex(pose, buf, p1, u1, .5f, c1);
    }

    /** 光流离起点 {@code s} 处此刻的透明度：剑气头刚过时最亮，之后淡去；靠近起点的一段渐显。 */
    private float wakeAlpha(double now, double s, double head) {
        double passed = now - (start + s / DawnvowCharge.GRAND_SPEED);
        if (passed < 0) return 0;
        float near = VfxParts.smooth(0, 4, (float) s);
        return near * (1 - VfxParts.smooth(0, WAKE, (float) passed)) * (s > head - 1 ? .7f : 1);
    }
}
