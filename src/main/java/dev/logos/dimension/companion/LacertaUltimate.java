package dev.logos.dimension.companion;

import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.combat.Airborne;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.dimension.ultimate.RelayGift;
import dev.logos.dimension.ultimate.UltimateCast;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimateHit;
import dev.logos.dimension.ultimate.UltimateScript;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.Tags;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔的奥义「绯穹裁决 · 炽龙裂地破」（docs/LACERTA_COMPANION.md 第 22 节，分镜稿 {@code lacerta_ult_storyboard.md}）：
 * 服务端这一半——她怎么动、播哪段动作、什么时候打、打多重、交出什么馈赠。镜头和画面在客户端的 {@code LacertaUltimateShow}。
 *
 * <p>完整版 107 刻（{@link Beats#FULL}）照分镜八镜：
 * <ol>
 *   <li>翼震贴地冲阵（0–11）：振翼一下，贴着地面冲到目标跟前；</li>
 *   <li>撞阵横扫（11–24）：炽焰长剑满月横扫，第 18 刻打中，卡肉两刻；</li>
 *   <li>逆焰升龙挑空（24–37）：龙尾抽地，斜撩把一圈敌怪挑上天；</li>
 *   <li>振翼登穹（37–51）：跃到它们上方，第 46–50 刻举剑过顶滞空；离天花板不足就压低（{@link #lift}）；</li>
 *   <li>赤星坠击（51–63）：垂直坠下；</li>
 *   <li>炽剑裂地（63）：主伤害、击飞、交出馈赠、一次黑白冲击帧，卡肉三刻；</li>
 *   <li>万岩崩飞（74–91）与焦烟擦镜（91–107）：碎岩和光柱是客户端的特效，她拄剑立定。</li>
 * </ol>
 * 第 0 刻到登穹之前，目标周围 {@link #PULL_RADIUS} 格内的普通敌怪一直被往中心拉；路上三下轻伤（第 8、18、31 刻）。
 *
 * <p>缩短版 44 刻（{@link Beats#SHORT}，连携里第二位起）只留最有辨识度的两镜：从原地振翼登穹定格，再陨星坠斩裂地，最后擦镜。
 * 两版的伤害一样：缩短版没有路上那三下，折进裂地那一下（{@link #mainScale}）。
 *
 * <p>伤害全是龙炎（{@link Dragonfire#deal}），走 {@link UltimateCast#hit} 乘连携倍率和前面的馈赠。不碰方块：不破坏、不点燃，
 * 地裂和碎岩都是特效。
 */
public final class LacertaUltimate implements UltimateScript {
    /**
     * 一版的时间表（刻）。没有的那一拍是 -1（缩短版没有冲阵、横扫、挑空）。客户端的画面照同一张表。
     *
     * @param length    一共几刻
     * @param impact    裂地（主伤害、馈赠、窗口起算）
     * @param dash      冲阵到第几刻为止（从 0 起）
     * @param sweep     横扫起手；{@code sweepHit} 打中
     * @param ascend    升龙挑空起手；{@code ascendHit} 打中、挑飞
     * @param rise      登穹起跳
     * @param apex      到顶、滞空开始
     * @param dive      滞空结束、开始坠
     * @param strike    剑从头顶砍下的那一刻（动作从举剑转到下劈）
     * @param recover   卡肉结束，动作接着播收势
     * @param stand     拄剑立定（切到 {@code cutin} 那个姿势）
     * @param wipe      擦镜开始
     */
    public record Beats(int length, int impact, int dash, int sweep, int sweepHit, int ascend, int ascendHit, int rise, int apex, int dive,
                        int strike, int recover, int stand, int wipe) {
        public static final Beats FULL = new Beats(107, 63, 11, 11, 18, 24, 31, 37, 46, 51, 58, 66, 90, 95);
        public static final Beats SHORT = new Beats(44, 20, -1, -1, -1, -1, -1, 0, 9, 13, 17, 23, 34, 33);

        public static Beats of(boolean condensed) {
            return condensed ? SHORT : FULL;
        }

        public boolean condensed() {
            return this == SHORT || sweep < 0;
        }

        /** 路上的几下轻伤在哪几刻（缩短版没有）。 */
        public int[] lightHits() {
            return condensed() ? new int[0] : new int[]{dash - 3, sweepHit, ascendHit};
        }

        /** 第 {@code t} 刻还在往中心拉敌怪（登穹之前；缩短版到坠下之前）。 */
        public boolean pulling(int t) {
            return t >= 0 && t < (condensed() ? strike : rise);
        }
    }

    // ---------------------------------------------------------------- 数

    /** 一剑的底：她的攻击 + 炽焰长剑（同招式里的一剑，{@link LacertaBlade#SWORD}）。 */
    static final float SWORD = LacertaBlade.SWORD;
    /** 路上每一下轻伤、裂地那一下各是一剑的几倍。 */
    static final float LIGHT = .4f, MAIN = 3f;
    /** 往中心拉：拉多远以内的、每两刻推一下最快多快（格/刻）、离中心多近就不拉了。 */
    static final double PULL_RADIUS = 8, PULL_SPEED = .42, PULL_STOP = 1.4;
    /** 轻伤的范围：冲过去时她身边、横扫、挑空（格）。 */
    static final double DASH_REACH = 3.2, SWEEP_REACH = 4.8, ASCEND_REACH = 4.5;
    /** 裂地的范围（以落点为心）；开演时选好的目标离落点这么远以内（飞着、被掀开了）也吃得到。 */
    static final double MAIN_RADIUS = 6, TARGET_REACH = 12;
    /** 挑空往上多快（格/刻）；横扫震起来多高；裂地往外、往上掀多快。 */
    static final double LAUNCH = .78, HOP = .28, KNOCK_OUT = 1.0, KNOCK_UP = .55;
    /** 冲到离中心多远停（站在目标跟前，不和它重叠）；落点离中心多远（剑尖插在中心）。 */
    static final double STAND = 1.7, LAND_BACK = 1.1;
    /** 冲阵贴地多高（格）。 */
    static final double SKIM = .12;
    /** 登穹最高跳多高；离天花板至少留多少（格）。 */
    static final double LIFT = 7, CLEARANCE = .3;
    /** 天花板不到这么高（从地面量，格）就算室内，跳的高度跟着压低。分镜：不足 5 格压低，最低 1.8 格小跳。 */
    static final double LOW_CEILING = 5, MIN_HOP = 1.8;
    /** 馈赠「龙炎碎甲」：后面的人打中带碎甲的目标时无视几成护甲；顺手再缠几刻龙炎。 */
    public static final double SUNDER_PIERCE = .4;
    public static final int SUNDER_BURN = 60;
    /** 动作：登穹举剑到头顶（skyfall 动作里 0.27 秒那一帧）、下劈砍中（0.5 秒，contact 标记）。 */
    static final double OVERHEAD = .27, CONTACT = .5, SKYFALL_END = 1.06;

    /** 一剑的底（她的攻击 + 剑）。 */
    static float base(float attack) {
        return attack + SWORD;
    }

    /** 裂地那一下是一剑的几倍：完整版 {@link #MAIN}；缩短版把路上三下轻伤也折进来，两版一样重。 */
    static float mainScale(boolean condensed) {
        return condensed ? MAIN + LIGHT * Beats.FULL.lightHits().length : MAIN;
    }

    /**
     * 登穹跳多高：头顶 {@code headroom} 格（从地面量到天花板）、她身高 {@code height}。开阔处跳 {@link #LIFT}；
     * 室内压到头顶留 {@link #CLEARANCE}，绝不顶进天花板（连 {@link #MIN_HOP} 都放不下时按放得下的跳）。
     */
    static double lift(double headroom, double height) {
        return Math.max(0, Math.min(LIFT, headroom - height - CLEARANCE));
    }

    /** 两头慢中间快。 */
    static double ease(double t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    // ---------------------------------------------------------------- 一次

    private final Beats beats;
    /** 打中过的（裂地和路上的轻伤）：馈赠「龙炎碎甲」只管它们。交出馈赠时裂地还没打（框架先交再调 tick），所以交的是这一份本身。 */
    private final Set<UUID> sundered = new HashSet<>();
    private Vec3 home = Vec3.ZERO, center = Vec3.ZERO, dir = new Vec3(0, 0, 1), stop = Vec3.ZERO, apexAt = Vec3.ZERO, landAt = Vec3.ZERO;
    private Vec3 riseFrom = Vec3.ZERO, diveFrom = Vec3.ZERO;
    private float yaw;
    /** 这一次登穹跳了多高（测试看）。 */
    private double lift;
    /** 动作：在播哪一段、从哪一刻开始播（客户端按「名字@开始刻」认是不是换了一段）。 */
    private String clip = "";
    private long clipStart;

    private LacertaUltimate(Beats beats) {
        this.beats = beats;
    }

    public static LacertaUltimate full() {
        return new LacertaUltimate(Beats.FULL);
    }

    public static LacertaUltimate condensed() {
        return new LacertaUltimate(Beats.SHORT);
    }

    public Beats beats() {
        return beats;
    }

    @Override
    public int length() {
        return beats.length;
    }

    @Override
    public int impact() {
        return beats.impact;
    }

    /** 这一次登穹跳了多高（格）。 */
    public double lifted() {
        return lift;
    }

    // ---------------------------------------------------------------- 测试用：最近一次

    private static double lastLift = -1, peakHead = Double.NEGATIVE_INFINITY;
    private static int stuckTicks;

    /** 最近一次登穹跳了多高。 */
    public static double lastLift() {
        return lastLift;
    }

    /** 最近一次她头顶到过的最高处（世界 y）。 */
    public static double peakHead() {
        return peakHead;
    }

    /** 最近一次有几刻她和方块重叠（卡进墙、天花板）。 */
    public static int stuckTicks() {
        return stuckTicks;
    }

    @Override
    public void start(UltimateCast cast) {
        if (!(cast.companion() instanceof LacertaCompanion c)) return;
        ServerLevel level = cast.level();
        // 巨龙形态时先变回人形（变的时候炸开一团龙炎遮着）；坐着、守着宝藏的起身；手上的招式收掉，剑由这里管
        if (c.isDragonForm()) c.dragon.revert(level, cast.owner());
        c.rest.rise(level, true);
        c.flight.land();
        c.blade.drop();
        // 契印的动作盖在所有动画上面：掌心龙息停下、振翼之类的动作收掉（逆鳞的加成照旧）
        c.seal.stopBreath(level);
        c.playSeal("", 0, 0, 0);
        c.setSitting(false);
        c.getNavigation().stop();
        c.setSwordShown(true);
        c.mood.play("face_battle", beats.length + 2);
        c.setNoGravity(true);
        home = c.position();
        center = ground(level, cast.aim(), 24);   // 目标飞在天上时砸它下面的地
        Vec3 flat = center.subtract(home).multiply(1, 0, 1);
        dir = flat.lengthSqr() > 1e-3 ? flat.normalize() : forward(c.getYRot());
        yaw = (float) (Mth.atan2(-dir.x, dir.z) * Mth.RAD_TO_DEG);
        double reach = Math.max(0, flat.length() - STAND);
        stop = ground(level, home.add(dir.scale(reach)), 2);
        Vec3 apexBase = center.subtract(dir.scale(.6));
        lift = lift(headroom(level, apexBase, LIFT + c.getBbHeight() + 1, c.getBbWidth()), c.getBbHeight());
        apexAt = apexBase.add(0, lift, 0);
        lastLift = lift;
        peakHead = Double.NEGATIVE_INFINITY;
        stuckTicks = 0;
        landAt = ground(level, center.subtract(dir.scale(LAND_BACK)), 3);
        // 起手：振翼一下，脚下一圈风和龙炎
        Vec3 at = c.position();
        level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + .1, at.z, 16, .7, .05, .7, .06);
        FoxfireCue.burst(level, at.add(0, .4, 0), FoxfireFlame.DRAGON, 1.2f);
        sound(level, at, SoundEvents.ENDER_DRAGON_FLAP, 1, 1.25f);
        sound(level, at, SoundEvents.FIRECHARGE_USE, .7f, .8f);
    }

    @Override
    public void tick(UltimateCast cast, int t) {
        if (!(cast.companion() instanceof LacertaCompanion c)) return;
        ServerLevel level = cast.level();
        c.getNavigation().stop();
        c.setDeltaMovement(Vec3.ZERO);
        c.resetFallDistance();
        c.setNoGravity(true);
        if (beats.pulling(t) && t % 2 == 0) pull(level, c, cast);
        if (beats.condensed()) shortTick(cast, c, level, t);
        else fullTick(cast, c, level, t);
        face(c);
        peakHead = Math.max(peakHead, c.getY() + c.getBbHeight());
        if (!level.noCollision(c, c.getBoundingBox().deflate(.02))) stuckTicks++;
    }

    /** 完整版的每一刻。 */
    private void fullTick(UltimateCast cast, LacertaCompanion c, ServerLevel level, int t) {
        Beats b = beats;
        if (t < b.dash) {
            // 冲阵：贴地，两头慢中间快
            if (t == 0) pose(c, "fly_move", 1.6f, true);
            Vec3 at = home.lerp(stop, ease((t + 1) / (double) b.dash));
            moveTo(c, ground(level, at, 2).add(0, SKIM, 0));
            if (t == 2) {
                sound(level, c.position(), SoundEvents.WIND_CHARGE_BURST.value(), 1.2f, .7f);
                sound(level, c.position(), SoundEvents.ENDER_DRAGON_FLAP, .8f, 1.5f);
            }
        } else if (t < b.rise) {
            moveTo(c, stop);
        }
        if (t == b.dash - 3) light(cast, c, level, c.position(), DASH_REACH, 0);
        if (t == b.sweep) {
            pose(c, "crimson_flame_sweep", 1, false);
            sound(level, c.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1, .6f);
            sound(level, c.position(), SoundEvents.BLAZE_SHOOT, .6f, .7f);
        }
        if (t == b.sweepHit) {
            light(cast, c, level, c.position(), SWEEP_REACH, HOP);
            pose(c, "crimson_flame_sweep", 0, false);   // 卡肉两刻
            sound(level, c.position(), SoundEvents.PLAYER_ATTACK_STRONG, 1, .7f);
            sound(level, c.position(), SoundEvents.ANVIL_LAND, .35f, .55f);
        }
        if (t == b.sweepHit + 2) pose(c, "crimson_flame_sweep", 1, false);
        if (t == b.ascend) {
            // 龙尾抽地借力
            pose(c, "crimson_ascend_3", 1, false);
            Vec3 tail = c.position().subtract(dir.scale(.9));
            level.sendParticles(ParticleTypes.CLOUD, tail.x, tail.y + .1, tail.z, 8, .3, .05, .3, .05);
            sound(level, tail, SoundEvents.MACE_SMASH_GROUND_HEAVY, .5f, 1.3f);
        }
        if (t == b.ascendHit) {
            light(cast, c, level, c.position(), ASCEND_REACH, LAUNCH);
            sound(level, c.position(), SoundEvents.BLAZE_SHOOT, 1, .9f);
            sound(level, c.position(), SoundEvents.ENDER_DRAGON_GROWL, .35f, 1.6f);
        }
        aerial(cast, c, level, t);
    }

    /** 缩短版的每一刻：从原地直接登穹（往目标上方去），后面和完整版一样。 */
    private void shortTick(UltimateCast cast, LacertaCompanion c, ServerLevel level, int t) {
        aerial(cast, c, level, t);
    }

    /** 登穹、滞空、坠下、裂地、收势：两版共用，时刻照各自的表。 */
    private void aerial(UltimateCast cast, LacertaCompanion c, ServerLevel level, int t) {
        Beats b = beats;
        if (t == b.rise) {
            riseFrom = c.position();
            // 举剑过顶：跳到顶时正好举到头顶
            pose(c, "crimson_skyfall_judgement", (float) (OVERHEAD / ((b.apex - b.rise) / 20.0)), false);
            sound(level, c.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.2f, 1);
            level.sendParticles(ParticleTypes.CLOUD, c.getX(), c.getY() + .1, c.getZ(), 12, .5, .05, .5, .07);
        }
        if (t >= b.rise && t < b.apex) {
            double k = (t - b.rise + 1) / (double) (b.apex - b.rise);
            // 往上冲得快、到顶收住；水平方向两头慢
            double up = 1 - (1 - k) * (1 - k);
            Vec3 flat = riseFrom.lerp(apexAt, ease(k));
            moveTo(c, new Vec3(flat.x, Mth.lerp(up, riseFrom.y, apexAt.y), flat.z));
        }
        if (t == b.apex) {
            pose(c, "crimson_skyfall_judgement", 0, false);   // 滞空：定在举剑过顶
            diveFrom = c.position();
        }
        if (t >= b.apex && t < b.dive) moveTo(c, diveFrom);
        if (t == b.dive) {
            diveFrom = c.position();
            // 坠下时慢慢挪到下劈的起势，砍下那一刻正好砍中
            pose(c, "crimson_skyfall_judgement", .15f, false);
            sound(level, c.position(), SoundEvents.ELYTRA_FLYING, .9f, 1.4f);
            sound(level, c.position(), SoundEvents.ENDER_DRAGON_FLAP, 1, .7f);
        }
        if (t >= b.dive && t < b.impact) {
            double k = (t - b.dive + 1) / (double) (b.impact - b.dive);
            Vec3 flat = diveFrom.lerp(landAt, k);
            moveTo(c, new Vec3(flat.x, Mth.lerp(k * k, diveFrom.y, landAt.y), flat.z));
        }
        if (t == b.strike) {
            double at = OVERHEAD + .15 * (b.strike - b.dive) / 20.0;
            pose(c, "crimson_skyfall_judgement", (float) ((CONTACT - at) / ((b.impact - b.strike) / 20.0)), false);
        }
        if (t == b.impact) slam(cast, c, level);
        if (t >= b.impact) moveTo(c, landAt);
        if (t == b.recover) pose(c, "crimson_skyfall_judgement", .5f, false);
        if (t == b.stand) pose(c, "cutin", 1, true);
        // 碎岩崩落、余震
        if (t > b.impact && t < b.wipe && (t - b.impact) % 7 == 3) {
            sound(level, center, SoundEvents.GENERIC_EXPLODE.value(), .45f, .5f + .1f * ((t / 7) % 3));
        }
        if (t == b.wipe + 2) {
            sound(level, center, SoundEvents.MACE_SMASH_GROUND_HEAVY, .4f, .6f);
            sound(level, c.position(), SoundEvents.ARMOR_EQUIP_NETHERITE.value(), .7f, 1.2f);
        }
    }

    /** 裂地：落点 {@link #MAIN_RADIUS} 格内的高额龙炎（缠身）、往外往上掀，一次黑白冲击帧。 */
    private void slam(UltimateCast cast, LacertaCompanion c, ServerLevel level) {
        float amount = base(c.attack()) * mainScale(beats.condensed());
        for (LivingEntity e : foes(level, c, cast, center, MAIN_RADIUS, true)) {
            if (cast.hit(e, amount, dealer(c, true)) > 0) sundered.add(e.getUUID());
            if (!e.isAlive()) continue;
            if (!heavy(e)) {
                Vec3 out = e.position().subtract(center).multiply(1, 0, 1);
                double d = out.length();
                out = d > 1e-3 ? out.scale(1 / d) : dir;
                double k = KNOCK_OUT * (1 - .5 * Math.min(1, d / MAIN_RADIUS));
                e.setDeltaMovement(out.x * k, KNOCK_UP, out.z * k);
                e.hurtMarked = true;
            }
            FoxfireCue.hit(level, e, center, FoxfireFlame.DRAGON);
        }
        c.fought(level.getGameTime());
        ScreenEffects.flash(cast.owner(), ScreenFlash.at(CameraPoint.world(center.add(0, 1, 0)))
                .frames(ScreenFlash.Frame.SILHOUETTE, ScreenFlash.Frame.ENGULF));
        FoxfireCue.burst(level, center.add(0, .3, 0), FoxfireFlame.DRAGON, 3);
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + .5, center.z, 3, 1.2, .2, 1.2, 0);
        level.sendParticles(ParticleTypes.CLOUD, center.x, center.y + .2, center.z, 40, 2.5, .1, 2.5, .18);
        sound(level, center, SoundEvents.GENERIC_EXPLODE.value(), 2, .55f);
        sound(level, center, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.4f, .6f);
        sound(level, center, SoundEvents.DRAGON_FIREBALL_EXPLODE, 1.2f, .7f);
        sound(level, center, SoundEvents.MACE_SMASH_GROUND_HEAVY, 1.4f, .6f);
        sound(level, center, SoundEvents.BEACON_ACTIVATE, 1.2f, .7f);
        pose(c, "crimson_skyfall_judgement", 0, false);   // 卡肉三刻
    }

    /** 路上的一下轻伤：{@code at} 周围 {@code reach} 格内的，往上掀 {@code up}（Boss、浮空免疫、很重的不掀）。 */
    private void light(UltimateCast cast, LacertaCompanion c, ServerLevel level, Vec3 at, double reach, double up) {
        float amount = base(c.attack()) * LIGHT;
        for (LivingEntity e : foes(level, c, cast, at, reach, false)) {
            if (cast.hit(e, amount, dealer(c, false)) > 0) sundered.add(e.getUUID());
            if (up > 0 && e.isAlive() && !heavy(e)) {
                Vec3 v = e.getDeltaMovement();
                e.setDeltaMovement(v.x * .3, Math.max(v.y, up), v.z * .3);
                e.hurtMarked = true;
            }
            FoxfireCue.hit(level, e, c.getEyePosition(), FoxfireFlame.DRAGON);
        }
        c.fought(level.getGameTime());
    }

    /** 龙族的聚怪：中心 {@link #PULL_RADIUS} 格内的普通敌怪往中心拉（Boss 不拉）。被定住的也照样被拉（定住只锁了走路）。 */
    private void pull(ServerLevel level, LacertaCompanion c, UltimateCast cast) {
        for (LivingEntity e : foes(level, c, cast, center, PULL_RADIUS, false)) {
            if (heavy(e)) continue;
            Vec3 d = center.subtract(e.position()).multiply(1, 0, 1);
            double len = d.length();
            if (len < PULL_STOP) continue;
            Vec3 v = d.scale(Math.min(PULL_SPEED, len * .12) / len);
            e.setDeltaMovement(v.x, e.getDeltaMovement().y, v.z);
            e.hurtMarked = true;
        }
    }

    @Override
    public void end(UltimateCast cast, boolean interrupted) {
        if (!(cast.companion() instanceof LacertaCompanion c)) return;
        c.setNoGravity(false);
        c.setMove("");
        c.setSwordShown(false);
        c.mood.cut();
        // 在半空被打断：马上展翼滑下来
        if (!c.onGround()) c.fallDistance = Math.max(c.fallDistance, LacertaCompanion.GLIDE_AFTER);
    }

    @Override
    public @Nullable RelayGift gift(UltimateCast cast) {
        return new Sunder(sundered, cast.stage().index());
    }

    // ---------------------------------------------------------------- 馈赠「龙炎碎甲」

    /**
     * 「龙炎碎甲」（规范 4.2 第 4 条「逆鳞余焰」）：她打中过的目标，在整个连携里被后面的每一段和玩家的最后一击无视 {@link #SUNDER_PIERCE}
     * 的护甲和韧性，每打中一下再缠一层龙炎（{@link #SUNDER_BURN} 刻）。管到最后一击（{@link #keep} 一直留）。
     */
    public static final class Sunder implements RelayGift {
        private final Set<UUID> marked;
        private final int from;
        /** 测试用：后面的人打中带碎甲的目标几次。 */
        static int pierced;

        public Sunder(Set<UUID> marked, int from) {
            this.marked = marked;
            this.from = from;
        }

        /** 是从第几段交出来的。 */
        public int from() {
            return from;
        }

        /** 带着碎甲吗。 */
        public boolean marked(UUID id) {
            return marked.contains(id);
        }

        @Override
        public void hit(UltimateCast cast, UltimateHit hit) {
            if (!marked.contains(hit.target().getUUID())) return;
            hit.pierce(SUNDER_PIERCE);
            Dragonfire.burn(hit.target(), cast.performer(), SUNDER_BURN);
            pierced++;
        }

        @Override
        public boolean keep(UltimateChain.Stage done) {
            return true;
        }

        public static int pierced() {
            return pierced;
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * {@code at} 周围 {@code radius} 格内她当作敌人、能伤到的（不伤你、别的同伴、你的宠物）。开演时选好的目标也算（主人打过的不一定是敌对生物）；
     * {@code reachTarget} 时目标离得再远一点（飞在天上、被掀开了）也算进来，免得主目标吃不到裂地。
     */
    private static List<LivingEntity> foes(ServerLevel level, LacertaCompanion c, UltimateCast cast, Vec3 at, double radius, boolean reachTarget) {
        LivingEntity target = cast.target();
        List<LivingEntity> list = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius, radius * .75, radius),
                e -> e != c && e.isAlive() && c.canHarm(e) && (c.hostileTo(e) || e == target) && e.position().distanceToSqr(at) <= radius * radius));
        if (reachTarget && target != null && !list.contains(target) && c.canHarm(target) && target.position().distanceToSqr(at) <= TARGET_REACH * TARGET_REACH)
            list.add(target);
        return list;
    }

    /** 拉不动、掀不起来的：Boss、浮空免疫的、生命上限很高的。 */
    private static boolean heavy(LivingEntity e) {
        return e.is(Tags.EntityTypes.BOSSES) || e.is(Airborne.IMMUNE) || e.getMaxHealth() >= CompanionEntity.STRONG * 5;
    }

    /** 一下龙炎：看血量和伤害吸收有没有掉（同她的剑）。 */
    private static UltimateCast.Dealer dealer(LacertaCompanion c, boolean burn) {
        return (target, amount) -> {
            float before = target.getHealth() + target.getAbsorptionAmount();
            boolean died = Dragonfire.deal(c, target, amount, burn);
            return died || target.getHealth() + target.getAbsorptionAmount() < before;
        };
    }

    /** 换一段动作（同一段只改快慢：客户端按「名字@开始刻」认，快慢变了接着播，不从头来）。 */
    private void pose(LacertaCompanion c, String name, float speed, boolean loop) {
        if (!name.equals(clip)) {
            clip = name;
            clipStart = c.level().getGameTime();
        }
        c.setMove(name + "|" + clipStart + "|" + speed + "|" + loop);
    }

    /** 挪到 {@code to}：按碰撞走，被方块挡住就停在挡住的地方（不穿墙、不顶进天花板）。 */
    private static void moveTo(LacertaCompanion c, Vec3 to) {
        Vec3 d = to.subtract(c.position());
        if (d.lengthSqr() < 1e-6) return;
        c.move(MoverType.SELF, d);
    }

    private void face(LacertaCompanion c) {
        c.setYRot(yaw);
        c.yBodyRot = yaw;
        c.yBodyRotO = yaw;
        c.setYHeadRot(yaw);
        c.setXRot(0);
    }

    private static Vec3 forward(float yaw) {
        float r = yaw * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(r), 0, Mth.cos(r));
    }

    /** {@code at} 这一列往下 {@code depth} 格内的地面（从上面 1.5 格往下找）；找不到就原样。 */
    static Vec3 ground(Level level, Vec3 at, double depth) {
        Vec3 from = at.add(0, 1.5, 0), to = at.add(0, -depth, 0);
        var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? at : new Vec3(at.x, hit.getLocation().y, at.z);
    }

    /** 从 {@code feet} 往上量到天花板有几格（她身子四角和正中都量，取最矮的），最多量到 {@code max}。 */
    static double headroom(Level level, Vec3 feet, double max, double width) {
        double room = max, w = width * .45;
        double[][] offsets = {{0, 0}, {w, w}, {w, -w}, {-w, w}, {-w, -w}};
        for (double[] o : offsets) {
            Vec3 from = feet.add(o[0], .05, o[1]), to = feet.add(o[0], max, o[1]);
            var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) room = Math.min(room, hit.getLocation().y - feet.y);
        }
        return room;
    }

    private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume, pitch);
    }
}
