package dev.logos.dimension.client.companion;

import dev.logos.client.VfxParts;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateShow;
import dev.logos.dimension.companion.PrincessUltimatePlan;
import dev.logos.dimension.ultimate.UltimatePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * 精灵公主的奥义「万分之一 · 世界树祈愿矢」在客户端的画面（docs/ELF_PRINCESS_COMPANION.md 第 11 节）：
 * 下令的玩家看分镜运镜（{@link UltimateFilm}），所有看得见她的人都看得到世界里画的种子、新苗、主箭、莲华法阵和光矢（{@link PrincessUltimateFx}）。
 * 时刻照 {@link PrincessUltimatePlan} 的时间表，和服务端脚本对齐。
 *
 * <p>完整版的八镜（分镜稿）：
 * <ol>
 *   <li>0–13 种子破土：3 刻闭幕压黑，硬切到地面微距，四周压黑只留新苗那一点（她自己的后期 {@link PrincessUltimateScreen}），极慢推进；</li>
 *   <li>13–28 抽枝化箭：贴着螺旋往上长的藤蔓旋转拉远，落到她搭箭的手上（匹配剪辑）；</li>
 *   <li>28–41 满弓蓄势：低机位从她右手边弧形摇过来，画面歪着，速度线往箭尖吸，最后 3 刻定格；</li>
 *   <li>41–50 破空追踪：过肩一拍，切到贴着箭尾急追，广角、径向模糊；</li>
 *   <li>50–64 莲华化雨：法阵高度的侧面急拉成大远景，往下摇到地面的敌群；</li>
 *   <li>64–76 箭雨洗地：跟着第一批光矢俯冲到贴地，再横着甩过落点；</li>
 *   <li>76–91 圣箭贯地：广角定机位，巨箭从画面上方垂直贯下，第 79 刻贯地时强烈震屏、往外喷的速度线（黑白闪由服务端在这一拍发）；</li>
 *   <li>91–104 叶片擦镜：一大片发光的叶子从左往右扫过，盖满时回到玩家视角。</li>
 * </ol>
 * 缩短版（44 刻）只留满弓、追箭、莲华化雨和贯地，再擦镜。
 */
public final class PrincessUltimateShow implements UltimateShow {
    /** 薄荷绿、亮薄荷、暖金、深绿（画面上的线）。 */
    static final int MINT = 0x8CD9B0, LIGHT = 0xA8F0C6, GOLD = 0xF2D58A, INK = 0x0E1F17;

    final UltimatePayloads.Cast cast;
    final PrincessUltimatePlan.Timeline plan;
    /** 开演时定下的几何：她脚下、往目标的方向、右手边、贯地的中心、法阵中心、种子、搭箭的地方、主箭射向哪。 */
    final Vec3 feet, dir, side, center, apex, seed, nock, aim;
    final double height;
    /** 这一帧在第几刻（带帧内插值）。 */
    float age;
    private @Nullable UltimateFilm film;

    private PrincessUltimateShow(UltimatePayloads.Cast cast, Vec3 feet, Vec3 facing, double height) {
        this.cast = cast;
        this.plan = PrincessUltimatePlan.timeline(cast.condensed());
        this.feet = feet;
        this.center = cast.aim();
        this.dir = PrincessUltimatePlan.toward(feet, center, facing);
        this.side = PrincessUltimatePlan.side(dir);
        this.height = height;
        this.apex = PrincessUltimatePlan.apex(center, height);
        this.seed = PrincessUltimatePlan.seed(feet, dir);
        this.nock = PrincessUltimatePlan.nock(feet, dir);
        this.aim = apex.subtract(nock).normalize();
    }

    /** 她档案里的工厂（{@code CompanionStyle.PRINCESS}）。 */
    public static @Nullable UltimateShow begin(UltimatePayloads.Cast cast) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        Entity her = level.getEntity(cast.performer());
        Vec3 feet = her != null ? her.position() : cast.aim();
        Vec3 facing = her != null ? her.getLookAngle() : new Vec3(0, 0, 1);
        PrincessUltimateShow show = new PrincessUltimateShow(cast, feet, facing, PrincessUltimatePlan.height(level, cast.aim()));
        UltimateFilm film = show.plan.sprouts() ? show.full() : show.condensed();
        if (film.play()) show.film = film;
        PrincessUltimateFx.add(show);
        return show;
    }

    @Override
    public boolean tick(UltimatePayloads.Cast cast, int age) {
        return age < cast.length();
    }

    @Override
    public void frame(UltimatePayloads.Cast cast, float age) {
        this.age = age;
    }

    /** Cut 1 种子破土：四周彻底压黑，只在新苗那一点留一圈（{@link PrincessUltimateScreen}）。只有本机玩家的过场在放时画。 */
    @Override
    public void screen(UltimatePayloads.Cast cast, float age) {
        if (film == null || UltimateFilm.playing() != film || !plan.sprouts()) return;
        if (age >= MACRO && age < plan.grow()) PrincessUltimateScreen.spotlight(seed.add(0, .36, 0), 1);
    }

    @Override
    public void end(UltimatePayloads.Cast cast, boolean interrupted) {
        if (interrupted && film != null) film.stop();
        PrincessUltimateFx.remove(this);
    }

    /** 测试用：本机在放的过场（不是本机玩家下的令时是 null）。 */
    public @Nullable UltimateFilm film() {
        return film;
    }

    // ---------------------------------------------------------------- 分镜

    /** 闭幕压到全黑用几刻，之后硬切到 Cut 1 的微距。 */
    static final float MACRO = 3;

    private UltimateFilm full() {
        PrincessUltimatePlan.Timeline p = plan;
        return UltimateFilm.of(cast)
                // Cut 1：3 刻闭幕压到全黑，硬切到贴地微距（四周压黑由她自己的后期画，只留新苗那一点）
                .darken(0, MACRO, MACRO, 0, 1)
                .cut(MACRO, p.grow(), this::macro)
                // Cut 2：螺旋拉远，藤蔓化箭、搭上弓
                .cut(p.grow(), p.ring(), this::spiral)
                // Cut 3：低机位弧形摇镜、荷兰角，速度线往箭尖吸，最后几刻蓄力定格
                .cut(p.ring(), p.release(), this::arc)
                .freeze(p.release() - 3, p.release())
                .lines(p.full(), p.release(), 2, UltimateFilm.Lines.CONVERGE, .6f, 0, INK, .62f, .66f)
                // Cut 4：过肩一拍，贴着箭身急追
                .cut(p.release(), p.release() + 2, this::shoulder)
                .cut(p.release() + 2, p.apex(), this::chase)
                .blur(p.release() + 1, p.apex(), 1, UltimateFilm.Blur.RADIAL, .5f, 0, .55f, .55f)
                .lines(p.release() + 1, p.apex(), 1, UltimateFilm.Lines.RADIAL, .6f, 0, INK, .55f, .55f)
                // Cut 5：高空急拉大远景、往下摇
                .cut(p.apex(), p.rain(), this::sky)
                // Cut 6：跟着第一批光矢俯冲，贴地横甩
                .cut(p.rain(), p.rain() + 6, this::dive)
                .blur(p.rain(), p.rain() + 6, 1, UltimateFilm.Blur.DIRECTIONAL, .5f, 90, .5f, .5f)
                .cut(p.rain() + 6, p.plunge(), this::whip)
                .blur(p.rain() + 6, p.plunge(), 1, UltimateFilm.Blur.DIRECTIONAL, .75f, 0, .5f, .5f)
                // Cut 7：广角定机位，巨箭从画面上方垂直贯下，贯地那一拍强烈震屏
                .cut(p.plunge(), p.length(), this::wide)
                .shake(p.impact(), p.impact() + 11, 2, .35f)
                .lines(p.impact(), p.impact() + 7, 1, UltimateFilm.Lines.RADIAL, .9f, 0, INK, .5f, .4f)
                // Cut 8：叶片擦镜回到玩家视角
                .wipe(p.wipe(), p.length(), UltimateFilm.Wipe.LEAF, LIGHT, GOLD);
    }

    private UltimateFilm condensed() {
        PrincessUltimatePlan.Timeline p = plan;
        return UltimateFilm.of(cast)
                .cut(0, p.release(), this::arc)
                .lines(1, p.release(), 2, UltimateFilm.Lines.CONVERGE, .6f, 0, INK, .62f, .66f)
                .cut(p.release(), p.apex(), this::chase)
                .blur(p.release(), p.apex(), 1, UltimateFilm.Blur.RADIAL, .5f, 0, .55f, .55f)
                .lines(p.release(), p.apex(), 1, UltimateFilm.Lines.RADIAL, .6f, 0, INK, .55f, .55f)
                .cut(p.apex(), p.rain(), this::sky)
                .cut(p.rain(), p.plunge(), this::dive)
                .blur(p.rain(), p.plunge(), 1, UltimateFilm.Blur.DIRECTIONAL, .5f, 90, .5f, .5f)
                .cut(p.plunge(), p.length(), this::wide)
                .shake(p.impact(), p.impact() + 9, 2, .32f)
                .lines(p.impact(), p.impact() + 6, 1, UltimateFilm.Lines.RADIAL, .9f, 0, INK, .5f, .4f)
                .wipe(p.wipe(), p.length(), UltimateFilm.Wipe.LEAF, LIGHT, GOLD);
    }

    // ---------------------------------------------------------------- 每一镜的机位

    /** Cut 1：贴近地面的微距，微微俯看刚破土的新苗（背景是压黑的地面，不是亮的天空），极慢推进。 */
    private CameraDirector.Aim macro(UltimateFilm.Shot s) {
        Vec3 from = new Vec3(-dir.x + side.x * .6, 0, -dir.z + side.z * .6).normalize();
        float d = s.lerp(1.1f, .7f, s.t());
        return s.look(seed.add(from.scale(d)).add(0, s.lerp(.6f, .48f, s.t()), 0), seed.add(0, .3, 0), 0, 40);
    }

    /** Cut 2：绕着往上长的藤蔓转着拉远，最后从她左前方仰着看她搭箭（藤蔓在左下，她在右上）。 */
    private CameraDirector.Aim spiral(UltimateFilm.Shot s) {
        float t = s.t(), e = s.ease();
        float grow = Mth.clamp(t * 1.35f, 0, 1);
        Vec3 front = seed.lerp(nock, grow);
        double end = Math.atan2(-1.2, 2.8), start = end - Math.toRadians(200);
        double a = Mth.lerp(e, start, end);
        float r = s.lerp(.9f, 3.05f, e);
        Vec3 ring = dir.scale(Math.cos(a)).add(side.scale(Math.sin(a)));
        Vec3 base = new Vec3(Mth.lerp(e, front.x, feet.x), 0, Mth.lerp(e, front.z, feet.z));
        double y = Mth.lerp(e, seed.y + .15, feet.y + .85);
        Vec3 from = new Vec3(base.x, y, base.z).add(ring.scale(r));
        Vec3 look = front.lerp(nock.lerp(seed, .28).add(0, .1, 0), VfxParts.smooth(.45f, 1, t));
        return s.look(safe(feet.add(0, 1, 0), from), look, s.lerp(0, -6, e), s.lerp(48, 56, e));
    }

    /** Cut 3：低机位，从她右手边弧形摇到右前方，仰拍满弓，画面歪着；她在画面正中偏左，箭尖指向右上方。 */
    private CameraDirector.Aim arc(UltimateFilm.Shot s) {
        double a = Math.toRadians(s.lerp(98, 58));
        float d = s.lerp(2.9f, 2.4f);
        Vec3 ring = dir.scale(Math.cos(a)).add(side.scale(Math.sin(a)));
        Vec3 from = feet.add(ring.scale(d)).add(0, .3, 0);
        Vec3 look = feet.add(0, 1.3, 0).add(aim.scale(.2));
        return s.look(safe(feet.add(0, 1, 0), from), look, s.lerp(-8, -14), 54);
    }

    /** Cut 4 的第一拍：过肩，看着离弦的箭。 */
    private CameraDirector.Aim shoulder(UltimateFilm.Shot s) {
        Vec3 from = feet.add(0, 1.8, 0).subtract(dir.scale(1.9)).add(side.scale(.85));
        Vec3 arrow = arrow(s.age());
        return s.look(safe(feet.add(0, 1.5, 0), from), arrow == null ? apex : arrow.add(aim.scale(3)), 0, 66);
    }

    /** Cut 4：斜后方贴着箭身急追（不正对箭尾，箭在画面里斜着），广角。 */
    private CameraDirector.Aim chase(UltimateFilm.Shot s) {
        Vec3 arrow = arrow(s.age());
        if (arrow == null) arrow = nock;
        Vec3 h = PrincessUltimatePlan.heading(plan, s.age(), nock, apex);
        Vec3 across = h.cross(new Vec3(0, 1, 0));
        across = across.lengthSqr() < 1e-4 ? side : across.normalize();
        Vec3 up = across.cross(h).normalize();
        float len = PrincessUltimatePlan.length(plan, s.age());
        Vec3 from = arrow.subtract(h.scale(len * .5 + 1.1)).add(up.scale(.5 + len * .06)).add(across.scale(-.8 - len * .08));
        return s.look(from, arrow.add(h.scale(len * .5 + 2.5)), 0, 74);
    }

    /** Cut 5：从法阵斜上方急拉成大远景，往下摇到地面的敌群。 */
    private CameraDirector.Aim sky(UltimateFilm.Shot s) {
        float e = s.easeOut();
        Vec3 from = apex.add(side.scale(s.lerp(8, 17, e))).subtract(dir.scale(s.lerp(1.5f, 6, e))).add(0, s.lerp(3.2f, 6.5f, e), 0);
        Vec3 look = apex.lerp(center.add(0, height * .3, 0), s.ease() * .85f);
        return s.look(safe(apex, from), look, 0, s.lerp(58, 72, e));
    }

    /** Cut 6 的前半：跟着第一批光矢从法阵旁边俯冲到贴地。 */
    private CameraDirector.Aim dive(UltimateFilm.Shot s) {
        float e = s.easeIn();
        Vec3 top = apex.add(side.scale(PrincessUltimatePlan.LOTUS_RADIUS + 3)).add(0, 1.5, 0);
        Vec3 bottom = center.add(side.scale(6)).subtract(dir.scale(2)).add(0, 2.4, 0);
        Vec3 from = top.lerp(bottom, e);
        Vec3 look = PrincessUltimatePlan.slot(apex, cast.seed(), 0).lerp(center.add(0, .8, 0), Mth.clamp(e * 1.25f + .15f, 0, 1));
        return s.look(safe(center.add(0, 1.5, 0), from), look, s.lerp(0, 6, e), 66);
    }

    /** Cut 6 的后半：贴地横着甩过落点。 */
    private CameraDirector.Aim whip(UltimateFilm.Shot s) {
        Vec3 from = center.add(side.scale(6)).subtract(dir.scale(2)).add(0, 2.4, 0);
        Vec3 to = center.add(0, .8, 0).subtract(from);
        double yaw = Math.toRadians(s.lerp(-42, 40));
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        Vec3 swept = new Vec3(to.x * cos - to.z * sin, to.y, to.x * sin + to.z * cos);
        return s.look(safe(center.add(0, 1.5, 0), from), from.add(swept), s.lerp(6, -4), 66);
    }

    /** Cut 7：广角定机位看敌群中心，巨箭从画面上方垂直贯下。 */
    private CameraDirector.Aim wide(UltimateFilm.Shot s) {
        Vec3 from = center.add(side.scale(11)).subtract(dir.scale(4.5)).add(0, 3.2, 0);
        return s.look(safe(center.add(0, 2, 0), from), center.add(0, 3.2, 0).subtract(dir.scale(1)), 0, 72);
    }

    // ---------------------------------------------------------------- 工具

    /** 第 {@code age} 刻主箭在哪（还没放出去是 null）。 */
    @Nullable Vec3 arrow(float age) {
        return PrincessUltimatePlan.arrow(plan, age, nock, apex, center);
    }

    /** 机位别嵌进方块：从 {@code anchor} 往 {@code want} 看一眼，挡住了就停在挡住的地方前面一点。 */
    private static Vec3 safe(Vec3 anchor, Vec3 want) {
        var level = Minecraft.getInstance().level;
        if (level == null) return want;
        var hit = level.clip(new ClipContext(anchor, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return want;
        Vec3 back = want.subtract(anchor);
        return hit.getLocation().subtract(back.normalize().scale(.35));
    }

}
