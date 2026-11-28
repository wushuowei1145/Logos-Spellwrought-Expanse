package dev.logos.dimension.client.hero;

import static dev.logos.dimension.ultimate.HeroFinisherTimeline.APEX;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.CONVERGE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.FALL;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.HUSH;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.IGNITE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.IMPACT;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.LENGTH;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.LUNGE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.PILLAR;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.REVEAL;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.RISE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.THROW;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.WIPE;

import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.companion.CompanionStyle;
import dev.logos.dimension.client.ultimate.UltimateClient;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateShow;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.cutin.ChainCutins;
import dev.logos.dimension.ultimate.HeroFinisherTimeline;
import dev.logos.dimension.ultimate.UltimatePayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * 玩家空手最后一击「星穹誓约 · 羁绊焚天炮」在客户端的画面（docs/ULTIMATES.md 第六节，时间表 {@link HeroFinisherTimeline}）：
 * <ul>
 *   <li>下令的玩家：八镜的分镜运镜（{@link UltimateFilm}），起手的压黑和全黑里的那朵火苗（{@link HeroFinisherScreen}），
 *       最后星芒擦镜回到他自己的视角；</li>
 *   <li>所有看得见的人：掌心的火、交汇的金光、参战同伴的光流、魔弹、冲刺的拖光、落点的冲击环、通天火柱、云洞、漫天星火
 *       （{@link HeroFinisherVfx}），以及伸臂托火、举球、暴砸、收势的姿势（{@link HeroFinisherPose}）。</li>
 * </ul>
 * 光流、魔弹外环、火柱的螺旋按这一轮参战同伴的主色混色（{@link HeroFinisherTimeline#mix}），几位参战就混几色。
 */
public final class HeroFinisherShow implements UltimateShow {
    /** 正在放的：演的人的实体 ID → 画面（世界里的特效、姿势按它画）。 */
    static final Map<Integer, HeroFinisherShow> ACTIVE = new HashMap<>();

    final UltimatePayloads.Cast cast;
    private final @Nullable UltimateFilm film;
    /** 主角面朝哪（开演时朝着要砸的那一点，水平）、右手边。整段都按这一个方向摆，不跟着视线晃。 */
    final Vec3 fwd, right;
    /** 面朝的偏航角（度）。 */
    final float yaw;
    /** 参战同伴的主色、深一点的主色（0xRRGGBB）和她们的实体 ID（光流顺着她们的方向来）。 */
    final int[] accents, deep;
    final int[] members;
    /** 主角头顶有多高的空（格，最多 12）：矮的屋里、洞里魔弹按它缩小，不顶进天花板。 */
    final float headroom;
    private int ticks;
    /** 砸下去的那一点：砸下之前跟着目标，砸下那一刻定住。 */
    private @Nullable Vec3 burst;
    private HeroFinisherTimeline.@Nullable Far far;
    final HeroFinisherVfx vfx;

    private HeroFinisherShow(UltimatePayloads.Cast cast, Vec3 fwd, int[] accents, int[] deep, int[] members) {
        this.cast = cast;
        this.fwd = fwd;
        this.right = new Vec3(-fwd.z, 0, fwd.x);
        this.yaw = (float) Math.toDegrees(Math.atan2(-fwd.x, fwd.z));
        this.accents = accents;
        this.deep = deep;
        var level = Minecraft.getInstance().level;
        Entity hero = level == null ? null : level.getEntity(cast.performer());
        Vec3 head = (hero == null ? cast.aim() : hero.position()).add(0, 1.8, 0);
        this.headroom = (float) (1.8 + clear(level, head, new Vec3(0, 1, 0), 10.2));
        this.members = members;
        this.vfx = new HeroFinisherVfx(this);
        UltimateFilm f = build(UltimateFilm.of(cast));
        this.film = f.play() ? f : null;
    }

    /** {@link dev.logos.dimension.client.cutin.HeroCutin#PLAYER} 里空手那一条的工厂：看得见的人都开一个，只有下令的玩家放过场。 */
    public static @Nullable UltimateShow begin(UltimatePayloads.Cast cast) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        Entity hero = level.getEntity(cast.performer());
        Vec3 feet = hero == null ? cast.aim() : hero.position();
        Vec3 d = cast.aim().subtract(feet);
        d = new Vec3(d.x, 0, d.z);
        if (d.lengthSqr() < 1e-4) {
            double r = Math.toRadians(hero == null ? 0 : hero.getYRot());
            d = new Vec3(-Math.sin(r), 0, Math.cos(r));
        }
        List<Integer> ids = new ArrayList<>();
        List<Integer> colors = new ArrayList<>(), deep = new ArrayList<>();
        var player = Minecraft.getInstance().player;
        if (player != null && player.getId() == cast.owner()) {
            for (UltimatePayloads.Member m : UltimateClient.status().members()) {
                ids.add(m.entity());
                colors.add(CompanionStyle.of(m.id()).accent() & 0xFFFFFF);
                deep.add(CompanionStyle.of(m.id()).accentDeep() & 0xFFFFFF);
            }
        } else if (hero != null) {
            // 别的玩家拿不到主人的状态：认他身边的同伴（主人在客户端认不出来，按距离取最近的几位）
            List<CompanionEntity> near = level.getEntitiesOfClass(CompanionEntity.class, hero.getBoundingBox().inflate(ChainCutins.RANGE));
            near.sort((a, b) -> Double.compare(a.distanceToSqr(hero), b.distanceToSqr(hero)));
            for (CompanionEntity c : near.subList(0, Math.min(near.size(), cast.members()))) {
                ids.add(c.getId());
                colors.add(CompanionStyle.of(c.def().id()).accent() & 0xFFFFFF);
                deep.add(CompanionStyle.of(c.def().id()).accentDeep() & 0xFFFFFF);
            }
        }
        HeroFinisherShow show = new HeroFinisherShow(cast, d.normalize(), colors.stream().mapToInt(Integer::intValue).toArray(),
                deep.stream().mapToInt(Integer::intValue).toArray(), ids.stream().mapToInt(Integer::intValue).toArray());
        ACTIVE.put(cast.performer(), show);
        return show;
    }

    // ---------------------------------------------------------------- 时间

    @Override
    public boolean tick(UltimatePayloads.Cast cast, int age) {
        ticks = age;
        return age < cast.length();
    }

    @Override
    public void screen(UltimatePayloads.Cast cast, float age) {
        if (film != null && UltimateFilm.playing() == film) HeroFinisherScreen.draw(this, age(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)));
    }

    @Override
    public void end(UltimatePayloads.Cast cast, boolean interrupted) {
        ACTIVE.remove(cast.performer(), this);
        if (interrupted && film != null) film.stop();
    }

    /** 这一帧在第几刻：本机放着过场时跟镜头走（截帧时停住的那一刻也一样），别人按收到开演以后的客户端刻。 */
    float age(float partial) {
        if (film != null && UltimateFilm.playing() == film) return film.age(partial);
        return ticks + partial;
    }

    /** 本机在放这一段的过场。 */
    boolean filming() {
        return film != null && UltimateFilm.playing() == film;
    }

    /** 测试用。 */
    public @Nullable UltimateFilm film() {
        return film;
    }

    /** 测试用：Cut 6–7 的极远机位（还没到时是 null）。 */
    public HeroFinisherTimeline.@Nullable Far far() {
        return far;
    }

    /** 测试用：本机正在放的那一段（演的人是 {@code performer}）。 */
    public static @Nullable HeroFinisherShow of(int performer) {
        return ACTIVE.get(performer);
    }

    // ---------------------------------------------------------------- 锚点

    @Nullable Entity hero() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntity(cast.performer());
    }

    /** 主角脚下（看不到他时是放的那一点）。 */
    Vec3 feet(float partial) {
        Entity e = hero();
        return e == null ? cast.aim() : e.getPosition(partial);
    }

    /** 伸直的右手掌心上方一点（托着火的地方）。玩家模型缩到 15/16：肩高约 1.29，胳膊约 0.7。 */
    Vec3 palm(Vec3 feet) {
        return feet.add(0, 1.4, 0).add(fwd.scale(.78)).add(right.scale(.3));
    }

    Vec3 chest(Vec3 feet) {
        return feet.add(0, 1.1, 0);
    }

    /** 魔弹多大：时间表上的半径；头顶空不够时整体缩小（最小三成五），举在头顶时不顶进天花板。 */
    float radius(float age) {
        float room = Mth.clamp((headroom - 2) / 6.6f, .35f, 1);
        return HeroFinisherTimeline.orbRadius(age) * room;
    }

    /** 魔弹在哪：Cut 3 前在掌心；Cut 4 升到头顶前方；Cut 5 握在身前；最后几刻砸到落点。 */
    Vec3 orb(float age, Vec3 feet) {
        float r = radius(age);
        Vec3 palm = palm(feet);
        Vec3 apex = feet.add(fwd.scale(1.1)).add(0, 1.9 + r * .95, 0);
        Vec3 at = palm.lerp(apex, HeroFinisherTimeline.lift(age));
        if (age >= LUNGE) {
            Vec3 held = feet.add(fwd.scale(.5 + r)).add(0, 1.1 + r * .45, 0);
            at = apex.lerp(held, HeroFinisherTimeline.smooth(LUNGE, LUNGE + 5, age));
        }
        if (age >= THROW) at = at.lerp(burst(age, 0).add(0, r * .6, 0), HeroFinisherTimeline.thrown(age));
        return at;
    }

    /** 砸下去的那一点（地上）：砸下之前跟着目标，砸下那一刻定住。 */
    Vec3 burst(float age, float partial) {
        if (burst != null) return burst;
        var level = Minecraft.getInstance().level;
        Entity target = level == null || cast.target() < 0 ? null : level.getEntity(cast.target());
        Vec3 at = target != null && target.isAlive() ? target.getPosition(partial) : cast.aim();
        if (level != null) {
            var down = level.clip(new ClipContext(at.add(0, .5, 0), at.add(0, -4, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            if (down.getType() != HitResult.Type.MISS) at = down.getLocation();
        }
        if (age >= IMPACT) burst = at;
        return at;
    }

    /** Cut 6–7 的极远机位：第一次用到时量一次（之后地形不会变）。 */
    HeroFinisherTimeline.Far far(float age) {
        if (far != null) return far;
        Vec3 p = burst(age, 0);
        var mc = Minecraft.getInstance();
        Level level = mc.level;
        double limit = mc.options.getEffectiveRenderDistance() * 16 - 24;
        far = HeroFinisherTimeline.far(p, fwd, limit, (from, dir, max) -> clear(level, from, dir, max));
        return far;
    }

    private static double clear(@Nullable Level level, Vec3 from, Vec3 dir, double max) {
        if (level == null) return max;
        var hit = level.clip(new ClipContext(from, from.add(dir.scale(max)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? max : hit.getLocation().distanceTo(from);
    }

    /** 机位别塞进方块：从 {@code anchor} 往 {@code from} 看一眼，挡住了就停在挡住的地方前面。 */
    static Vec3 unblocked(Vec3 anchor, Vec3 from) {
        var level = Minecraft.getInstance().level;
        if (level == null) return from;
        var hit = level.clip(new ClipContext(anchor, from, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return from;
        Vec3 back = anchor.subtract(from);
        return hit.getLocation().add(back.normalize().scale(Math.min(.3, back.length() * .5)));
    }

    /** 以 {@code center} 为圆心、方位 {@code deg} 度（0 是主角前方，90 是右手边，-90 是左手边）、水平离 {@code dist}、高 {@code h} 的一点。 */
    Vec3 orbit(Vec3 center, float deg, float dist, float h) {
        double r = Math.toRadians(deg);
        return center.add(fwd.scale(Math.cos(r) * dist)).add(right.scale(Math.sin(r) * dist)).add(0, h, 0);
    }

    // ---------------------------------------------------------------- 分镜

    private UltimateFilm build(UltimateFilm f) {
        int wipe2 = HeroFinisherTimeline.lerpColor(HeroFinisherTimeline.blend(accents), HeroFinisherTimeline.WHITE, .4f);
        return f.cut(IGNITE, REVEAL, this::ignite)
                .cut(REVEAL, CONVERGE, this::reveal)
                .cut(CONVERGE, APEX, this::surge)
                .cut(APEX, LUNGE, this::apex)
                .freeze(HUSH, LUNGE)
                .cut(LUNGE, IMPACT, this::lunge)
                .cut(IMPACT, PILLAR, this::blast)
                .cut(PILLAR, FALL, this::pillar)
                .cut(FALL, LENGTH, this::fall)
                // Cut 3：光流往掌心灌，细细的白线往里吸
                .lines(CONVERGE + 2, APEX, 3, UltimateFilm.Lines.CONVERGE, .4f, 0, 0xFFFFFF)
                // Cut 4：速度线骤然往魔弹中心猛吸，屏息那几刻停住
                .lines(APEX, HUSH, 2, UltimateFilm.Lines.CONVERGE, .9f, 0, 0x120C08, .5f, .62f)
                .grade(HUSH, LUNGE, 1, .22f, .45f, 0xFFE2A8, .08f, .55f)
                // Cut 5：斜着冲下去，粗黑的速度线和方向模糊
                .blur(LUNGE + 1, IMPACT, 2, UltimateFilm.Blur.DIRECTIONAL, .55f, 32, .5f, .5f)
                .lines(LUNGE, IMPACT, 2, UltimateFilm.Lines.DIRECTIONAL, .85f, 32, 0x0A0806)
                // 砸下的震动、火柱冲起的余震
                .shake(IMPACT, IMPACT + 9, 3, .8f)
                .shake(RISE, RISE + 12, 4, .45f)
                .wipe(WIPE, LENGTH, UltimateFilm.Wipe.STAR, HeroFinisherTimeline.GOLD, wipe2)
                .returning(6);
    }

    /** Cut 1 暗中燃火：贴近掌心极缓推进（画面全黑，只看得到那朵火）。 */
    private CameraDirector.Aim ignite(UltimateFilm.Shot s) {
        Vec3 p = palm(feet(partial(s)));
        Vec3 from = p.subtract(right.scale(s.lerp(2.1f, 1.45f))).add(fwd.scale(.25)).add(0, .08, 0);
        return s.look(unblocked(p, from), p, 0, 50);
    }

    /** Cut 2 渐亮 · 伸臂托火：侧身剪影，镜头平稳后拉；略仰，背后是天。主角在画面右边、面朝左，火在左边。 */
    private CameraDirector.Aim reveal(UltimateFilm.Shot s) {
        Vec3 feet = feet(partial(s));
        Vec3 center = feet.add(fwd.scale(.35)).add(0, 1.4, 0);
        float k = s.ease();
        Vec3 from = center.subtract(right.scale(Mth.lerp(k, 1.9f, 4.3f))).add(fwd.scale(Mth.lerp(k, .2f, .8f))).add(0, Mth.lerp(k, -.05f, -.5f), 0);
        return s.look(unblocked(center, from), center.add(0, .25 * k, 0), 0, s.lerp(50, 46));
    }

    /** Cut 3 万流归宗：绕着主角体侧甩过去、急速后撤，容下越涨越大的光流。 */
    private CameraDirector.Aim surge(UltimateFilm.Shot s) {
        float age = s.age();
        Vec3 feet = feet(partial(s));
        Vec3 c = orb(age, feet).scale(.6).add(chest(feet).scale(.4));
        float k = s.easeOut();
        Vec3 from = orbit(c, Mth.lerp(k, -95, -32), Mth.lerp(k, 4, 9.5f), Mth.lerp(k, .1f, 1.6f));
        return s.look(unblocked(c, from), c, Mth.lerp(k, -4, 3), Mth.lerp(k, 56, 72));
    }

    /** Cut 4 魔弹成型 · 屏息定格：贴地低仰的广角，微微推近；主角在下面，魔弹压满画面上方。 */
    private CameraDirector.Aim apex(UltimateFilm.Shot s) {
        float age = s.age();
        Vec3 feet = feet(partial(s));
        Vec3 orb = orb(age, feet);
        Vec3 look = chest(feet).lerp(orb, .72);
        Vec3 from = orbit(feet, -30, s.lerp(10, 8.6f), .35f);
        return s.look(unblocked(look, from), look, 0, s.lerp(88, 80));
    }

    /** Cut 5 倾力暴砸：贴在主角斜后上方高速俯冲，画面歪着，越冲越广。 */
    private CameraDirector.Aim lunge(UltimateFilm.Shot s) {
        float age = s.age();
        Vec3 feet = feet(partial(s));
        Vec3 orb = orb(age, feet);
        Vec3 p = burst(age, partial(s)).add(0, 1, 0);
        Vec3 from = feet.subtract(fwd.scale(3.4)).subtract(right.scale(1.3)).add(0, 3.1, 0);
        Vec3 look = orb.lerp(p, .3 + .45 * s.easeIn());
        return s.look(unblocked(chest(feet), from), look, s.lerp(-9, -15), Mth.lerp(s.easeIn(), 76, 98));
    }

    /** Cut 6 极远超广角：硬切到爆发点斜上方几十格外的大远景（洞里时退到洞内最远处），满规格黑白闪在服务端放。 */
    private CameraDirector.Aim blast(UltimateFilm.Shot s) {
        HeroFinisherTimeline.Far f = far(s.age());
        return s.look(f.from(), f.look(), 0, 82);
    }

    /** Cut 7 巨型火柱直冲天际：同一个远处的机位慢慢仰起来，从地面看到火柱捅开的云洞。 */
    private CameraDirector.Aim pillar(UltimateFilm.Shot s) {
        HeroFinisherTimeline.Far f = far(s.age());
        Vec3 p = burst(s.age(), 0);
        float k = s.ease();
        Vec3 top = f.cave() ? f.look().add(0, 1.5, 0) : p.add(0, HeroFinisherVfx.HEIGHT * .62, 0);
        Vec3 from = f.from().lerp(p.add(0, f.from().y - p.y, 0), .06 * k);
        return s.look(from, f.look().lerp(top, k), 0, s.lerp(82, 86));
    }

    /** Cut 8 星火归位：从远处高空平滑飞回战场正中，落到主角斜前方；最后星芒擦镜回到玩家视角。 */
    private CameraDirector.Aim fall(UltimateFilm.Shot s) {
        float age = s.age();
        HeroFinisherTimeline.Far f = far(age);
        Vec3 feet = feet(partial(s));
        Vec3 p = burst(age, 0);
        float k = HeroFinisherTimeline.smooth(FALL, WIPE + 4, age);
        Vec3 near = orbit(feet, -38, 3.8f, 1.5f);
        Vec3 high = f.from().lerp(near, .5).add(0, f.cave() ? 0 : 14, 0);
        // 二次贝塞尔：从远处高空弧线落下来
        Vec3 from = f.from().scale((1 - k) * (1 - k)).add(high.scale(2 * k * (1 - k))).add(near.scale(k * k));
        Vec3 look = (f.cave() ? f.look() : p.add(0, 30, 0)).lerp(feet.add(0, 1.2, 0), k);
        if (k > .85f) from = unblocked(chest(feet), from);
        return s.look(from, look, 0, Mth.lerp(k, 84, 60));
    }

    private static float partial(UltimateFilm.Shot s) {
        return s.age() - Mth.floor(s.age());
    }
}
