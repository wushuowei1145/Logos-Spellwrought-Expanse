package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.dimension.ryuka.FoxMarks;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.dimension.ultimate.RelayGift;
import dev.logos.dimension.ultimate.UltimateCast;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimateDef;
import dev.logos.dimension.ultimate.UltimateHit;
import dev.logos.dimension.ultimate.UltimateScript;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 白梅的奥义「狐嫁入 · 水镜形代断」在服务端的一半（docs/SHIRAUME_COMPANION.md 第 11 节，分镜稿 {@code shiraume_ult_storyboard.md}）。
 * 落雨成镜、翻进浮世绘的水镜里，吹散一张形代把敌群一个个贴住、牵上因果丝，掌前一刀只斩那张小纸人；收刀破镜，回到现世，
 * 这一刀的因果才落到敌人身上：五段居合、强行叠满五枚狐火印当场引爆、周围 {@link #SPLASH_RADIUS} 格樱花溅射。
 *
 * <p>这里管她的动作、声音、世界里所有人都看得见的粒子和伤害；镜头、水镜倒影、浮世绘后期、纸形代和因果丝、破镜、斩痕、九尾
 * 只有下令的玩家看得到，写在客户端的 {@code ShiraumeUltimateShow}。两边按同一张节拍表（{@link Beats}）对齐。
 *
 * <p>接力馈赠「狐火蚀骨」（{@link Corrosion}）：主目标身边转着三朵残樱狐印，下一段（或玩家的最后一击）打中它时伤害
 * ×{@link #CORROSION}，第一下在它身上二次炸开淡粉狐火。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class ShiraumeUltimate implements UltimateScript {
    /**
     * 一段奥义的节拍（刻，开演以后）。完整版照分镜稿，缩短版只留水镜翻入、斩纸、破镜、因果斩和擦镜（维护者 2026-11-28 定）。
     *
     * @param length  一共几刻
     * @param impact  因果斩落下：结算伤害、接力窗口起算（分镜 Cut 7）
     * @param drop    雨滴落进水镜（Cut 1）
     * @param dive    镜头潜进水镜，开始 180° 翻转（Cut 2）
     * @param rolled  翻完、浮世绘画风上满
     * @param blow    吹形代那一镜开始（Cut 3）；缩短版没有，是 -1
     * @param bind    百纸封影、牵因果丝那一镜开始（Cut 4）；缩短版没有，是 -1
     * @param draw    斩纸那一镜开始（Cut 5）
     * @param slash   居合斩断形代的那一下
     * @param sheathe 收刀「咔哒」、水镜碎开、镜头反向翻正（Cut 6）
     * @param wipe    梅瓣擦镜那一镜开始（Cut 8）
     */
    public record Beats(int length, int impact, int drop, int dive, int rolled, int blow, int bind, int draw, int slash, int sheathe, int wipe) {
        /** 缩短版：没有吹纸、封影两镜，形代一开始就贴在敌人身上。 */
        public boolean condensed() {
            return blow < 0;
        }

        /** 吹的那一口（吹纸那一镜开始后 5 刻）；缩短版没有。 */
        public int blowAt() {
            return blow < 0 ? -1 : blow + 5;
        }

        /** 形代贴到敌人身上、因果丝牵起来的那一刻：完整版是封影那一镜开始后 4 刻，缩短版是一进斩纸那一镜就有。 */
        public int bound() {
            return bind < 0 ? draw : bind + 4;
        }

        /** 收刀那一镜（Cut 6）开始：斩断以后形代烧着，收刀前 3 刻。 */
        public int burn() {
            return sheathe - 3;
        }

        /** 流火凝出来的那一刻：赶在居合之前凝好（{@link CompanionBlade#MANIFEST} 刻）。 */
        public int manifest() {
            return slash - CompanionBlade.MANIFEST - 1;
        }

        /** 横斩起手的那一刻：横斩的接触在动作 0.79 秒，按 {@link CompanionBlade#ATTACK_SPEED} 倍播是 7 刻多一点。 */
        public int swing() {
            return slash - 7;
        }
    }

    /** 完整版：107 刻（5.35 秒），第 78 刻出手。 */
    public static final Beats FULL = new Beats(107, 78, 6, 13, 21, 28, 41, 55, 61, 70, 93);
    /** 缩短版：44 刻（2.2 秒），第 24 刻出手。 */
    public static final Beats CONDENSED = new Beats(44, 24, 1, 3, 8, -1, -1, 8, 14, 18, 34);

    /** 五段居合：每段是她一刀（{@link ShiraumeCompanion#bladeDamage}）的几倍，五段合成一下打出去（刀伤，吃护甲）。 */
    public static final int SLASHES = 5;
    public static final double SLASH = .8;
    /** 满印引爆：一刀的几倍，至少是平常五枚印爆开的那一下（{@link FoxMarks#BURST}），狐火伤害。 */
    public static final double BURST = 3;
    /** 樱花溅射：主目标周围几格、一刀的几倍（狐火）。 */
    public static final double SPLASH = 1.5, SPLASH_RADIUS = 4;
    /** 狐火蚀骨：带残樱狐印的目标被下一段打中时伤害乘多少。 */
    public static final double CORROSION = 1.25;
    /** 残樱狐印最多挂多久（刻）：盖过整个接力窗口，再加上下一段完整版。 */
    public static final int MARK_LIFE = UltimateChain.WINDOW + UltimateDef.MAX_FULL + 40;
    /** 目标离她超过这么远就先闪到它跟前（{@link #BLINK_TO} 格）。 */
    static final double BLINK_FROM = 7, BLINK_TO = 4.2;
    /** 九尾虚影：几条、在世界里往上冒几刻。 */
    static final int TAILS = 9, TAIL_TICKS = 10;

    private final Beats beats;

    private ShiraumeUltimate(Beats beats) {
        this.beats = beats;
    }

    public static ShiraumeUltimate full() {
        return new ShiraumeUltimate(FULL);
    }

    public static ShiraumeUltimate condensed() {
        return new ShiraumeUltimate(CONDENSED);
    }

    /** 这一段演哪一版的节拍（客户端按 {@code Cast#condensed} 取同一张）。 */
    public static Beats beats(boolean condensed) {
        return condensed ? CONDENSED : FULL;
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

    // ---------------------------------------------------------------- 伤害（纯算术，单元测试直接测）

    /** 五段居合合起来的基础伤害（再乘连携倍率和馈赠）。 */
    public static float slashes(float blade) {
        return (float) (blade * SLASHES * SLASH);
    }

    /** 满印引爆的基础伤害。 */
    public static float burst(float blade) {
        return (float) Math.max(FoxMarks.BURST, blade * BURST);
    }

    /** 溅射到周围每一个的基础伤害。 */
    public static float splash(float blade) {
        return (float) (blade * SPLASH);
    }

    /** 狐火蚀骨加过的伤害。 */
    public static float corroded(float amount) {
        return (float) (amount * CORROSION);
    }

    // ---------------------------------------------------------------- 演

    @Override
    public void start(UltimateCast cast) {
        if (!(cast.companion() instanceof ShiraumeCompanion c)) return;
        ServerLevel level = cast.level();
        c.blade.drop();
        c.setMove("");
        c.setMisc("face_kitsune");
        c.getNavigation().stop();
        LivingEntity target = cast.target();
        if (target != null && horizontal(c.position(), target.position()) > BLINK_FROM) blink(level, c, target);
        face(c, target != null ? target.position() : cast.aim(), 180);
    }

    @Override
    public void tick(UltimateCast cast, int t) {
        if (!(cast.companion() instanceof ShiraumeCompanion c)) return;
        ServerLevel level = cast.level();
        LivingEntity target = cast.target();
        Beats b = beats;
        c.getNavigation().stop();
        c.setDeltaMovement(0, c.getDeltaMovement().y, 0);
        if (t < b.impact) face(c, target != null ? target.position() : cast.aim(), 20);
        Vec3 feet = c.position(), forward = forward(c);
        Vec3 hand = hand(c);

        // Cut 1：夜雨微落，一滴雨砸进脚前的水镜
        if (t < b.dive + 2) rain(level, feet, c);
        if (t == b.drop) {
            Vec3 drop = puddle(c);
            level.sendParticles(ParticleTypes.SPLASH, drop.x, drop.y + .05, drop.z, 10, .12, 0, .12, .05);
            level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, .7f), drop.x, drop.y + .05, drop.z, 8, .35, 0, .35, 0);
            sound(level, drop, SoundEvents.POINTED_DRIPSTONE_DRIP_WATER_INTO_CAULDRON, 1.2f, 1.1f);
            sound(level, drop, SoundEvents.AMETHYST_BLOCK_CHIME, .7f, 1.7f);
        }
        // Cut 2：潜进水镜翻进浮世绘，能剧小鼓一声；她捻起一张形代
        if (t == b.dive) {
            sound(level, feet, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 1.4f, .5f);
            sound(level, feet, SoundEvents.PLAYER_SPLASH, .6f, 1.3f);
        }
        if (t == b.dive + 3) sound(level, feet, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), .9f, .62f);
        if (!b.condensed() && t == b.dive + 3) play(c, level, "cast_foxfire_r", .45f, false);

        // Cut 3：吹一口，形代化作千纸和梅瓣朝敌群扑过去
        if (b.blowAt() >= 0 && t == b.blowAt()) {
            sound(level, hand, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), .35f, 1.7f);
            sound(level, hand, SoundEvents.CHERRY_LEAVES_FALL, 1, 1.2f);
        }
        if (b.blowAt() >= 0 && t >= b.blowAt() && t < b.blowAt() + 6) {
            stream(level, hand, target != null ? target.position().add(0, target.getBbHeight() * .6, 0) : cast.aim().add(0, 1, 0), c);
            if ((t - b.blowAt()) % 2 == 0) sound(level, hand, SoundEvents.BOOK_PAGE_TURN, .9f, .9f + .25f * (t - b.blowAt()) / 2);
        }

        // Cut 4（缩短版是一进斩纸那一镜）：纸形代贴满敌群、牵起因果丝；主目标上的狐火印一枚枚叠上去（叠到四枚，第五枚在出手时）
        List<LivingEntity> bound = target == null || t < b.bound() || t >= b.slash ? List.of() : bound(c, target);
        if (t == b.bound()) {
            for (LivingEntity e : bound) {
                Vec3 at = e.position().add(0, e.getBbHeight() * .6, 0);
                level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Items.PAPER), at.x, at.y, at.z, 10, .25, .35, .25, .05);
                level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 6, .3, .4, .3, .02);
            }
            sound(level, feet, SoundEvents.BOOK_PAGE_TURN, 1, .7f);
            sound(level, feet, SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), .8f, 1.6f);
        }
        if (target != null && t >= b.bound() && t < b.bound() + 4 * 3 && (t - b.bound()) % 3 == 0 && t < b.slash
                && FoxMarks.count(target) < FoxMarks.FULL - 1) FoxMarks.add(c, target);
        if (t >= b.bound() && t < b.slash && (t - b.bound()) % 2 == 0) threads(level, hand, bound);
        if (t == b.bound() + 4 && !b.condensed()) sound(level, feet, SoundEvents.CROSSBOW_LOADING_END.value(), .7f, 1.9f);

        // Cut 5：凝刀，拔刀前静下来，掌前横斩，只斩那一张纸人
        if (t == b.manifest()) {
            c.setBladeShown(true);
            sound(level, hand, SoundEvents.AMETHYST_BLOCK_RESONATE, .5f, 1.7f);
        }
        if (t == b.swing() - 3) play(c, level, "ryuka_idle", 1, true);
        if (t == b.swing()) play(c, level, "ryuka_yoko", (float) CompanionBlade.ATTACK_SPEED, false);
        if (t == b.slash) {
            Vec3 cut = hand.add(forward.scale(.3));
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, cut.x, cut.y, cut.z, 1, 0, 0, 0, 0);
            sound(level, cut, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.1f, 1.9f);
            sound(level, cut, SoundEvents.ANVIL_LAND, .25f, 2);
            sound(level, cut, SoundEvents.PLAYER_ATTACK_SWEEP, .6f, 1.5f);
        }
        // 斩成两半的形代烧成淡粉狐火
        if (t > b.slash && t < b.sheathe && (t - b.slash) % 3 == 1) {
            Vec3 cut = hand.add(forward.scale(.3));
            FoxfireCue.burst(level, cut, FoxfireFlame.SHIRAUME, .45f);
            level.sendParticles(ParticleTypes.WHITE_ASH, cut.x, cut.y, cut.z, 6, .15, .15, .15, .01);
        }
        if (t == b.slash + 5) play(c, level, "ryuka_idle", 1, true);

        // Cut 6：收刀咔哒一声，水镜碎了
        if (t == b.sheathe) {
            c.setBladeShown(false);
            sound(level, hand, SoundEvents.LEVER_CLICK, 1, 1.75f);
            sound(level, hand, SoundEvents.ARMOR_EQUIP_IRON.value(), .6f, 1.6f);
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, hand.x, hand.y, hand.z, 4, .3, .3, .3, .02);
        }
        if (t == b.sheathe + 1) {
            sound(level, feet, SoundEvents.GLASS_BREAK, 1.1f, .75f);
            sound(level, feet, SoundEvents.GENERIC_SPLASH, .7f, 1.4f);
            level.sendParticles(ParticleTypes.SPLASH, feet.x, feet.y + .1, feet.z, 30, 1.2, 0, 1.2, .2);
        }

        // Cut 7：因果斩落下
        if (t >= b.impact && t < b.impact + TAIL_TICKS) tails(level, c, target, cast.aim(), t - b.impact);
        if (t == b.impact + 5) sound(level, target != null ? target.position() : cast.aim(), SoundEvents.FOX_SCREECH, 1.3f, .7f);

        // Cut 8：梅瓣燃灰，她优雅地站着
        if (t == b.wipe - 4) {
            c.setMove("");
            c.setMisc("act_proud");
        }
        if (t >= b.impact + 4 && t < b.length && t % 3 == 0 && target != null) {
            Vec3 at = target.position().add(0, target.getBbHeight() + .5, 0);
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 4, 1.6, .4, 1.6, .01);
        }
        if (t == b.impact) impact(cast, c, level, target);
    }

    @Override
    public void end(UltimateCast cast, boolean interrupted) {
        if (!(cast.companion() instanceof ShiraumeCompanion c)) return;
        c.setBladeShown(false);
        c.setMove("");
        c.setMisc("");
    }

    @Override
    public @Nullable RelayGift gift(UltimateCast cast) {
        LivingEntity target = cast.target();
        return target == null ? null : new Corrosion(target);
    }

    /** 因果斩：五段居合、强行叠满五枚狐火印当场引爆、樱花溅射，一次黑白冲击帧。 */
    private static void impact(UltimateCast cast, ShiraumeCompanion c, ServerLevel level, @Nullable LivingEntity target) {
        Vec3 at = target != null ? target.getBoundingBox().getCenter() : cast.aim().add(0, 1, 0);
        ScreenEffects.flash(cast.owner(), ScreenFlash.at(CameraPoint.world(at))
                .frames(ScreenFlash.Frame.GLOW, ScreenFlash.Frame.SILHOUETTE, ScreenFlash.Frame.ENGULF));
        sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), 1.2f, .7f);
        sound(level, at, SoundEvents.PLAYER_ATTACK_SWEEP, 1.4f, .6f);
        sound(level, at, SoundEvents.LIGHTNING_BOLT_IMPACT, .7f, 1.6f);
        sound(level, at, SoundEvents.SOUL_ESCAPE.value(), 1.4f, 1.2f);
        // 斜贯过去的一道斩痕：沿着她看过去的方向斜着落一串横扫
        Vec3 side = right(c);
        for (int i = -4; i <= 4; i++) {
            Vec3 p = at.add(side.scale(i * .8)).add(0, -i * .32, 0);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 70, 1.8, 1.2, 1.8, .06);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 30, .3, .3, .3, .35);
        float blade = c.bladeDamage();
        if (target != null) {
            FoxMarks.clear(target);
            cast.hit(target, slashes(blade));
            cast.hit(target, burst(blade), foxfire(c));
            FoxfireCue.burst(level, at, FoxfireFlame.SHIRAUME, Math.max(1.8f, target.getBbHeight() * 1.4f));
            if (target.isAlive()) FoxfireRules.burn(target, FoxfireFlame.SHIRAUME, c, FoxMarks.BURN_TICKS);
        }
        for (LivingEntity e : splashed(c, target, at)) {
            cast.hit(e, splash(blade), foxfire(c));
            FoxfireCue.hit(level, e, at, FoxfireFlame.SHIRAUME);
            if (e.isAlive()) FoxfireRules.burn(e, FoxfireFlame.SHIRAUME, c, FoxfireRules.BURN_TICKS);
        }
    }

    /** 狐火伤害：同她的狐火弹，吃狐火的规矩（同族只受四分之一）。返回打没打进去。 */
    private static UltimateCast.Dealer foxfire(ShiraumeCompanion c) {
        return (target, amount) -> {
            float before = target.getHealth();
            boolean died = Foxfire.deal(c, target, amount, new Foxfire.Context(FoxfireFlame.SHIRAUME, true, false));
            return died || target.getHealth() < before;
        };
    }

    /** 被形代贴住、牵上因果丝的：主目标，加上它周围 {@link #SPLASH_RADIUS} 格里她打得着的敌人（最多 7 个，按远近）。 */
    static List<LivingEntity> bound(ShiraumeCompanion c, LivingEntity target) {
        List<LivingEntity> out = new ArrayList<>();
        out.add(target);
        out.addAll(splashed(c, target, target.getBoundingBox().getCenter()));
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    /** 溅射到的：{@code at} 周围 {@link #SPLASH_RADIUS} 格里她打得着、算敌人的，主目标除外，按远近。 */
    static List<LivingEntity> splashed(ShiraumeCompanion c, @Nullable LivingEntity target, Vec3 at) {
        List<LivingEntity> out = new ArrayList<>(c.level().getEntitiesOfClass(LivingEntity.class,
                new net.minecraft.world.phys.AABB(at, at).inflate(SPLASH_RADIUS),
                e -> e != target && e != c && e.isAlive() && c.canHarm(e) && c.hostileTo(e)
                        && e.getBoundingBox().getCenter().distanceTo(at) <= SPLASH_RADIUS));
        out.sort((a, b) -> Double.compare(a.distanceToSqr(at), b.distanceToSqr(at)));
        return out.size() > 7 ? out.subList(0, 7) : out;
    }

    // ---------------------------------------------------------------- 世界里的样子

    /** 夜雨：她身边稀稀落落几滴。 */
    private static void rain(ServerLevel level, Vec3 feet, ShiraumeCompanion c) {
        var r = c.getRandom();
        for (int i = 0; i < 2; i++) {
            double a = r.nextDouble() * Mth.TWO_PI, d = .6 + r.nextDouble() * 2.6;
            level.sendParticles(ParticleTypes.FALLING_WATER, feet.x + Math.cos(a) * d, feet.y + 2.6 + r.nextDouble(), feet.z + Math.sin(a) * d, 1, 0, 0, 0, 0);
        }
    }

    /** 吹散的千纸和梅瓣：从她手边朝敌群一路扑过去。 */
    private static void stream(ServerLevel level, Vec3 from, Vec3 to, ShiraumeCompanion c) {
        Vec3 d = to.subtract(from);
        double len = Math.max(.5, d.length());
        Vec3 dir = d.scale(1 / len);
        var paper = new ItemParticleOption(ParticleTypes.ITEM, Items.PAPER);
        var r = c.getRandom();
        for (int i = 0; i < 6; i++) {
            double k = r.nextDouble() * .4;
            Vec3 p = from.add(d.scale(k)).add((r.nextDouble() - .5) * .5, (r.nextDouble() - .5) * .4, (r.nextDouble() - .5) * .5);
            level.sendParticles(i % 2 == 0 ? paper : ParticleTypes.CHERRY_LEAVES, p.x, p.y, p.z, 0, dir.x, dir.y + .05, dir.z, .6 + r.nextDouble() * .4);
        }
    }

    /** 因果丝：从被贴住的每一个牵回她手里，一串淡粉的光点。 */
    private static void threads(ServerLevel level, Vec3 hand, List<LivingEntity> bound) {
        var dust = new DustParticleOptions(0xFF7FA8, .55f);
        for (LivingEntity e : bound) {
            Vec3 from = e.position().add(0, e.getBbHeight() * .6, 0);
            for (int i = 1; i <= 7; i++) {
                Vec3 p = from.lerp(hand, i / 8.0);
                level.sendParticles(dust, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
        }
    }

    /** 九尾虚影：从斩痕后面扇形地往天上冒九道淡粉狐火。 */
    private static void tails(ServerLevel level, ShiraumeCompanion c, @Nullable LivingEntity target, Vec3 aim, int age) {
        Vec3 base = target != null ? target.position() : aim;
        Vec3 away = base.subtract(c.position()).multiply(1, 0, 1);
        away = away.lengthSqr() < 1e-4 ? forward(c) : away.normalize();
        Vec3 side = new Vec3(-away.z, 0, away.x);
        var core = new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.6f);
        var bright = new DustParticleOptions(FoxfireFlame.SHIRAUME.bright, 1.1f);
        for (int i = 0; i < TAILS; i++) {
            double fan = (i - (TAILS - 1) / 2.0) / ((TAILS - 1) / 2.0);
            double h = .8 + age * .7;
            Vec3 p = base.add(away.scale(1.2 + h * .25)).add(side.scale(fan * (1 + h * .55))).add(0, h * (1 - .35 * fan * fan), 0);
            level.sendParticles(core, p.x, p.y, p.z, 3, .18, .25, .18, 0);
            if (age % 2 == 0) level.sendParticles(bright, p.x, p.y, p.z, 1, .1, .1, .1, 0);
        }
    }

    // ---------------------------------------------------------------- 姿势、位置

    /** 身体播一段动作（{@code name} 在她的动画文件里），{@code rate} 倍速。 */
    private static void play(ShiraumeCompanion c, ServerLevel level, String name, float rate, boolean loop) {
        c.setMove(name + "|" + level.getGameTime() + "|" + rate + "|" + loop);
    }

    /** 转过去对着 {@code at}，每刻最多 {@code turn} 度（身子、头一起）。 */
    private static void face(ShiraumeCompanion c, Vec3 at, float turn) {
        Vec3 d = at.subtract(c.position());
        if (d.x * d.x + d.z * d.z < 1e-4) return;
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90;
        float now = Mth.approachDegrees(c.getYRot(), yaw, turn);
        c.setYRot(now);
        c.yBodyRot = now;
        c.setYHeadRot(now);
    }

    static Vec3 forward(LivingEntity c) {
        double r = Math.toRadians(c.yBodyRot);
        return new Vec3(-Math.sin(r), 0, Math.cos(r));
    }

    static Vec3 right(LivingEntity c) {
        Vec3 f = forward(c);
        return new Vec3(-f.z, 0, f.x);
    }

    /** 她捻着形代的那只手（胸前偏右一点）。客户端画纸人、牵丝用同一个点（{@link #handOf}）。 */
    static Vec3 hand(LivingEntity c) {
        return handOf(c.position(), forward(c), c.getBbHeight());
    }

    /** {@code feet} 处、面朝 {@code forward}、身高 {@code height} 的她，捻着形代的手在哪。 */
    public static Vec3 handOf(Vec3 feet, Vec3 forward, double height) {
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        return feet.add(forward.scale(.42)).add(right.scale(.16)).add(0, height * .72, 0);
    }

    /** 雨滴落进的那一滩水镜：她脚前一格多。 */
    static Vec3 puddle(LivingEntity c) {
        return puddleOf(c.position(), forward(c));
    }

    public static Vec3 puddleOf(Vec3 feet, Vec3 forward) {
        return feet.add(forward.scale(1.35));
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** 化作狐火闪到目标跟前（{@link #BLINK_TO} 格，她原来那一侧）；那里站不住就左右转着找，都不行就不闪。 */
    private static void blink(ServerLevel level, ShiraumeCompanion c, LivingEntity target) {
        Vec3 back = c.position().subtract(target.position()).multiply(1, 0, 1);
        back = back.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : back.normalize();
        for (int k = 0; k < 7; k++) {
            double turn = Math.toRadians((k + 1) / 2 * 30 * (k % 2 == 0 ? 1 : -1));
            Vec3 dir = new Vec3(back.x * Math.cos(turn) - back.z * Math.sin(turn), 0, back.x * Math.sin(turn) + back.z * Math.cos(turn));
            Vec3 spot = stand(level, c, target.position().add(dir.scale(BLINK_TO)));
            if (spot == null) continue;
            Vec3 from = c.position().add(0, .7, 0);
            c.puff(level, from);
            c.teleportTo(spot.x, spot.y, spot.z);
            c.resetFallDistance();
            c.puff(level, spot.add(0, .7, 0));
            c.blinkSound(level, spot);
            return;
        }
    }

    /** {@code at} 那一列上下两格里站得住的一点：脚下结实、身子不卡进方块、不在水里。 */
    private static @Nullable Vec3 stand(ServerLevel level, ShiraumeCompanion c, Vec3 at) {
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            BlockPos feet = BlockPos.containing(at.x, at.y + .2 + dy, at.z);
            if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) continue;
            if (!level.getFluidState(feet).isEmpty()) continue;
            Vec3 spot = new Vec3(at.x, feet.getY(), at.z);
            if (level.noCollision(c, c.getDimensions(c.getPose()).makeBoundingBox(spot))) return spot;
        }
        return null;
    }

    private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume, pitch);
    }

    // ---------------------------------------------------------------- 狐火蚀骨

    /**
     * 接力馈赠「狐火蚀骨」：主目标身边转着三朵残樱狐印，下一段（白梅最后放时就是玩家的最后一击）打中它时伤害 ×{@link #CORROSION}，
     * 第一下在它身上二次炸开一团淡粉狐火、再缠一身狐火。那一段演完、连携结束、或者印挂满 {@link #MARK_LIFE} 刻，印就散了。
     */
    static final class Corrosion implements RelayGift {
        private final LivingEntity target;
        private boolean burst;

        Corrosion(LivingEntity target) {
            this.target = target;
        }

        @Override
        public void handed(UltimateCast from) {
            mark(target, MARK_LIFE);
        }

        @Override
        public void hit(UltimateCast cast, UltimateHit hit) {
            if (hit.target() != target) return;
            hit.amount(corroded(hit.amount()));
            corrosions++;
            if (burst || !(target.level() instanceof ServerLevel level)) return;
            burst = true;
            Vec3 at = target.getBoundingBox().getCenter();
            FoxfireCue.burst(level, at, FoxfireFlame.SHIRAUME, Math.max(1.4f, target.getBbHeight() * 1.1f));
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 30, .6, .6, .6, .05);
            sound(level, at, SoundEvents.FIRECHARGE_USE, 1, 1.3f);
            sound(level, at, SoundEvents.SOUL_ESCAPE.value(), 1.2f, 1.5f);
            FoxfireRules.burn(target, FoxfireFlame.SHIRAUME, cast.performer(), FoxMarks.BURN_TICKS);
            unmark(target);
        }

        @Override
        public boolean keep(UltimateChain.Stage done) {
            unmark(target);
            return false;
        }

        @Override
        public void cleared(ServerLevel level) {
            unmark(target);
        }

        /** 测试用：二次引爆过没有。 */
        boolean burst() {
            return burst;
        }
    }

    /** 测试用：狐火蚀骨一共加过几下伤害。 */
    private static int corrosions;

    public static int corroded() {
        return corrosions;
    }

    /** 挂着残樱狐印的目标，挂到哪一刻为止（游戏刻）。 */
    private static final Map<LivingEntity, Long> MARKED = new WeakHashMap<>();

    static void mark(LivingEntity target, int ticks) {
        MARKED.put(target, target.level().getGameTime() + ticks);
    }

    static void unmark(LivingEntity target) {
        MARKED.remove(target);
    }

    /** {@code target} 身上挂着残樱狐印（测试用）。 */
    public static boolean marked(LivingEntity target) {
        Long until = MARKED.get(target);
        return until != null && target.isAlive() && target.level().getGameTime() < until;
    }

    /** 残樱狐印：三朵淡粉的光绕着它转，偶尔落一片樱瓣。 */
    @SubscribeEvent
    static void tickMarks(ServerTickEvent.Post event) {
        if (MARKED.isEmpty()) return;
        var dust = new DustParticleOptions(0xFF9EC4, .9f);
        for (var it = MARKED.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            LivingEntity t = e.getKey();
            if (!t.isAlive() || t.isRemoved() || !(t.level() instanceof ServerLevel level) || level.getGameTime() >= e.getValue()) {
                it.remove();
                continue;
            }
            long now = level.getGameTime();
            double radius = t.getBbWidth() * .5 + .45;
            for (int k = 0; k < 3; k++) {
                double a = now * .22 + k * Mth.TWO_PI / 3;
                double y = t.getY() + t.getBbHeight() * .55 + Math.sin(now * .15 + k * 2.1) * .18;
                level.sendParticles(dust, t.getX() + Math.cos(a) * radius, y, t.getZ() + Math.sin(a) * radius, 1, 0, 0, 0, 0);
            }
            if (now % 6 == 0)
                level.sendParticles(ParticleTypes.CHERRY_LEAVES, t.getX(), t.getY() + t.getBbHeight() * .7, t.getZ(), 1, radius * .6, .1, radius * .6, 0);
        }
    }
}
