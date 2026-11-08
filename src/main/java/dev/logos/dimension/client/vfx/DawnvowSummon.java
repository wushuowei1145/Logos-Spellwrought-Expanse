package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.longsword.LongswordPayload;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 曦誓不挂剑鞘，拔剑与收剑换成光：
 * <ul>
 *   <li>拔剑（{@code draw}，动作 {@code dawnvow_draw}）：双臂伸直到身前、掌心向上，金色光点从四周旋着聚拢到横在掌上的剑的轮廓上
 *       （剑身、护手、握柄、剑首），剑的虚影随之渐亮，{@link #FORM} 秒时剑凝成实体、整把剑一闪；随后右手握住剑柄转两圈剑花，
 *       双手合握回到待机；</li>
 *   <li>收剑（{@code sheathe}，动作 {@code dawnvow_sheathe}）：双手把剑倒转，{@link #PLUNGE} 秒插进身前的地面，剑随之亮起；
 *       松手之后 {@link #VANISH} 秒散成光点，向外飘散、上浮、淡去。</li>
 * </ul>
 * 都画在剑骨骼上（像素单位，原点在握柄，剑身沿 +Y），跟着手走；第一、第三人称一样。光点是三片互相垂直的小光片，
 * 从哪个角度看都是一颗亮点。贴图复用 Logos 的 {@code fire_beam/dot} 与 {@code laser}。动作由 tools/dawnvow_moves.py 生成，
 * 时刻与这里对齐。
 */
public final class DawnvowSummon {
    /** 剑凝成实体的时刻、凝成后的一闪持续多久、收剑时剑消失的时刻、散开的光点飘多久（秒）。 */
    private static final double FORM = 0.7, FLASH = 0.3, PLUNGE = 0.3, VANISH = 0.62, DRIFT = 0.35;
    private static final int MOTES = 56;
    /** 光点的金色（与大型蓄力的光点同色）。 */
    private static final int MOTE = 0xFFDB8C;
    /** 剑的轮廓（像素）：剑尖、护手中心、握柄底、剑首底。 */
    private static final float TIP = 23.4f, GUARD = 4.9f, GRIP = -3.4f, POMMEL = -5.7f;

    private DawnvowSummon() {}

    /** 招式已经播了多少秒。 */
    private static double age(LongswordPayload p, double now) { return Math.max(0, (now - p.started()) / 20); }

    private static boolean dawnvow(LongswordPayload p) { return p != null && p.variant() == 0; }

    /** 剑的模型此刻画不画：拔剑凝成之前、收剑消失之后不画。 */
    public static boolean visible(LongswordPayload p, double now) {
        if (!dawnvow(p)) return true;
        return switch (p.move()) {
            case "draw" -> age(p, now) >= FORM;
            case "sheathe" -> age(p, now) < VANISH;
            default -> true;
        };
    }

    /** 拔剑、收剑时在剑骨骼上画光点与剑的光影；其余招式不画。 */
    public static void submit(LongswordPayload p, double now, LongswordRig.Sample sample, PoseStack pose, SubmitNodeCollector collector, boolean firstPerson) {
        if (!dawnvow(p)) return;
        boolean draw = p.move().equals("draw");
        if (!draw && !p.move().equals("sheathe")) return;
        double age = age(p, now);
        if (draw ? age > FORM + FLASH : age > VANISH + DRIFT) return;
        SubmitNodeCollector out = ShaderPackOverlay.item(collector, firstPerson);
        pose.pushPose();
        pose.mulPose(sample.weapon());
        // 剑的光影：拔剑时随光点聚拢渐亮、凝成时一闪；收剑时先亮起再随剑散去
        float shade = draw
                ? (float) (age < FORM ? .35 * Math.pow(age / FORM, 2) : 1 - (age - FORM) / FLASH)
                : (float) (age < PLUNGE ? 0 : age < VANISH ? (age - PLUNGE) / (VANISH - PLUNGE) : Math.max(0, 1 - (age - VANISH) / .12));
        if (shade > .005f) out.submitCustomGeometry(pose, DawnvowAura.LASER.world(), (q, buf) -> {
            for (int k = 0; k < 3; k++) {
                Vec3 across = new Vec3(Math.cos(k * Math.PI / 3), 0, Math.sin(k * Math.PI / 3));
                shape(q, buf, across, 5.5f, .55f * shade, DawnvowAura.GOLD);
                shape(q, buf, across, 1.6f, .9f * shade, DawnvowAura.HOT);
            }
        });
        out.submitCustomGeometry(pose, DawnvowAura.DOT.world(), (q, buf) -> {
            for (int i = 0; i < MOTES; i++) {
                Vec3 target = target(i);
                Vec3 at;
                float a, size = .9f + .8f * hash(i, 5);
                if (draw) {
                    // 各自晚一点出发，从四周旋着落到剑上
                    double delay = .35 * hash(i, 1), k = Mth.clamp((age / FORM - delay) / (1 - delay), 0, 1);
                    if (age >= FORM || k <= 0) continue;
                    double in = k * k * (2.2 - 1.2 * k);
                    Vec3 start = target.add(scatter(i).scale(10 + 8 * hash(i, 2)));
                    double turn = (1 - in) * Math.PI * (1.2 + hash(i, 3)) * (hash(i, 4) < .5 ? -1 : 1);
                    at = spin(start.lerp(target, in), target, turn);
                    a = VfxParts.smooth(0, .15f, (float) k) * (1 - VfxParts.smooth(.85f, 1, (float) k));
                } else {
                    // 从剑上散开，往外飘、往上浮
                    double k = Mth.clamp((age - VANISH + .05) / DRIFT, 0, 1);
                    if (k <= 0 || k >= 1) continue;
                    double out2 = 1 - (1 - k) * (1 - k);
                    at = target.add(scatter(i).scale(9 * out2)).add(0, 6 * k * k, 0);
                    a = 1 - VfxParts.smooth(.4f, 1, (float) k);
                    size *= 1 - .4f * (float) k;
                }
                star(q, buf, at, size, VfxParts.argb(.95f * a, hash(i, 6) < .3 ? DawnvowAura.HOT : MOTE));
            }
        });
        pose.popPose();
    }

    /** 第 i 个光点落在剑上的位置：大半在剑身，其余在护手、握柄与剑首。 */
    private static Vec3 target(int i) {
        float r = hash(i, 0), s = hash(i, 7) - .5f;
        if (r < .6f) return new Vec3(s * 2.4, Mth.lerp(r / .6f, GUARD + .8f, TIP), 0);
        if (r < .8f) return new Vec3(s * 14, GUARD + .5 * hash(i, 8), 0);
        if (r < .93f) return new Vec3(s * 1, Mth.lerp((r - .8f) / .13f, GRIP, GUARD - .5f), 0);
        return new Vec3(s * 1.4, POMMEL + hash(i, 8) * 1.5, 0);
    }

    /** 第 i 个光点飘离的方向（单位向量，略偏上）。 */
    private static Vec3 scatter(int i) {
        double a = hash(i, 9) * Mth.TWO_PI, y = hash(i, 10) * 1.4 - .5;
        return new Vec3(Math.cos(a), y, Math.sin(a)).normalize();
    }

    /** 绕着目标所在的竖轴转 {@code turn}。 */
    private static Vec3 spin(Vec3 p, Vec3 about, double turn) {
        double x = p.x - about.x, z = p.z - about.z, c = Math.cos(turn), s = Math.sin(turn);
        return new Vec3(about.x + x * c - z * s, p.y, about.z + x * s + z * c);
    }

    /** 一颗亮点：三片互相垂直的小光片。 */
    private static void star(PoseStack.Pose pose, VertexConsumer buf, Vec3 c, float r, int color) {
        VfxParts.flat(pose, buf, c, new Vec3(1, 0, 0), new Vec3(0, 1, 0), r, 0, color);
        VfxParts.flat(pose, buf, c, new Vec3(0, 1, 0), new Vec3(0, 0, 1), r, 0, color);
        VfxParts.flat(pose, buf, c, new Vec3(1, 0, 0), new Vec3(0, 0, 1), r, 0, color);
    }

    /** 剑的光影一片：沿剑从剑首到剑尖、宽 {@code width}，两端收窄，护手处另加一横。 */
    private static void shape(PoseStack.Pose pose, VertexConsumer buf, Vec3 across, float width, float alpha, int rgb) {
        float[] ys = {POMMEL, GUARD, TIP - 3, TIP + 1};
        float[] ws = {.4f, 1, .8f, 0};
        for (int i = 0; i < ys.length - 1; i++) {
            Vec3 a = new Vec3(0, ys[i], 0), b = new Vec3(0, ys[i + 1], 0);
            Vec3 ha = across.scale(width * ws[i] / 2), hb = across.scale(width * ws[i + 1] / 2);
            int c = VfxParts.argb(alpha, rgb);
            VfxParts.vertex(pose, buf, a.add(ha), .5f, 0, c);
            VfxParts.vertex(pose, buf, a.subtract(ha), .5f, 1, c);
            VfxParts.vertex(pose, buf, b.subtract(hb), .5f, 1, c);
            VfxParts.vertex(pose, buf, b.add(hb), .5f, 0, c);
        }
        // 护手的一横
        Vec3 g = new Vec3(0, GUARD + .4, 0), side = new Vec3(1, 0, 0).scale(8), up = new Vec3(0, 1, 0).scale(width * .35);
        int c = VfxParts.argb(alpha, rgb);
        VfxParts.vertex(pose, buf, g.subtract(side).add(up), .5f, 0, c);
        VfxParts.vertex(pose, buf, g.subtract(side).subtract(up), .5f, 1, c);
        VfxParts.vertex(pose, buf, g.add(side).subtract(up), .5f, 1, c);
        VfxParts.vertex(pose, buf, g.add(side).add(up), .5f, 0, c);
    }

    private static float hash(int i, int salt) {
        long h = (i + 1) * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        return ((h >>> 40) & 0xFFFFFF) / (float) 0x1000000;
    }
}
