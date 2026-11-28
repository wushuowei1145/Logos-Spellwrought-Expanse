package dev.logos.dimension.client.companion;

import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.client.screenfx.WorldStyleRenderer;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateShow;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.ShiraumeUltimate;
import dev.logos.dimension.ultimate.UltimatePayloads;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * 白梅的奥义「狐嫁入 · 水镜形代断」的画面（docs/SHIRAUME_COMPANION.md 第 11 节，分镜稿 {@code shiraume_ult_storyboard.md}）：
 * 分镜运镜和擦镜用公共的 {@link UltimateFilm}，水镜、浮世绘、纸形代和因果丝、居合、破镜、因果斩、九尾都在她自己的全屏后期
 * {@link ShiraumeUltimateScreen} 里。时刻和服务端脚本是同一张节拍表 {@link ShiraumeUltimate.Beats}。
 *
 * <p>分镜（完整版的刻数）：
 * <ol>
 * <li>0–13 落雨成镜：贴地低机位，从她的脸俯摇到脚前的水洼；水里倒着她和一轮绯月，一滴雨荡开淡粉的涟漪。</li>
 * <li>13–28 翻入水镜：镜头往水里扎，水面漫过整个画面（整个画面上下翻），镜头再滚转 180°，倒着的世界转正、左右颠倒，
 *     浮世绘画风上满；她捻起一张纸形代。</li>
 * <li>28–41 吹纸：她的侧脸特写，吹一口，纸人散作千纸朝敌群扑过去，镜头横甩追过去（方向模糊）。</li>
 * <li>41–55 百纸封影：从敌群跟前沿着因果丝急拉回她的肩后，千纸贴满敌人，朱粉的丝都收进她手里那一张。</li>
 * <li>55–67 斩纸：定机位、长焦，拔刀前静下来，掌前一记横斩只斩那一张纸人。</li>
 * <li>67–78 破镜：斩开的两半烧成淡粉狐火，收刀「咔哒」；镜中画面裂开、一块块飞散，后面是反向翻正的黑白现世。</li>
 * <li>78–93 因果斩：广角，斜贯全屏的月牙斩痕把敌群和绯月一刀两断，两侧错开，九尾冲天；速度线、震屏、一次黑白冲击帧（服务端）。</li>
 * <li>93–107 擦镜：梅瓣横扫过去，回到玩家视角。</li>
 * </ol>
 * 缩短版（44 刻）只留水镜翻入、斩纸、破镜、因果斩和擦镜，形代一开始就贴在敌人身上。
 */
public final class ShiraumeUltimateShow implements UltimateShow {
    /** 她档案里放的：每次开演新开一个。不是本机玩家下的令时没有画面。 */
    public static final UltimateShow.Factory FACTORY = ShiraumeUltimateShow::begin;

    /** 纸人、形代在世界里多高（格）。 */
    static final double PAPER_SIZE = .3, DOLL_SIZE = .28;
    /** 千纸一张飞多少刻、一张比一张晚多少刻起飞。 */
    static final float FLIGHT = 7, STAGGER = .4f;
    /** 水洼半径（格）；月亮：过场前半（水镜、镜中）在她身后，破镜以后在敌人身后，越往后越大。 */
    static final float PUDDLE = 1.7f, MOON_POOL = .075f, MOON_MIRROR = .14f, MOON_REAL = .2f;
    /** 斩痕的斜度（弧度，往右上）、两侧最多错开几像素（按 720 高算）、九尾多长（画面高度的几成）。 */
    static final float SLASH_ANGLE = .38f, SHIFT = 12, TAILS = .85f;

    private static long frames;

    private final UltimatePayloads.Cast cast;
    private final ShiraumeUltimate.Beats b;
    private final UltimateFilm film;
    private final int floodEnd;
    /** 被纸形代贴住、牵上因果丝的：目标在最前面。 */
    private final List<Integer> bound;
    private final Paper[] papers = new Paper[ShiraumeUltimateScreen.PAPERS];
    private final ShiraumeUltimateScreen.Params params = new ShiraumeUltimateScreen.Params();

    /** 一张千纸：贴到第几个敌人身上的哪一点、什么时候起飞、飞的弧多高多偏、转多少。 */
    private record Paper(int foe, double dx, double dy, double dz, float launch, double lift, double swirl, float tilt) {}

    private ShiraumeUltimateShow(UltimatePayloads.Cast cast, ShiraumeUltimate.Beats b, UltimateFilm film) {
        this.cast = cast;
        this.b = b;
        this.film = film;
        this.floodEnd = floodEnd(b);
        Minecraft mc = Minecraft.getInstance();
        Entity her = mc.level == null ? null : mc.level.getEntity(cast.performer());
        Entity target = target(cast);
        this.bound = bound(cast, her, target);
        long seed = cast.seed();
        for (int i = 0; i < papers.length; i++) {
            double h1 = hash(seed, i, 1), h2 = hash(seed, i, 2), h3 = hash(seed, i, 3), h4 = hash(seed, i, 4), h5 = hash(seed, i, 5);
            papers[i] = new Paper(bound.isEmpty() ? -1 : i % bound.size(), h1 - .5, .12 + h2 * .72, h3 - .5,
                    b.blowAt() + i * STAGGER, .35 + h4 * .9, (h5 - .5) * 1.2, (float) ((h2 - .5) * .9));
        }
    }

    static @Nullable UltimateShow begin(UltimatePayloads.Cast cast) {
        ShiraumeUltimate.Beats b = ShiraumeUltimate.beats(cast.condensed());
        UltimateFilm film = film(cast, b);
        if (!film.play()) return null;
        ShiraumeUltimateScreen.forget();
        return new ShiraumeUltimateShow(cast, b, film);
    }

    /** 水漫过整个画面的那一刻：这之后整个画面上下翻（镜中），倒影收起。 */
    static int floodEnd(ShiraumeUltimate.Beats b) {
        return b.dive() + (b.condensed() ? 2 : 3);
    }

    // ---------------------------------------------------------------- 分镜

    static UltimateFilm film(UltimatePayloads.Cast cast, ShiraumeUltimate.Beats b) {
        int flood = floodEnd(b), rolled = b.rolled();
        int cut2End = b.condensed() ? b.draw() : b.blow();
        UltimateFilm film = UltimateFilm.of(cast)
                // Cut 1：贴地低机位，从她的脸俯摇到脚前的水洼
                .cut(0, b.dive(), s -> s.look(s.around(s.feet(), 32, s.lerp(3f, 2.7f), .34f),
                        s.lerp(s.eyes().add(0, .1, 0), s.feet().add(s.forward().scale(.7)).add(0, .12, 0), s.ease()), 0, 58))
                // Cut 2：往水里扎（水漫过画面），再滚转 180° 翻进镜中；起身到她胸前，她捻起一张形代
                .cut(b.dive(), cut2End, s -> {
                    float l = s.local();
                    float dip = smooth(l / (flood - b.dive()));
                    float rise = smooth((l - (flood - b.dive())) / Math.max(1, cut2End - flood));
                    float roll = 180 * smooth((l - (flood - b.dive())) / Math.max(1, rolled - flood));
                    Vec3 from = s.around(s.feet(), s.lerp(32, 58, rise), s.lerp(s.lerp(2.7f, 2.3f, dip), 2.4f, rise), s.lerp(s.lerp(.34f, .16f, dip), .95f, rise));
                    Vec3 look = s.lerp(s.feet().add(s.forward().scale(.7)).add(0, .12, 0), s.chest().add(0, .1, 0), rise);
                    return s.look(from, look, roll, s.lerp(58, 50, rise));
                });
        if (!b.condensed()) {
            int whip = b.blowAt() + 1;
            // Cut 3：她左侧的侧脸特写（镜中左右颠倒，她在画面左边、朝右），吹一口，镜头横甩追着千纸过去
            film.cut(b.blow(), b.bind(), s -> {
                float w = Mth.clamp((s.age() - whip) / (b.bind() - whip), 0, 1);
                w = w * w;
                Vec3 face = s.eyes().add(s.forward().scale(.3)).add(0, -.12, 0);
                Vec3 from = s.around(s.eyes(), -88, 1.5f, -.1f).add(s.forward().scale(w * 1.4));
                return s.look(from, s.lerp(face, s.targetChest(), w), 180, s.lerp(48, 62, w));
            });
            // Cut 4：从敌群跟前沿着因果丝急拉回她的肩后（她在画面左下角）
            film.cut(b.bind(), b.draw(), s -> {
                float t = s.t(), e = 1 - (1 - t) * (1 - t) * (1 - t);
                Vec3 tow = s.toward(), left = s.right().scale(-1);
                Vec3 near = s.targetChest().subtract(tow.scale(2.4)).add(left.scale(.6)).add(0, .35, 0);
                Vec3 over = s.chest().subtract(tow.scale(1.7)).add(left.scale(.75)).add(0, .45, 0);
                return s.look(s.lerp(near, over, e), s.targetChest().add(0, .2 * e, 0), 180, s.lerp(55, 62, e));
            });
        }
        film
                // Cut 5：定机位、长焦，她在画面右边朝左，掌前横斩
                .cut(b.draw(), b.burn(), s -> {
                    Vec3 hand = hand(s);
                    return s.look(s.around(s.chest(), s.lerp(62, 58), s.lerp(2.3f, 2.05f), .05f), s.lerp(s.chest(), hand, .5f), 180, 44);
                })
                // Cut 6：斩开的两半烧着，收刀
                .cut(b.burn(), b.sheathe(), s -> s.look(s.around(s.chest(), 50, s.lerp(2.5f, 2.7f), .2f), s.chest(), 180, 50))
                // 碎镜后面：她身后看敌群，反向滚转 180° 翻正（黑白现世）
                .cut(b.sheathe(), b.impact(), s -> {
                    Vec3 tow = s.toward();
                    Vec3 from = s.chest().subtract(tow.scale(2.4)).add(s.right().scale(.9)).add(0, .7, 0);
                    return s.look(from, s.targetChest().add(0, .5, 0), 180 * (1 - s.ease()), 60);
                })
                // Cut 7：广角，敌群和天上的绯月，斩痕和九尾
                .cut(b.impact(), b.wipe(), s -> {
                    Vec3 tow = s.toward();
                    Vec3 from = s.feet().subtract(tow.scale(s.lerp(3.6f, 3.0f, s.easeOut()))).add(s.right().scale(1.8)).add(0, 1.5, 0);
                    return s.look(from, s.targetChest().add(0, 1.3, 0), 0, 74);
                })
                // Cut 8：她优雅地站着，敌人在她身后；梅瓣擦镜
                .cut(b.wipe(), b.length(), s -> s.look(s.around(s.chest(), s.lerp(118, 108), 3f, .35f), s.lerp(s.chest(), s.targetChest(), .25f), 0, 55))
                .lines(b.impact(), b.impact() + 9, 2, UltimateFilm.Lines.RADIAL, .9f, 0, 0xFFFFFF)
                .blur(b.impact(), b.impact() + 3, 1, UltimateFilm.Blur.RADIAL, .55f, 0, .5f, .55f)
                .shake(b.impact(), b.impact() + 8, 2, .26f)
                .wipe(b.condensed() ? b.wipe() : b.wipe() + 2, b.length(), UltimateFilm.Wipe.PETAL, 0xFFC2E0, 0xF7B6D6);
        if (!b.condensed()) film.blur(b.blowAt() + 2, b.bind(), 1, UltimateFilm.Blur.DIRECTIONAL, .7f, 0, .5f, .5f);
        return film;
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** 一镜里她捻形代的那只手。 */
    private static Vec3 hand(UltimateFilm.Shot s) {
        Entity e = s.performer();
        double height = e == null ? 1.5 : e.getBbHeight();
        return ShiraumeUltimate.handOf(s.feet(), s.forward(), height);
    }

    // ---------------------------------------------------------------- 每刻、每帧

    @Override
    public boolean tick(UltimatePayloads.Cast cast, int age) {
        return age < cast.length();
    }

    @Override
    public void frame(UltimatePayloads.Cast cast, float age) {
        if (UltimateFilm.playing() == film) ShiraumeUltimateScreen.want();
    }

    @Override
    public void screen(UltimatePayloads.Cast cast, float clientAge) {
        if (UltimateFilm.playing() != film) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float age = film.age(partial);
        Entity her = mc.level.getEntity(cast.performer());
        if (her == null) return;
        boolean hold = fill(mc, her, age, partial);
        ShiraumeUltimateScreen.draw(params, hold);
        frames++;
    }

    @Override
    public void end(UltimatePayloads.Cast cast, boolean interrupted) {
        if (interrupted) film.stop();
    }

    /** 画了几帧（客户端场景检查）。 */
    public static long frames() {
        return frames;
    }

    /** 测试用。 */
    public UltimateFilm film() {
        return film;
    }

    /** 按这一帧的刻填全屏后期的参数；返回这一帧画完要不要存下来留给破镜。 */
    private boolean fill(Minecraft mc, Entity her, float age, float partial) {
        ShiraumeUltimateScreen.Params p = params;
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.position();
        Matrix4f viewProjection = PostVfx.viewProjection();
        p.inverse.set(viewProjection).invert();
        p.age = age;
        p.seed = cast.seed() & 0xFFF;
        p.cameraX = wrap(cam.x);
        p.cameraY = (float) cam.y;
        p.cameraZ = wrap(cam.z);
        p.hideFlash = mc.options.hideLightningFlash().get() ? 1 : 0;

        Vec3 feet = her.getPosition(partial);
        Vec3 forward = forward(her, partial);
        Vec3 hand = ShiraumeUltimate.handOf(feet, forward, her.getBbHeight());
        Entity target = target(cast);
        Vec3 targetFeet = target == null ? cast.aim() : target.getPosition(partial);
        Vec3 targetChest = target == null ? cast.aim().add(0, 1, 0) : targetFeet.add(0, target.getBbHeight() * .6, 0);

        // 现世：夜雨、倒影、潜进水里
        float dive = b.dive();
        p.night = age < dive ? 1 : 1 - clamp((age - dive) / (floodEnd - dive));
        p.flood = age >= dive && age < floodEnd ? clamp((age - dive) / (floodEnd - dive)) : 0;
        p.reflection = age < floodEnd ? 1 - p.flood : 0;
        p.mirror = age >= floodEnd && age < b.sheathe() ? 1 : 0;
        boolean mirror = p.mirror > .5f;
        float[] feetOnScreen = project(viewProjection, cam, feet, false);
        p.waterLine = feetOnScreen == null ? .45f : Mth.clamp(feetOnScreen[1], .05f, .95f);
        Vec3 puddle = ShiraumeUltimate.puddleOf(feet, forward);
        p.puddleX = (float) (puddle.x - cam.x);
        p.puddleY = (float) (puddle.y - cam.y);
        p.puddleZ = (float) (puddle.z - cam.z);
        p.puddleRadius = age < floodEnd ? PUDDLE : 0;
        p.waterHeight = (float) (feet.y - cam.y);
        float[] drop = project(viewProjection, cam, puddle, false);
        p.rippleAge = age >= b.drop() && age < floodEnd && drop != null ? age - b.drop() : -1;
        if (drop != null) {
            p.rippleX = drop[0];
            p.rippleY = drop[1];
        }

        // 画风：浮世绘、黑白现世、月亮
        float ukiyoFrom = floodEnd - 1, ukiyoTo = b.rolled() + 1;
        p.ukiyo = age < ukiyoFrom || age >= b.sheathe() ? 0 : clamp((age - ukiyoFrom) / (ukiyoTo - ukiyoFrom));
        p.mono = age < b.sheathe() ? 0 : age < b.wipe() ? 1 : 1 - clamp((age - b.wipe()) / 6);
        p.moonOn = age < b.wipe() ? 1 : 1 - clamp((age - b.wipe()) / 5);
        // 月亮：水镜、镜中挂在她身后、偏向第一镜镜头的那一侧，低低的；破镜以后挂在敌人身后。按她看向目标的方向每帧算（开演那一刻她还没转过来）
        Vec3 toward = targetFeet.subtract(feet).multiply(1, 0, 1);
        toward = toward.lengthSqr() < 1e-4 ? forward : toward.normalize();
        Vec3 side = new Vec3(-toward.z, 0, toward.x);
        Vec3 moon = age < b.sheathe()
                ? toward.scale(-Math.cos(Math.toRadians(20))).subtract(side.scale(Math.sin(Math.toRadians(20)))).scale(Math.cos(Math.toRadians(16)))
                        .add(0, Math.sin(Math.toRadians(16)), 0)
                : toward.scale(Math.cos(Math.toRadians(17))).add(0, Math.sin(Math.toRadians(17)), 0);
        p.moonX = (float) moon.x;
        p.moonY = (float) moon.y;
        p.moonZ = (float) moon.z;
        p.moonRadius = age < floodEnd ? MOON_POOL : age < b.sheathe() ? MOON_MIRROR : MOON_REAL;

        // 掌前的纸形代：吹散以前、因果丝收回来以后拿在手里；斩开以后烧掉
        boolean holding = b.condensed() ? age >= floodEnd && age < b.sheathe()
                : (age >= floodEnd + 2 && age < b.blowAt()) || (age >= b.bound() && age < b.sheathe());
        float[] handOnScreen = project(viewProjection, cam, hand, mirror);
        p.handState = holding && handOnScreen != null ? age >= b.slash() ? 2 : 1 : 0;
        if (handOnScreen != null) {
            p.handX = handOnScreen[0];
            p.handY = handOnScreen[1];
            p.handSize = size(handOnScreen, DOLL_SIZE);
        }
        p.burn = clamp((age - b.slash()) / (b.sheathe() - b.slash()));
        float iaido = age - (b.slash() - .7f);
        p.iaido = iaido >= 0 && iaido <= 9 && age < b.sheathe() ? iaido : -1;

        // 因果丝：从每个被贴住的敌人牵回她手里，斩纸那一下断掉
        p.threads = 0;
        if (handOnScreen != null && age >= b.bound() && age < b.slash() + 1) {
            for (int id : bound) {
                if (p.threads >= ShiraumeUltimateScreen.THREADS) break;
                Entity e = mc.level.getEntity(id);
                if (!(e instanceof LivingEntity l) || !l.isAlive()) continue;
                float[] at = project(viewProjection, cam, e.getPosition(partial).add(0, e.getBbHeight() * .6, 0), mirror);
                if (at == null) continue;
                p.thread[p.threads * 2] = at[0];
                p.thread[p.threads * 2 + 1] = at[1];
                p.threads++;
            }
        }
        p.threadsOn = clamp((age - b.bound()) / 2) * (age < b.slash() ? 1 : 1 - clamp(age - b.slash()));

        // 千纸：吹散以后从她手边飞出去，一张张贴到敌人身上（缩短版一开始就贴着）
        boolean paperTime = age >= (b.condensed() ? floodEnd : b.blowAt()) && age < b.sheathe();
        p.papers = 0;
        p.papersOn = paperTime ? 1 : 0;
        if (paperTime) {
            for (Paper paper : papers) {
                if (paper.foe < 0 || paper.foe >= bound.size()) continue;
                Entity e = mc.level.getEntity(bound.get(paper.foe));
                if (e == null) continue;
                float[] at = paper(paper, e, hand, age, partial, viewProjection, cam, mirror);
                int i = p.papers;
                if (at == null) {
                    p.paper[i * 4 + 2] = 0;
                } else {
                    p.paper[i * 4] = at[0];
                    p.paper[i * 4 + 1] = at[1];
                    p.paper[i * 4 + 2] = size(at, PAPER_SIZE);
                    p.paper[i * 4 + 3] = at[3];
                }
                p.papers++;
            }
        }

        // 破镜：收刀前一两刻的镜中画面存下来，收刀起裂开飞散
        boolean hold = age >= b.sheathe() - 2 && age < b.sheathe();
        p.shatter = age >= b.sheathe() && age < b.impact() ? clamp((age - b.sheathe()) / (b.impact() - b.sheathe())) : -1;
        // 从斩开的那一点裂开：收刀之前一直跟着那只手，之后定住
        if (age < b.sheathe() && handOnScreen != null) {
            p.shatterX = Mth.clamp(handOnScreen[0], .15f, .85f);
            p.shatterY = Mth.clamp(handOnScreen[1], .15f, .85f);
        }

        // 因果斩、九尾
        float since = age - b.impact();
        float[] slashAt = project(viewProjection, cam, targetChest, false);
        p.slash = since >= 0 && since < 14 ? since : -1;
        p.slashX = slashAt == null ? .5f : Mth.clamp(slashAt[0], .2f, .8f);
        p.slashY = slashAt == null ? .5f : Mth.clamp(slashAt[1], .2f, .8f);
        p.slashAngle = SLASH_ANGLE;
        p.shift = since >= 0 && since < 5 ? (float) (SHIFT * mc.getMainRenderTarget().height / 720.0 * Math.pow(1 - since / 5, 1.5)) : 0;
        float tailsEnd = Math.min(b.impact() + 15, b.wipe() + 3);
        p.tails = since < .5f ? 0 : clamp((since - .5f) / 2) * (1 - clamp((age - (tailsEnd - 4)) / 4));
        float grow = clamp((since - .5f) / 5);
        p.tailsGrow = 1 - (1 - grow) * (1 - grow);
        Vec3 away = targetFeet.subtract(feet).multiply(1, 0, 1);
        away = away.lengthSqr() < 1e-4 ? forward : away.normalize();
        float[] root = project(viewProjection, cam, targetFeet.add(away.scale(.8)), false);
        p.tailsX = root == null ? .5f : root[0];
        p.tailsY = root == null ? .3f : Math.min(root[1], .45f);
        p.tailsSize = TAILS;
        return hold;
    }

    /** 一张千纸这一帧在画面上的位置、到镜头的距离和转角；还没起飞、在镜头后面时是 null。 */
    private float @Nullable [] paper(Paper paper, Entity foe, Vec3 hand, float age, float partial, Matrix4f vp, Vec3 cam, boolean mirror) {
        Vec3 base = foe.getPosition(partial);
        double w = foe.getBbWidth() * .55;
        Vec3 stick = base.add(paper.dx * w * 2, paper.dy * foe.getBbHeight(), paper.dz * w * 2);
        // 贴的那一面朝着镜头：往镜头那边挪到盒子表面
        Vec3 toCam = cam.subtract(stick).multiply(1, 0, 1);
        if (toCam.lengthSqr() > 1e-4) stick = stick.add(toCam.normalize().scale(w * .6));
        Vec3 at;
        float spin;
        if (b.condensed() || age >= paper.launch + FLIGHT) {
            at = stick;
            spin = paper.tilt;
        } else if (age < paper.launch) {
            return null;
        } else {
            float f = (age - paper.launch) / FLIGHT;
            float e = 1 - (1 - f) * (1 - f);
            Vec3 side = stick.subtract(hand).cross(new Vec3(0, 1, 0));
            side = side.lengthSqr() < 1e-4 ? Vec3.ZERO : side.normalize();
            Vec3 mid = hand.lerp(stick, .5).add(0, paper.lift, 0).add(side.scale(paper.swirl));
            at = hand.scale((1 - e) * (1 - e)).add(mid.scale(2 * e * (1 - e))).add(stick.scale(e * e));
            spin = paper.tilt + (1 - e) * (float) Math.sin(age * 1.7 + paper.swirl * 5) * 1.4f;
        }
        float[] out = project(vp, cam, at, mirror);
        if (out == null) return null;
        return new float[]{out[0], out[1], out[2], spin};
    }

    // ---------------------------------------------------------------- 小工具

    /** {@code world} 投到这一帧画面上：x、y（0–1，左下是 0；镜中时上下翻），到镜头的深度。在镜头后面时是 null。 */
    private static float @Nullable [] project(Matrix4f viewProjection, Vec3 cam, Vec3 world, boolean mirror) {
        Vector4f v = viewProjection.transform(new Vector4f((float) (world.x - cam.x), (float) (world.y - cam.y), (float) (world.z - cam.z), 1));
        if (v.w <= .05f) return null;
        float x = v.x / v.w * .5f + .5f, y = v.y / v.w * .5f + .5f;
        return new float[]{x, mirror ? 1 - y : y, v.w};
    }

    /** {@code height} 格高的东西在那个深度上占画面高度的几成。 */
    private static float size(float[] projected, double height) {
        return (float) (height * WorldStyleRenderer.PROJECTION.m11() / (2 * projected[2]));
    }

    private static float clamp(float t) {
        return Mth.clamp(t, 0, 1);
    }

    private static float wrap(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate / 4096) * 4096);
    }

    private static double hash(long seed, int i, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL + salt * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    private static Vec3 forward(Entity e, float partial) {
        float yaw = e instanceof LivingEntity l && !(e instanceof Player) ? Mth.rotLerp(partial, l.yBodyRotO, l.yBodyRot) : e.getViewYRot(partial);
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }

    private static @Nullable Entity target(UltimatePayloads.Cast cast) {
        var level = Minecraft.getInstance().level;
        return level == null || cast.target() < 0 ? null : level.getEntity(cast.target());
    }

    /** 被纸形代贴住的：目标，加上它周围几格里的敌人（同服务端的溅射范围，最多凑满因果丝的根数），按远近。 */
    private static List<Integer> bound(UltimatePayloads.Cast cast, @Nullable Entity her, @Nullable Entity target) {
        List<Integer> out = new ArrayList<>();
        if (target == null) return out;
        out.add(target.getId());
        var level = Minecraft.getInstance().level;
        if (level == null) return out;
        Vec3 at = target.getBoundingBox().getCenter();
        List<LivingEntity> near = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(ShiraumeUltimate.SPLASH_RADIUS),
                e -> e != target && e != her && e.isAlive() && e instanceof Enemy && !(e instanceof CompanionEntity) && e.getId() != cast.owner()
                        && e.getBoundingBox().getCenter().distanceTo(at) <= ShiraumeUltimate.SPLASH_RADIUS));
        near.sort((x, y) -> Double.compare(x.distanceToSqr(at), y.distanceToSqr(at)));
        for (LivingEntity e : near) {
            if (out.size() >= ShiraumeUltimateScreen.THREADS) break;
            out.add(e.getId());
        }
        return out;
    }
}
