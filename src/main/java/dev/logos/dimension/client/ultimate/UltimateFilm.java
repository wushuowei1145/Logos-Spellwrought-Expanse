package dev.logos.dimension.client.ultimate;

import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.ultimate.UltimatePayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 一段奥义的过场（docs/ULTIMATES.md「过场」）：分镜表（每一镜从第几刻到第几刻、镜头怎么走）加上盖在画面上的几层
 * （压暗和调色、动态模糊、速度线、擦镜），在她的 {@link UltimateShow} 里建好、{@link #play} 出来。
 *
 * <p>只有下令的那个玩家的镜头被接管、看得到叠层；别的玩家看的是世界里的那一半（动作、粒子、Logos 的特效）。
 * 时刻都按这段奥义开演以后的游戏刻（带帧内插值），和服务端脚本、{@link UltimatePayloads.Cast#impact()} 对齐。
 *
 * <pre>{@code
 * UltimateFilm.of(cast)
 *         .darken(0, 13, 4, 2, .85f)                                  // 压黑起手
 *         .cut(0, 13, s -> s.look(s.around(s.feet(), 20, 1.4f, .2f), s.chest()))
 *         .cut(13, 28, s -> s.look(s.around(s.target(), s.lerp(-60, 30, s.ease()), 6, 2), s.targetChest(), 0, 50))
 *         .lines(28, 41, 2, UltimateFilm.Lines.CONVERGE, .8f, 0, 0xFFFFFF)
 *         .wipe(91, 104, UltimateFilm.Wipe.PETAL, 0xA8F0C6, 0xF2D58A)
 *         .play();
 * }</pre>
 */
public final class UltimateFilm implements CameraDirector.Rig {
    /** 一镜：每帧按这一镜的进度给机位（{@link Shot#look}）。 */
    @FunctionalInterface
    public interface Move {
        CameraDirector.Aim aim(Shot shot);
    }

    /** 动态模糊：沿一个方向（横扫、下坠），或者从焦点往外（冲刺、爆炸）。 */
    public enum Blur { DIRECTIONAL, RADIAL }

    /** 漫画速度线：平行的一排（沿某个方向）、从焦点往外喷、往焦点里吸。中间留一块不盖住主体。 */
    public enum Lines { DIRECTIONAL, RADIAL, CONVERGE }

    /** 擦镜用什么：花瓣、叶子、纸片（符纸、形代）、碎晶、烟、火星、星芒。序号给着色器用，顺序不要改。 */
    public enum Wipe { PETAL, LEAF, PAPER, SHARD, SMOKE, SPARK, STAR }

    private record Cut(float start, float end, Move move) {}

    /** 盖在画面上的一层：{@code start} 到 {@code end}，头尾各淡入淡出几刻，中间强度 {@code peak}。其余字段由那一种层解释。 */
    record Layer(float start, float end, float fadeIn, float fadeOut, float peak, int mode, float a, float b, float c, float d, int color, int color2) {
        float weight(float age) {
            if (age < start || age >= end) return 0;
            float in = fadeIn <= 0 ? 1 : Math.min(1, (age - start) / fadeIn);
            float out = fadeOut <= 0 ? 1 : Math.min(1, (end - age) / fadeOut);
            return peak * Math.min(in, out);
        }

        /** 这一层走到几成（擦镜的进度）。 */
        float progress(float age) {
            return Mth.clamp((age - start) / Math.max(1e-3f, end - start), 0, 1);
        }
    }

    /** 这一帧要盖在画面上的东西（{@link UltimateFilmRenderer} 按它写着色器的参数）。 */
    record Overlay(@Nullable Layer grade, float gradeWeight, @Nullable Layer blur, float blurWeight, @Nullable Layer lines, float linesWeight,
                   @Nullable Layer wipe, float wipeProgress) {}

    /** 本机正在放的那一段。 */
    private static @Nullable UltimateFilm playing;

    private final UltimatePayloads.Cast cast;
    private final List<Cut> cuts = new ArrayList<>();
    private final List<Layer> grades = new ArrayList<>(), blurs = new ArrayList<>(), lines = new ArrayList<>(), wipes = new ArrayList<>(),
            shakes = new ArrayList<>(), freezes = new ArrayList<>();
    private int returnTicks = 6;
    private boolean letterbox;

    private UltimateFilm(UltimatePayloads.Cast cast) {
        this.cast = cast;
    }

    public static UltimateFilm of(UltimatePayloads.Cast cast) {
        return new UltimateFilm(cast);
    }

    public UltimatePayloads.Cast cast() {
        return cast;
    }

    // ---------------------------------------------------------------- 分镜

    /** 从第 {@code start} 刻到第 {@code end} 刻是这一镜（硬切进来）。镜与镜之间的空档是玩家自己的视角。 */
    public UltimateFilm cut(float start, float end, Move move) {
        cuts.add(new Cut(start, end, move));
        return this;
    }

    /** 这几刻里镜头停在 {@code start} 那一帧不动（卡肉顿帧、滞空定格）：机位定住，世界里的东西照常走。 */
    public UltimateFilm freeze(float start, float end) {
        freezes.add(new Layer(start, end, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0));
        return this;
    }

    /** 这几刻里镜头抖：{@code amplitude} 是最大偏移（格），头尾各淡 {@code fade} 刻。 */
    public UltimateFilm shake(float start, float end, float fade, float amplitude) {
        shakes.add(new Layer(start, end, fade, fade, amplitude, 0, 0, 0, 0, 0, 0, 0));
        return this;
    }

    /** 回到玩家视角用几刻（默认 6，藏在擦镜底下）。 */
    public UltimateFilm returning(int ticks) {
        returnTicks = Math.max(1, ticks);
        return this;
    }

    /** 上下加遮幅（宽银幕）。 */
    public UltimateFilm letterbox() {
        letterbox = true;
        return this;
    }

    // ---------------------------------------------------------------- 叠层

    /**
     * 调色：压暗 {@code dark}（1 是全黑）、去饱和 {@code desaturate}、往 {@code tint} 染 {@code tintAmount} 成、暗角 {@code vignette}。
     * 头尾各淡 {@code fade} 刻。同一刻有几层时取后加的那层。
     */
    public UltimateFilm grade(float start, float end, float fade, float dark, float desaturate, int tint, float tintAmount, float vignette) {
        grades.add(new Layer(start, end, fade, fade, 1, 0, dark, desaturate, tintAmount, vignette, tint, 0));
        return this;
    }

    /** 只压暗（起手的黑场、收拢）：淡入 {@code fadeIn}、淡出 {@code fadeOut} 刻。 */
    public UltimateFilm darken(float start, float end, float fadeIn, float fadeOut, float amount) {
        grades.add(new Layer(start, end, fadeIn, fadeOut, 1, 0, amount, 0, 0, .3f * amount, 0xFFFFFF, 0));
        return this;
    }

    /** 动态模糊：{@code strength} 0–1；方向模糊沿 {@code angle} 度（0 向右、90 向下），径向模糊从画面上 {@code (cx, cy)}（0–1）往外。 */
    public UltimateFilm blur(float start, float end, float fade, Blur mode, float strength, float angle, float cx, float cy) {
        blurs.add(new Layer(start, end, fade, fade, strength, mode.ordinal() + 1, (float) Math.toRadians(angle), cx, cy, 0, 0, 0));
        return this;
    }

    /** 速度线：{@code strength} 0–1，{@code angle} 度（平行线的走向），径向的焦点在画面正中。{@code color} 是线的颜色（漫画是黑或白）。 */
    public UltimateFilm lines(float start, float end, float fade, Lines mode, float strength, float angle, int color) {
        lines.add(new Layer(start, end, fade, fade, strength, mode.ordinal() + 1, (float) Math.toRadians(angle), .5f, .5f, 0, color, 0));
        return this;
    }

    /** 同上，径向的焦点在画面上 {@code (cx, cy)}（0–1）。 */
    public UltimateFilm lines(float start, float end, float fade, Lines mode, float strength, float angle, int color, float cx, float cy) {
        lines.add(new Layer(start, end, fade, fade, strength, mode.ordinal() + 1, (float) Math.toRadians(angle), cx, cy, 0, color, 0));
        return this;
    }

    /**
     * 擦镜：一大片 {@code kind} 从左往右扫过整个画面，正中盖满的那几刻里镜头回到玩家视角（{@link #returning}）。
     * 擦镜一般就是最后一镜：{@code end} 对齐这段奥义的长度。颜色在两个之间随机。
     */
    public UltimateFilm wipe(float start, float end, Wipe kind, int color, int color2) {
        wipes.add(new Layer(start, end, 0, 0, 1, kind.ordinal(), 0, 0, 0, 0, color, color2));
        return this;
    }

    // ---------------------------------------------------------------- 放

    /** 这一段是不是本机玩家下的令（只有他的镜头被接管）。 */
    public boolean mine() {
        var player = Minecraft.getInstance().player;
        return player != null && player.getId() == cast.owner();
    }

    /**
     * 放出来：这一段有过场（{@link UltimatePayloads.Cast#filmed}）、又是本机玩家下的令，才接管镜头、加叠层，别的时候什么都不做。返回放没放。
     * 没放时 {@link #playing()} 不是它，她的全屏后期也就不画。
     */
    public boolean play() {
        Minecraft mc = Minecraft.getInstance();
        if (!cast.filmed() || !mine() || mc.player == null) return false;
        int length = Math.max(1, cast.length());
        int back = Math.min(returnTicks, length);
        Vec3 eye = mc.player.getEyePosition();
        CameraShot shot = CameraShot.of(CameraPoint.world(eye), CameraPoint.world(eye.add(mc.player.getViewVector(1))))
                .timing(0, length - back, back);
        if (letterbox) shot = shot.letterbox();
        playing = this;
        CameraDirector.play(shot, this);
        return true;
    }

    /** 提前收起（被打断）。 */
    public void stop() {
        if (playing != this) return;
        playing = null;
        if (CameraDirector.rig() == this) CameraDirector.returnNow();
    }

    /** 本机正在放的那一段（镜头还在它手里）；放完、被别的镜头顶掉时是 null。 */
    public static @Nullable UltimateFilm playing() {
        if (playing != null && CameraDirector.rig() != playing) playing = null;
        return playing;
    }

    /** 这一帧在第几刻（带帧内插值）。 */
    public float age(float partialTick) {
        int age = CameraDirector.age();
        return age < 0 ? cast.length() : age + partialTick;
    }

    // ---------------------------------------------------------------- 每帧

    @Override
    public CameraDirector.@Nullable Aim aim(float age, float partialTick) {
        float shotAge = age;
        for (Layer f : freezes) if (age >= f.start && age < f.end) shotAge = f.start;
        Cut cut = null;
        for (Cut c : cuts) if (shotAge >= c.start && shotAge < c.end) cut = c;
        CameraDirector.Aim aim = cut == null ? eyes(partialTick) : cut.move.aim(new Shot(this, cut, shotAge, partialTick));
        if (aim == null) return null;
        float shake = 0;
        for (Layer s : shakes) shake = Math.max(shake, s.weight(age));
        if (shake <= 0) return aim;
        long seed = cast.seed();
        Vec3 jolt = new Vec3(noise(age * 1.9f, seed), noise(age * 2.3f, seed + 17), noise(age * 2.1f, seed + 31)).scale(shake);
        return new CameraDirector.Aim(aim.from().add(jolt), aim.look().add(jolt.scale(.4)), aim.roll() + noise(age * 1.7f, seed + 47) * shake * 6,
                aim.fov());
    }

    /** 玩家自己的视角（镜与镜之间的空档）。 */
    private static CameraDirector.@Nullable Aim eyes(float partialTick) {
        Player p = Minecraft.getInstance().player;
        if (p == null) return null;
        Vec3 eye = p.getEyePosition(partialTick);
        return new CameraDirector.Aim(eye, eye.add(p.getViewVector(partialTick)), 0);
    }

    /** 平滑的 -1..1 噪声（抖动）。 */
    private static float noise(float x, long seed) {
        int i = Mth.floor(x);
        float f = x - i, u = f * f * (3 - 2 * f);
        return Mth.lerp(u, hash(i, seed), hash(i + 1, seed));
    }

    private static float hash(int i, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFF) / 32767.5f - 1;
    }

    /** 这一帧盖在画面上的东西。 */
    Overlay overlay(float age) {
        Layer grade = top(grades, age), blur = top(blurs, age), line = top(lines, age), wipe = top(wipes, age);
        return new Overlay(grade, grade == null ? 0 : grade.weight(age), blur, blur == null ? 0 : blur.weight(age), line,
                line == null ? 0 : line.weight(age), wipe, wipe == null ? -1 : wipe.progress(age));
    }

    /** 这一刻起作用的那一层（几层叠在一起时取后加的）。 */
    private static @Nullable Layer top(List<Layer> layers, float age) {
        Layer best = null;
        for (Layer l : layers) if (age >= l.start && age < l.end) best = l;
        return best;
    }

    // ---------------------------------------------------------------- 一镜里能用的

    /** 一镜里每帧拿到的：这一镜走到哪了、演的人和目标在哪，以及搭机位用的小工具。 */
    public static final class Shot {
        private final UltimateFilm film;
        private final Cut cut;
        private final float age, partial;

        Shot(UltimateFilm film, Cut cut, float age, float partial) {
            this.film = film;
            this.cut = cut;
            this.age = age;
            this.partial = partial;
        }

        /** 这段奥义开演以后第几刻（带帧内插值）。 */
        public float age() {
            return age;
        }

        /** 这一镜开始以后第几刻。 */
        public float local() {
            return age - cut.start;
        }

        /** 这一镜走到几成（0–1）。 */
        public float t() {
            return Mth.clamp((age - cut.start) / Math.max(1e-3f, cut.end - cut.start), 0, 1);
        }

        /** 两头慢、中间快。 */
        public float ease() {
            float t = t();
            return t * t * (3 - 2 * t);
        }

        /** 越走越快（冲进去）。 */
        public float easeIn() {
            float t = t();
            return t * t;
        }

        /** 越走越慢（刹住）。 */
        public float easeOut() {
            float t = 1 - t();
            return 1 - t * t;
        }

        public UltimatePayloads.Cast cast() {
            return film.cast;
        }

        /** 演的人（同伴，或者放最后一击的玩家）；不在本机的世界里时是 null。 */
        public @Nullable Entity performer() {
            var level = Minecraft.getInstance().level;
            return level == null ? null : level.getEntity(film.cast.performer());
        }

        /** 打谁；没有、或者不在本机的世界里时是 null。 */
        public @Nullable Entity targetEntity() {
            var level = Minecraft.getInstance().level;
            return level == null || film.cast.target() < 0 ? null : level.getEntity(film.cast.target());
        }

        /** 演的人脚下（看不到她时是放的那一点）。 */
        public Vec3 feet() {
            Entity e = performer();
            return e == null ? film.cast.aim() : e.getPosition(partial);
        }

        /** 演的人的眼睛。 */
        public Vec3 eyes() {
            Entity e = performer();
            return e == null ? film.cast.aim().add(0, 1.5, 0) : e.getEyePosition(partial);
        }

        /** 演的人胸口（身高六成处）。 */
        public Vec3 chest() {
            Entity e = performer();
            return e == null ? film.cast.aim().add(0, 1, 0) : e.getPosition(partial).add(0, e.getBbHeight() * .6, 0);
        }

        /** 目标脚下；没有目标时是放的那一点。 */
        public Vec3 target() {
            Entity e = targetEntity();
            return e == null ? film.cast.aim() : e.getPosition(partial);
        }

        /** 目标胸口；没有目标时是放的那一点往上一格。 */
        public Vec3 targetChest() {
            Entity e = targetEntity();
            return e == null ? film.cast.aim().add(0, 1, 0) : e.getPosition(partial).add(0, e.getBbHeight() * .6, 0);
        }

        /** 开演时选好的那一点（没有目标时是准星前方的地面）。 */
        public Vec3 aim() {
            return film.cast.aim();
        }

        /** 下令的玩家的眼睛。 */
        public Vec3 owner() {
            var p = Minecraft.getInstance().player;
            return p == null ? eyes() : p.getEyePosition(partial);
        }

        /** 演的人面朝哪（水平）。 */
        public Vec3 forward() {
            Entity e = performer();
            float yaw = e instanceof LivingEntity l && !(e instanceof Player) ? Mth.rotLerp(partial, l.yBodyRotO, l.yBodyRot)
                    : e == null ? 0 : e.getViewYRot(partial);
            double r = Math.toRadians(yaw);
            return new Vec3(-Math.sin(r), 0, Math.cos(r));
        }

        /** 演的人的右手边（水平）。 */
        public Vec3 right() {
            Vec3 f = forward();
            return new Vec3(-f.z, 0, f.x);
        }

        /** 从演的人指向目标（水平）；看不到目标时是她面朝的方向。 */
        public Vec3 toward() {
            Vec3 d = target().subtract(feet());
            d = new Vec3(d.x, 0, d.z);
            return d.lengthSqr() < 1e-4 ? forward() : d.normalize();
        }

        /**
         * 以 {@code center} 为圆心、水平离 {@code distance} 格、高 {@code height} 格的一点；{@code yaw} 度是方位：
         * 0 在她正前方，90 在她右手边，180 在她身后。环绕、弧形摇镜就是让 {@code yaw} 随进度变。
         */
        public Vec3 around(Vec3 center, float yaw, float distance, float height) {
            double r = Math.toRadians(yaw);
            Vec3 f = forward(), side = right();
            return center.add(f.scale(Math.cos(r) * distance)).add(side.scale(Math.sin(r) * distance)).add(0, height, 0);
        }

        public float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        public float lerp(float a, float b) {
            return lerp(a, b, ease());
        }

        public Vec3 lerp(Vec3 a, Vec3 b, float t) {
            return a.lerp(b, t);
        }

        /** 站在 {@code from} 看 {@code at}，不歪，视角照玩家的。 */
        public CameraDirector.Aim look(Vec3 from, Vec3 at) {
            return new CameraDirector.Aim(from, at, 0);
        }

        /** 站在 {@code from} 看 {@code at}，画面歪 {@code roll} 度（180 是上下颠倒），视角 {@code fov} 度（NaN 照玩家的）。 */
        public CameraDirector.Aim look(Vec3 from, Vec3 at, float roll, float fov) {
            return new CameraDirector.Aim(from, at, roll, fov);
        }
    }
}
