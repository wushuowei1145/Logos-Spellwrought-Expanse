package dev.logos.dimension.client.companion;

import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateShow;
import dev.logos.dimension.companion.LacertaUltimate;
import dev.logos.dimension.ultimate.UltimatePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔的奥义「绯穹裁决 · 炽龙裂地破」在客户端的画面（docs/LACERTA_COMPANION.md 第 22 节）：下令的玩家看一段过场（{@link UltimateFilm}），
 * 所有看得见她的人都看得到世界里的特效（{@link LacertaUltimateFx}：碎岩、光柱、地裂、刀光、龙炎巨刃）。
 *
 * <p>完整版的分镜（刻数照 {@link LacertaUltimate.Beats#FULL}）：
 * <ol>
 *   <li>0–11 超广角低机位急追，视角 70°→95°，径向模糊加往外喷的速度线；</li>
 *   <li>11–24 绕她甩一整圈，横向模糊，打中那一下定格两刻；</li>
 *   <li>24–37 低机位仰摇，跟着上撩的剑往天上看，纵向模糊、斜的速度线；</li>
 *   <li>37–51 从下面仰拍她登穹，视角收窄微推；模糊归零，速度线反过来往她身上吸，第 46–50 刻定格；</li>
 *   <li>51–63 贴着她的背往下猛坠，纵向模糊和速度线拉满；</li>
 *   <li>63–74 硬切到贴地的机位，定格三刻、重震（黑白冲击帧由服务端发）；</li>
 *   <li>74–91 急拉远、慢慢仰起来，让崩飞的碎岩和冲天的光柱撑满画面；</li>
 *   <li>91–107 她拄剑立定，焦烟火星擦镜回到玩家视角。</li>
 * </ol>
 * 缩短版（{@link LacertaUltimate.Beats#SHORT}）只有登穹、定格、坠下、裂地、拉远，再擦镜。
 *
 * <p>机位都从她身上或落点往外按碰撞量（{@link CameraShots#reach}），室内不会嵌进墙和天花板。
 */
public final class LacertaUltimateShow implements UltimateShow {
    /** 擦镜的颜色：焦烟、带火光的烟（亮的火星在世界里另有）。 */
    static final int SMOKE = 0x231C1E, EMBER = 0x7A3A26;
    /** 速度线的颜色：漫画的粗黑线。 */
    static final int INK = 0x140A0C;

    private final LacertaUltimate.Beats beats;
    private final @Nullable UltimateFilm film;
    final LacertaUltimateFx fx;

    private LacertaUltimateShow(UltimatePayloads.Cast cast) {
        this.beats = LacertaUltimate.Beats.of(cast.condensed());
        this.fx = new LacertaUltimateFx(cast, beats);
        UltimateFilm f = build(cast);
        this.film = f.play() ? f : null;
    }

    /** {@code CompanionStyle.LACERTA} 里放的工厂：每次开演新开一个；不是本机下的令时也开（世界里的特效大家都看得见），只是不接管镜头。 */
    public static UltimateShow begin(UltimatePayloads.Cast cast) {
        LacertaUltimateShow show = new LacertaUltimateShow(cast);
        LacertaUltimateFx.add(show.fx);
        return show;
    }

    @Override
    public boolean tick(UltimatePayloads.Cast cast, int age) {
        // 世界里的特效按客户端世界的游戏刻自己走（LacertaUltimateFx），演完还要淡一会儿
        return age < cast.length();
    }

    @Override
    public void end(UltimatePayloads.Cast cast, boolean interrupted) {
        if (interrupted) {
            if (film != null) film.stop();
            LacertaUltimateFx.remove(fx);
        } else {
            fx.fadeOut();
        }
    }

    /** 测试用：这一次的过场（不是本机下的令时是 null）。 */
    public @Nullable UltimateFilm film() {
        return film;
    }

    public LacertaUltimateFx fx() {
        return fx;
    }

    // ---------------------------------------------------------------- 分镜

    private UltimateFilm build(UltimatePayloads.Cast cast) {
        UltimateFilm f = UltimateFilm.of(cast);
        return beats.condensed() ? condensed(f) : full(f);
    }

    private UltimateFilm full(UltimateFilm f) {
        LacertaUltimate.Beats b = beats;
        Vec3 d = fx.dir(), r = fx.right(), center = fx.center();
        // Cut 1 翼震音爆 · 贴地冲阵：她右前方贴着地面，跟着她冲，视角一下拉宽
        f.cut(0, b.dash(), s -> {
            Vec3 feet = s.feet();
            Vec3 from = safe(s.chest(), feet.subtract(d.scale(.6)).add(r.scale(2.4)).add(0, .35, 0));
            return s.look(from, feet.add(d.scale(4)).add(0, .9, 0), s.lerp(-4, 3), s.lerp(70, 95, s.easeOut()));
        });
        f.blur(0, b.dash(), 2, UltimateFilm.Blur.RADIAL, .75f, 0, .5f, .5f);
        f.lines(0, b.dash(), 2, UltimateFilm.Lines.RADIAL, .75f, 0, INK);
        // Cut 2 撞阵横扫：从高处陡陡地往下看、绕着她甩一整圈（被拉过来的敌群围着她，刀光的圆环看得全），砍中那一下定格两刻
        f.cut(b.dash(), b.ascend(), s -> {
            float yaw = s.lerp(-150, 210);
            Vec3 chest = s.chest();
            Vec3 from = safe(chest, ring(chest, d, r, yaw, 3.4).add(0, 4, 0));
            return s.look(from, chest.add(0, -.3, 0), s.lerp(6, -6), 80);
        });
        f.freeze(b.sweepHit(), b.sweepHit() + 2);
        f.shake(b.sweepHit(), b.sweepHit() + 3, 1, .08f);
        f.blur(b.dash(), b.ascend(), 2, UltimateFilm.Blur.DIRECTIONAL, .7f, 0, .5f, .5f);
        f.lines(b.dash(), b.ascend(), 2, UltimateFilm.Lines.DIRECTIONAL, .5f, 0, INK);
        // Cut 3 逆焰升龙挑空：在她身侧贴地仰摇（敌群在她前面，不挡镜头），跟着上撩的剑往天上看
        f.cut(b.ascend(), b.rise(), s -> {
            Vec3 chest = s.chest();
            Vec3 from = safe(chest, s.feet().add(r.scale(3.4)).subtract(d.scale(.6)).add(0, .25, 0));
            float up = s.lerp(0, 4.5f, s.easeOut());
            return s.look(from, chest.add(0, up, 0).add(d.scale(s.lerp(.3f, 1.2f))), s.lerp(-3, -10), 84);
        });
        f.blur(b.ascend(), b.rise(), 2, UltimateFilm.Blur.DIRECTIONAL, .8f, 90, .5f, .5f);
        f.lines(b.ascend(), b.rise(), 2, UltimateFilm.Lines.DIRECTIONAL, .55f, -65, INK);
        // Cut 4 振翼登穹：从落点后下方仰拍她冲上去、举剑过顶，微推；第 46–50 刻定格（全片唯一一次滞空）
        apex(f, b.rise(), b.dive(), center, d, r);
        f.freeze(b.apex(), b.dive() - 1);
        f.lines(b.rise() + 3, b.dive(), 2, UltimateFilm.Lines.CONVERGE, .7f, 0, INK);
        f.grade(b.rise(), b.dive(), 3, .25f, .1f, 0xFFB070, .12f, .45f);
        // Cut 5 赤星坠击：贴着她的背往下猛坠
        dive(f, b.dive(), b.impact(), d, r);
        f.blur(b.dive(), b.impact(), 1, UltimateFilm.Blur.DIRECTIONAL, 1, 90, .5f, .5f);
        f.lines(b.dive(), b.impact(), 1, UltimateFilm.Lines.DIRECTIONAL, 1, 90, INK);
        f.shake(b.dive(), b.impact(), 2, .04f);
        // Cut 6 炽剑裂地：硬切贴地，定格三刻、重震
        impact(f, b.impact(), b.impact() + 11, center, d, r);
        f.freeze(b.impact(), b.recover());
        f.shake(b.impact(), b.impact() + 11, 1, .32f);
        f.blur(b.impact(), b.impact() + 11, 1, UltimateFilm.Blur.RADIAL, .45f, 0, .5f, .55f);
        f.grade(b.impact(), b.stand(), 3, 0, 0, 0xFFE7C0, .15f, .35f);
        // Cut 7 万岩崩飞 · 炽白光柱：急拉远、慢慢仰起来
        pullOut(f, b.impact() + 11, b.stand() + 1, center, d, r, 6, 14);
        f.shake(b.impact() + 11, b.stand() + 1, 3, .1f);
        f.blur(b.impact() + 11, b.stand() + 1, 3, UltimateFilm.Blur.DIRECTIONAL, .65f, 90, .5f, .5f);
        f.lines(b.impact() + 11, b.stand() + 1, 3, UltimateFilm.Lines.RADIAL, .6f, 0, INK, .5f, .6f);
        // Cut 8 焦烟火星擦镜：凑近她拄剑立定的样子，烟和火星从左往右扫过去
        f.cut(b.stand() + 1, b.length(), s -> {
            Vec3 chest = s.chest();
            Vec3 from = safe(chest, s.feet().add(d.scale(1.9)).add(r.scale(1.3)).add(0, 1.2, 0));
            return s.look(from, chest.add(0, .1, 0), 0, 60);
        });
        f.wipe(b.wipe(), b.length(), UltimateFilm.Wipe.SMOKE, SMOKE, EMBER);
        return f;
    }

    private UltimateFilm condensed(UltimateFilm f) {
        LacertaUltimate.Beats b = beats;
        Vec3 d = fx.dir(), r = fx.right(), center = fx.center(), home = fx.home();
        // 从她起跳的地方仰拍：振翼冲上去
        f.cut(0, b.apex(), s -> {
            Vec3 from = safe(s.chest(), home.add(r.scale(2.2)).subtract(d.scale(1.2)).add(0, .3, 0));
            return s.look(from, s.chest(), s.lerp(0, -6), s.lerp(72, 90));
        });
        f.blur(0, b.apex(), 2, UltimateFilm.Blur.RADIAL, .45f, 0, .5f, .5f);
        f.lines(0, b.apex(), 2, UltimateFilm.Lines.RADIAL, .6f, 0, INK);
        // 登穹定格
        apex(f, b.apex(), b.dive(), center, d, r);
        f.freeze(b.apex() + 1, b.dive());
        f.lines(b.apex(), b.dive(), 1, UltimateFilm.Lines.CONVERGE, .7f, 0, INK);
        f.grade(b.apex(), b.dive(), 1, .22f, .1f, 0xFFB070, .12f, .45f);
        // 陨星坠下
        dive(f, b.dive(), b.impact(), d, r);
        f.blur(b.dive(), b.impact(), 1, UltimateFilm.Blur.DIRECTIONAL, 1, 90, .5f, .5f);
        f.lines(b.dive(), b.impact(), 1, UltimateFilm.Lines.DIRECTIONAL, 1, 90, INK);
        // 裂地：定格、重震
        impact(f, b.impact(), b.impact() + 7, center, d, r);
        f.freeze(b.impact(), b.impact() + 2);
        f.shake(b.impact(), b.impact() + 10, 1, .3f);
        f.blur(b.impact(), b.impact() + 5, 1, UltimateFilm.Blur.RADIAL, .45f, 0, .5f, .55f);
        f.grade(b.impact(), b.length(), 2, 0, 0, 0xFFE7C0, .15f, .3f);
        // 拉远看碎岩和光柱，擦镜
        pullOut(f, b.impact() + 7, b.length(), center, d, r, 6, 11);
        f.lines(b.impact() + 7, b.wipe() + 2, 2, UltimateFilm.Lines.RADIAL, .5f, 0, INK, .5f, .6f);
        f.wipe(b.wipe(), b.length(), UltimateFilm.Wipe.SMOKE, SMOKE, EMBER);
        return f;
    }

    /** 登穹：从落点后下方陡陡地仰拍她（逆着天光成剪影），视角收窄微推。 */
    private static void apex(UltimateFilm f, float start, float end, Vec3 center, Vec3 d, Vec3 r) {
        Vec3 base = center.add(0, 1, 0);
        f.cut(start, end, s -> {
            Vec3 from = safe(base, center.subtract(d.scale(2.6)).subtract(r.scale(1.8)).add(0, .5, 0));
            return s.look(from, s.chest().add(0, .4, 0), s.lerp(0, 4), s.lerp(70, 48, s.easeOut()));
        });
    }

    /** 坠下：贴着她的背，往下看着冲上来的地面。 */
    private static void dive(UltimateFilm f, float start, float end, Vec3 d, Vec3 r) {
        f.cut(start, end, s -> {
            Vec3 chest = s.chest();
            Vec3 from = safe(chest, chest.add(0, 2, 0).subtract(d.scale(1.3)).add(r.scale(.4)));
            return s.look(from, s.feet().add(0, -3, 0).add(d.scale(.6)), s.lerp(0, 6), 95);
        });
    }

    /** 裂地：硬切到低机位（离开碎岩崩飞的那一圈），看着她和插进地里的剑。 */
    private static void impact(UltimateFilm f, float start, float end, Vec3 center, Vec3 d, Vec3 r) {
        f.cut(start, end, s -> {
            Vec3 feet = s.feet();
            Vec3 from = safe(s.chest(), feet.subtract(d.scale(5)).add(r.scale(2.6)).add(0, 1.1, 0));
            return s.look(from, feet.add(0, .7, 0).add(d.scale(1)), -4, 74);
        });
    }

    /** 急拉远、慢慢仰起来：{@code near} 格拉到 {@code far} 格，看的点从地面升到光柱半腰。 */
    private static void pullOut(UltimateFilm f, float start, float end, Vec3 center, Vec3 d, Vec3 r, double near, double far) {
        Vec3 base = center.add(0, 1.2, 0);
        f.cut(start, end, s -> {
            double dist = Mth.lerp(s.easeOut(), near, far);
            Vec3 want = center.subtract(d.scale(dist * .8)).add(r.scale(dist * .6)).add(0, Mth.lerp(s.ease(), 1.0, 3.2), 0);
            return s.look(safe(base, want), center.add(0, s.lerp(1.2f, 7), 0), s.lerp(-2, 2), 88);
        });
    }

    /** 以 {@code center} 为心、离 {@code dist} 格的一点：{@code yaw} 0 在她正前方（{@code d}），90 在她右手边。 */
    static Vec3 ring(Vec3 center, Vec3 d, Vec3 r, float yaw, double dist) {
        double a = Math.toRadians(yaw);
        return center.add(d.scale(Math.cos(a) * dist)).add(r.scale(Math.sin(a) * dist));
    }

    /** 机位：从 {@code anchor}（她身上、落点上方，总在空处）往 {@code want} 量，被方块挡住就停在前面。 */
    static Vec3 safe(Vec3 anchor, Vec3 want) {
        var level = Minecraft.getInstance().level;
        return level == null ? want : CameraShots.reach(level, anchor, want);
    }

    /** 测试用：本机现在是不是这一段的镜头在跑。 */
    public boolean filming() {
        return film != null && UltimateFilm.playing() == film && CameraDirector.active();
    }

    /** 演的人（不在本机的世界里时是 null）。 */
    static @Nullable Entity performer(UltimatePayloads.Cast cast) {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntity(cast.performer());
    }
}
