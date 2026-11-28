package dev.logos.dimension.client.companion;

import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateShow;
import dev.logos.dimension.companion.KohakuUltimate;
import dev.logos.dimension.ultimate.UltimatePayloads;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀奥义的画面（docs/KOHAKU_COMPANION.md 第 20 节；分镜稿 {@code kohaku_ult_storyboard.md}）。节拍和服务端脚本共用 {@link KohakuUltimate.Beats}。
 *
 * <p>完整版的八镜：
 * <ol>
 *   <li>举杖吟唱（0–14）：缓慢推近到半身，压暗、去饱和、往暖琥珀偏，四角压出暗角；</li>
 *   <li>敌群凝时（14–27）：硬切到扑上来的敌群，镜头定格；反色一下（开了「隐藏闪电的闪烁」时不反）、RGB 色散、同心波纹，抓下这一帧；</li>
 *   <li>画面坍缩（27–43）：那一帧沿漩涡抽缩进一颗悬在黑暗里的琥珀晶石；</li>
 *   <li>掌中琥珀（43–55）：急拉开，晶石悬在她手边，脚下转着她的炼成阵；</li>
 *   <li>指尖轻弹（55–65）：极近的特写，第 58 刻一碰，四芒星一闪，一道裂纹贯穿晶石；</li>
 *   <li>全屏碎镜（65–75）：裂纹放大成盖满全屏的 Voronoi 碎玻璃，每一块折射得不一样，镜头微微地抖；</li>
 *   <li>星辰花爆破（75–91）：出手。碎片朝镜头四面八方飞散，阵心开出星辰花；冲击帧（服务端）、径向模糊、往外喷的速度线、强震，镜头往后推开；</li>
 *   <li>碎晶擦镜（91–106）：她拄着杖，大阵还在地上压着敌群，回流的金色缎带流向主人；一大片琥珀碎晶扫过去，回到玩家视角。</li>
 * </ol>
 * 缩短版（42 刻）没有吟唱和长的坍缩：凝时一瞬（0–6）→ 坍缩（6–10）→ 掌中琥珀 → 轻弹 → 碎镜 → 爆破（26）→ 擦镜。
 *
 * <p>全屏后期（凝时、坍缩、晶石、碎镜）是她自己的通道（{@link KohakuUltimateScreen}），只在本机玩家下的令时画；世界里的阵、星辰花、缎带、
 * 晶壳所有看得见她的人都画（{@link KohakuUltimateFx}）。
 */
public final class KohakuUltimateShow implements UltimateShow {
    /** 她档案里放的工厂（{@code CompanionStyle.KOHAKU}）。 */
    public static final UltimateShow.Factory FACTORY = KohakuUltimateShow::begin;

    private final KohakuUltimate.Beats beats;
    private final @Nullable UltimateFilm film;
    private boolean captured;
    /** 这一段的后期画了几帧（客户端场景检查）。 */
    private int drawn;

    private KohakuUltimateShow(KohakuUltimate.Beats beats, @Nullable UltimateFilm film) {
        this.beats = beats;
        this.film = film;
    }

    private static UltimateShow begin(UltimatePayloads.Cast cast) {
        KohakuUltimate.Beats b = cast.condensed() ? KohakuUltimate.CONDENSED : KohakuUltimate.FULL;
        face(cast);
        KohakuUltimateFx.start(cast, b);
        UltimateFilm film = film(cast, b);
        return new KohakuUltimateShow(b, film.play() ? film : null);
    }

    /** 分镜运镜和盖在画面上的一层。 */
    static UltimateFilm film(UltimatePayloads.Cast cast, KohakuUltimate.Beats b) {
        int amber = KohakuUltimateFx.AMBER;
        UltimateFilm f = UltimateFilm.of(cast);
        if (b.chant()) {
            // 举杖吟唱：缓慢推近到半身
            f.cut(0, b.stop(), s -> s.look(orbit(s, s.chest(), s.lerp(30, 14), s.lerp(3.6f, 2.0f), s.lerp(.35f, .12f)), s.chest().add(0, .3, 0),
                    s.lerp(-2, 1), s.lerp(52, 46)));
            f.grade(0, b.collapse(), 4, .32f, .45f, amber, .3f, .8f);
        } else {
            f.grade(0, b.impact(), 2, .25f, .38f, amber, .28f, .7f);
        }
        // 敌群凝时：硬切、定格（坍缩那一镜被后期整个盖住，镜头停在这里不动）
        f.cut(b.stop(), b.palm(), s -> {
            // 站在她和敌群之间、离敌群近一点（她离得近时不要退到她身上去）
            Vec3 at = s.targetChest(), d = s.target().subtract(s.feet());
            float back = (float) Math.clamp(Math.sqrt(d.x * d.x + d.z * d.z) * .55, 1.8, 3.4);
            Vec3 from = at.subtract(s.toward().scale(back)).add(side(s).scale(1.1)).add(0, -.2, 0);
            return s.look(from, at, 4, 68);
        });
        f.freeze(b.stop(), b.palm());
        // 掌中琥珀：急拉开，前三刻拉到位就定住
        f.cut(b.palm(), b.flick(), s -> {
            float k = 1 - (float) Math.pow(1 - Math.min(1, s.local() / 3f), 3);
            Vec3 from = orbit(s, s.feet(), Mth.lerp(k, 14, 30), Mth.lerp(k, 1.2f, 3.3f), Mth.lerp(k, 1.25f, 1.35f));
            return s.look(from, s.chest().add(0, .3, 0), 0, 56);
        });
        // 指尖轻弹：极近地切到晶石跟前，再推近一点
        f.cut(b.flick(), b.shatter(), s -> {
            // 从她右前方斜着看过去：背景是她身后的场地，不是站在她身后的主人
            Vec3 g = KohakuUltimateFx.gem(s.feet(), s.toward(), side(s), height(s.performer()));
            return s.look(orbit(s, g, 48, s.lerp(1.7f, 1.35f), .08f), g, s.lerp(4, -2), 40);
        });
        // 碎镜（被后期盖住）和爆破：从侧面斜上方看着阵心，往后推开；她在画面一边
        f.cut(b.shatter(), b.wipe(), s -> {
            float k = Mth.clamp((s.age() - b.impact()) / (b.wipe() - b.impact()), 0, 1);
            k = 1 - (1 - k) * (1 - k);
            Vec3 from = s.target().add(side(s).scale(Mth.lerp(k, 6, 9.5f))).subtract(s.toward().scale(Mth.lerp(k, 2, 3)))
                    .add(0, Mth.lerp(k, 3, 4), 0);
            return s.look(from, s.target().add(0, .8, 0), Mth.lerp(k, -6, 0), Mth.lerp(k, 74, 66));
        });
        // 擦镜前：她拄着杖，大阵还压着敌群
        f.cut(b.wipe(), b.length(), s -> s.look(orbit(s, s.feet(), s.lerp(-52, -44), 3.4f, 1.25f), s.chest().lerp(s.target().add(0, .8, 0), .3), 0, 62));
        f.grade(b.palm(), b.impact(), 2, .1f, .2f, amber, .15f, .55f);
        f.shake(b.shatter(), b.impact(), 1, .04f);
        int blast = b.chant() ? 1 : 0;
        f.blur(b.impact(), b.impact() + 5 + 2 * blast, 1 + blast, UltimateFilm.Blur.RADIAL, .85f, 0, .5f, .55f);
        f.lines(b.impact(), b.impact() + 8 + 4 * blast, 2 + blast, UltimateFilm.Lines.RADIAL, .72f, 0, 0x20140C);
        f.shake(b.impact(), b.impact() + 7 + 2 * blast, 2 + blast, b.chant() ? .32f : .28f);
        f.wipe(b.length() - (b.chant() ? 12 : 11), b.length(), UltimateFilm.Wipe.SHARD, amber, KohakuUltimateFx.LIGHT);
        return f;
    }

    private static float height(@Nullable Entity e) {
        return e == null ? 1.6f : e.getBbHeight();
    }

    /**
     * 同 {@code Shot#around}，但「正前方」取她朝着目标的方向（{@code toward}），不取她身体的朝向：
     * 客户端的身体朝向要过好几刻才转过来，按它摆镜头，开头几刻会拍到她的背。
     */
    static Vec3 orbit(UltimateFilm.Shot s, Vec3 center, float yaw, float distance, float height) {
        double r = Math.toRadians(yaw);
        Vec3 f = s.toward(), side = side(s);
        return center.add(f.scale(Math.cos(r) * distance)).add(side.scale(Math.sin(r) * distance)).add(0, height, 0);
    }

    /** 她朝着目标时的右手边。 */
    static Vec3 side(UltimateFilm.Shot s) {
        Vec3 f = s.toward();
        return new Vec3(-f.z, 0, f.x);
    }

    public KohakuUltimate.Beats beats() {
        return beats;
    }

    /** 这一段的后期画了几帧（测试用）。 */
    public int drawn() {
        return drawn;
    }

    /** 测试用。 */
    public @Nullable UltimateFilm film() {
        return film;
    }

    @Override
    public boolean tick(UltimatePayloads.Cast cast, int age) {
        face(cast);
        return age < cast.length();
    }

    /**
     * 客户端也让她一下子面朝目标：服务端转过去了，客户端的身体朝向却要被头拖着慢慢转（站着不动时身体只跟到离头 75° 以内），
     * 缩短版一开演就是特写，会拍到她的背。所有看得见她的客户端都这样摆，大家看到的一样。
     */
    private static void face(UltimatePayloads.Cast cast) {
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(cast.performer()) instanceof LivingEntity e)) return;
        Vec3 d = cast.aim().subtract(e.position());
        if (d.x * d.x + d.z * d.z < 1e-4) return;
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90;
        e.setYRot(yaw);
        e.yRotO = yaw;
        e.setYBodyRot(yaw);
        e.yBodyRotO = yaw;
        e.setYHeadRot(yaw);
        e.yHeadRotO = yaw;
    }

    @Override
    public void screen(UltimatePayloads.Cast cast, float age) {
        if (film == null || UltimateFilm.playing() != film) return;
        Minecraft mc = Minecraft.getInstance();
        if (!captured && age >= beats.capture()) {
            KohakuUltimateScreen.capture();
            captured = true;
        }
        KohakuUltimateLook look = KohakuUltimateLook.at(beats, age, mc.options.hideLightningFlash().get());
        if (!look.active()) return;
        if ((look.frozen() || look.collapse() >= 0 || look.gem() > 0 || look.shatter() > 0) && !captured) {
            KohakuUltimateScreen.capture();
            captured = true;
        }
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var level = mc.level;
        Entity performer = level == null ? null : level.getEntity(cast.performer());
        Entity target = level == null || cast.target() < 0 ? null : level.getEntity(cast.target());
        Vec3 focus = target != null ? target.getPosition(partial).add(0, target.getBbHeight() * .6, 0) : cast.aim().add(0, 1, 0);
        float[] ripple = screen(mc, focus);
        KohakuUltimateScreen.Gem gem = KohakuUltimateScreen.Gem.CENTER;
        if (!look.centered() && performer != null) {
            Vec3 g = KohakuUltimateFx.gem(performer, cast.aim(), partial);
            float[] at = screen(mc, g), top = screen(mc, g.add(0, KohakuUltimateFx.GEM, 0));
            float radius = at == null || top == null ? 0 : Math.abs(top[1] - at[1]);
            gem = at == null ? new KohakuUltimateScreen.Gem(.5f, .5f, 0, 0) : new KohakuUltimateScreen.Gem(at[0], at[1], radius, Mth.sin(age * .15f) * .08f);
        }
        KohakuUltimateScreen.draw(look, age, cast.seed(), ripple == null ? .5f : ripple[0], ripple == null ? .5f : ripple[1], gem, KohakuUltimateFx.AMBER);
        drawn++;
    }

    /** 世界里一点投到画面上（0–1，左下是 0）；在镜头背后时是 null。 */
    private static float @Nullable [] screen(Minecraft mc, Vec3 at) {
        Camera camera = mc.gameRenderer.getMainCamera();
        var forward = camera.forwardVector();
        if (at.subtract(camera.position()).dot(new Vec3(forward.x(), forward.y(), forward.z())) <= .05) return null;
        Vec3 ndc = mc.gameRenderer.projectPointToScreen(at);
        return new float[]{(float) (ndc.x * .5 + .5), (float) (ndc.y * .5 + .5)};
    }

    @Override
    public void end(UltimatePayloads.Cast cast, boolean interrupted) {
        if (!interrupted) return;
        if (film != null) film.stop();
        KohakuUltimateFx.stop(cast.performer());
    }

    /** 本机正在放的过场是不是她的（测试用）。 */
    public static boolean playing() {
        UltimateFilm f = UltimateFilm.playing();
        return f != null && f.cast().key().equals("kohaku") && CameraDirector.active();
    }
}
