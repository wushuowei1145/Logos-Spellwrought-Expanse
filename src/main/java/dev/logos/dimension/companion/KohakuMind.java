package dev.logos.dimension.companion;

import dev.logos.lang.Intent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀的心智（docs/KOHAKU_COMPANION.md 第 7 节）：通用的那些在 {@link CompanionMind}，这里是她自己的。
 * <ul>
 *   <li>WARD 琥珀护膜：一打起来先给自己罩上；碎了歇够了再凝。分比迎战高。</li>
 *   <li>迎战（ENGAGE）就是施法：她更喜欢拉开距离——站到主人身后、和敌人隔开，站定了再放；被贴近了先往外退。</li>
 *   <li>HISS 哈气：不参与攻击时她不出手，可敌人冲过来她还是会朝它哈气（{@code act_guard}）。</li>
 *   <li>幻翼：同原版的猫，幻翼靠近她、冲着主人来时她哈一口气，幻翼就不敢扑下来（{@link #scarePhantoms}）。</li>
 *   <li>SUPPORT 支援：一打起来随机放一个支援（守护、强化、治疗，包括你给的这几类页），给你或最需要的那位；有余力时
 *       （魔力五成以上、护膜还在、身边没有冲着她来的）照顾你、你的使魔和宠物、别的同伴：谁伤得重就给谁治疗或护盾。
 *       精灵公主也在外面时，治疗让给公主，她只放护盾。魔力少时不放。</li>
 *   <li>UTILITY 猫眼药剂：你周围暗、又没有夜视时递给你一瓶。</li>
 *   <li>攻击按处境挑页（{@link #castAt}）：强敌先封进琥珀，扎堆的放炼成阵、午睡结界、沸腾烧瓶，不然一滴就够；
 *       每一种意图都先看你给的杖上有没有这一类的页，有就用你的。魔力少时只放一滴就够。</li>
 *   <li>日常（{@link KohakuLife}）：LOUNGE 你站着不动时凑过来陪你（下雨撑伞、在家去她的地毯、晴天晒太阳、不然在你身边坐着），
 *       GIFT 你饿了递给你吃的，FETCH 一场打完把最有意思的掉落叼到你脚边。</li>
 * </ul>
 *
 * <p>行动策略在她身上的意思（第 7.5 节）：随机应变隔开 {@link #KEEP} 格；优先远程隔得更远；优先近战站得近一点（她还是法师，不冲上去）；
 * 不参与攻击不施法，躲到主人背后，护膜照常、敌人近了哈气。
 */
public final class KohakuMind extends CompanionMind {
    /** 和敌人至少隔多远：随机应变、优先远程、优先近战。 */
    static final double KEEP = 8, KEEP_FAR = 12, KEEP_NEAR = 5;
    /** 护膜的分（比迎战、掩护都高）；哈气的分（比躲开高一点）。 */
    static final double WARD_SCORE = .97, HISS_SCORE = .8;
    /** 哈气：敌人离她或主人多近、两次之间隔多久（刻）。 */
    static final double HISS_AT = 9;
    static final int HISS_GAP = 100;
    /** 幻翼：离她多远以内的会被吓走；两次哈气之间隔多久。 */
    static final double PHANTOM_RANGE = 16;
    static final int PHANTOM_GAP = 60;
    /**
     * 站位：在主人斜后方——主人身后、再往一侧偏 {@link #SIDE_OFFSET} 格。一滴就够是直线飞的，站在主人正后方会被主人挡住。
     * 弹道离主人近过 {@link #CLEAR} 格时不放，往旁边挪一步。
     */
    static final double SIDE_OFFSET = 2.5, CLEAR = 1.0;
    /** 敌人近到这里就先退开（之后凝辉在这里接手）；冲着她来的近过「隔开」也退。 */
    static final double CLOSE = 3.5;
    /** 支援的分：开场那一个（比迎战高，比护膜低）；有余力时照顾别人；猫眼药剂（打起来时不比迎战高）。 */
    static final double SUPPORT_SCORE = .9, CARE_SCORE = .6, UTILITY_SCORE = .55, UTILITY_FIGHT_SCORE = .45;
    /** 有余力：魔力至少几成；照顾别人：血低于几成的；走过去够得着的距离。 */
    static final double SPARE_MANA = .5, CARE_BELOW = .6, REACH = 12;
    /** 敌人扎堆：离她在打的那个多近的算一堆；一堆几个算多。 */
    static final double CLUSTER = 3.5;
    static final int CROWD = 3;
    /** 范围攻击（你给的页）落点离你近过这里不放，免得炸到你。 */
    static final double AREA_SAFE = 5;
    /** 没有敌人多久算一场打完（下一场再放开场的支援）。 */
    static final int CALM_RESET = 100;
    /** 凝辉：敌人近到这里就斩（随机应变、优先近战、优先远程）；优先远程时只在猫步还没好时斩。 */
    static final double SWEEP_AT = 3, SWEEP_AT_MELEE = 4.5, SWEEP_AT_RANGED = 2.5;
    /** 猫步、两个人的猫步的分（比护膜高：被围了先脱身）。 */
    static final double BLINK_SCORE = .99, CARRY_SCORE = .98;
    /** 被包围：几格内有几个敌人；护膜碎了时近一点的两个也算。 */
    static final double SURROUND = 4, SURROUND_CLOSE = 2.5;
    /** 两个人的猫步：你血少于几成、身边几格内有两个以上敌人。 */
    static final float CARRY_BELOW = .25f;
    static final double CARRY_NEAR = 5;
    /**
     * 闲着陪你的分：在家去地毯（要比「离主人远了就跟上」高）、撑伞和晒太阳、在你身边打盹（比挂机时的休息高）、在你身边坐着
     * （比跟着站在你身边多一点，跟随正在做时还有 {@link #HYSTERESIS}）；附近有掉落要捡时先捡（{@link #LOUNGE_AFTER_LOOT}）。
     */
    static final double LOUNGE_RUG = .75, LOUNGE_NEAR = .5, LOUNGE_NAP = .55, LOUNGE_SIT = .45, LOUNGE_AFTER_LOOT = .3;
    /**
     * 夜里跟你睡、赖床（这时通用的「主人睡了就休息」记 0：不然哪一次打分她的这一晚没排上——附近一分钟内的掉落把分压下去、床边一时没地方——
     * 就先进了休息，之后休息带着滞后 1.1 一直赢她的 1.05，整晚只是在你身边蜷着，没有赖床、「大」字、礼物）；你挂机时去床上打盹（比在你身边打盹高一点）。
     */
    static final double LOUNGE_NIGHT = 1.05, LOUNGE_BED = .6;
    /** 给你吃的、叼战利品（叼在嘴里时更急）。 */
    static final double GIFT_SCORE = .8, FETCH_SCORE = .5, FETCH_CARRY = .8;
    /** 测试用：哈过几次气、吓走过几次幻翼。 */
    static int hisses, phantomsScared;

    private final KohakuCompanion c;
    private long nextHiss, nextPhantomHiss;
    /** 站在主人哪一侧（1 或 -1，进入战斗时按她当时在哪边定）。 */
    private int side = 1;
    private boolean sideChosen;
    /** 这一场开场的支援放过了没有；两个人的猫步用过了没有（一场一次）；最近一次有敌人是哪一刻。 */
    private boolean opened, carried;
    private long lastFoe = -10_000;
    /** 打算放的那一个支援（或猫眼药剂）：她的页或你的页、给谁、落在哪。 */
    private record Plan(@Nullable KohakuSpells own, KohakuSpells.@Nullable Yours yours, LivingEntity target, Vec3 at, boolean opening) {}

    private @Nullable Plan plan;

    KohakuMind(KohakuCompanion c) {
        super(c);
        this.c = c;
    }

    @Override
    protected boolean engagedOwn(Kind k) {
        return k == Kind.WARD || k == Kind.BLINK || k == Kind.CARRY || k == Kind.SUPPORT && foe != null;
    }

    /** 撤退、法阵还在转开、正在凝辉横扫或猫步、正在起身或递东西时什么都不做（动作自己走完）。 */
    @Override
    protected boolean busy(ServerLevel level, ServerPlayer p, long now) {
        if (!c.withdrawing() && !c.inCircle() && !c.moves.busy() && !c.life.busy(now)) return false;
        c.getNavigation().stop();
        return true;
    }

    @Override
    protected void beforeScore(ServerLevel level, ServerPlayer p, long now) {
        if (foe != null) lastFoe = now;
        else if (now - lastFoe > CALM_RESET) {
            opened = false;
            carried = false;
        }
        scarePhantoms(level, p, now);
        c.life.watchFight(level, p, now, foe != null);
        c.life.rollFood(level, p, now, foe == null);
    }

    // ---------------------------------------------------------------- 打分

    @Override
    protected void scoreOwn(ServerLevel level, ServerPlayer p, OwnerWatch w, long now, Situation s) {
        KohakuMagic m = c.magic;
        // 护膜：有敌人就先罩上（不参与攻击也罩，那不是攻击）
        boolean wardCasting = m.casting() && current == Kind.WARD;
        scores.put(Kind.WARD, wardCasting ? 1.1 : foe != null && m.wardReady(now) ? WARD_SCORE : 0);
        // 哈气：不出手时，敌人冲到她或主人跟前
        boolean hissing = current == Kind.HISS && "act_guard".equals(c.mood.playing());
        boolean near = foe != null && (foe.distanceTo(c) < HISS_AT || foe.distanceTo(p) < HISS_AT);
        scores.put(Kind.HISS, hissing ? 1 : s.holdBack() && near && now >= nextHiss ? HISS_SCORE : 0);
        // 支援：正在放的就放完；开场随机一个；有余力时照顾伤得重的
        boolean supporting = m.casting() && (current == Kind.SUPPORT || current == Kind.UTILITY);
        if (!supporting) plan = null;
        double support = 0, utility = 0;
        if (supporting) {
            support = current == Kind.SUPPORT ? 1.05 : 0;
            utility = current == Kind.UTILITY ? 1.05 : 0;
        } else if (!m.low() && !m.casting()) {
            if (foe != null && !opened) {
                plan = opening(level, p, now);
                if (plan == null) opened = true;
                else support = SUPPORT_SCORE;
            }
            if (plan == null && spare(level, now)) {
                plan = care(level, p, now);
                if (plan != null) support = CARE_SCORE;
            }
            if (plan == null) {
                plan = catEye(level, p, now);
                if (plan != null) utility = foe == null ? UTILITY_SCORE : UTILITY_FIGHT_SCORE;
            }
        }
        scores.put(Kind.SUPPORT, support);
        scores.put(Kind.UTILITY, utility);
        // 猫步：被包围了先脱身；两个人的猫步：你快撑不住、身边又围着敌人
        List<Mob> close = level.getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(SURROUND), e -> e.isAlive() && threatens(e)
                && e.distanceTo(c) <= SURROUND);
        long tight = close.stream().filter(e -> e.distanceTo(c) <= SURROUND_CLOSE).count();
        boolean surrounded = close.size() >= 3 || tight >= 2 && !m.wardUp();
        scores.put(Kind.BLINK, surrounded && c.moves.blinkReady(now) && c.available() >= KohakuMoves.BLINK_MANA ? BLINK_SCORE : 0);
        boolean carry = !carried && KohakuSpells.TWO_STEP.unlocked(c.seals()) && p.getHealth() < p.getMaxHealth() * CARRY_BELOW
                && p.distanceTo(c) < 16 && c.available() >= KohakuMoves.CARRY_MANA && !c.moves.busy()
                && level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(CARRY_NEAR), e -> e.isAlive() && threatens(e)).size() >= 2;
        scores.put(Kind.CARRY, carry ? CARRY_SCORE : 0);
        // 日常：没在打的时候
        KohakuLife life = c.life;
        KohakuLife.Plan lounge = life.choose(level, p, w, now, s.calm(), s.order());
        double loungeScore = lounge == null ? 0 : switch (lounge.spot()) {
            case NIGHT -> LOUNGE_NIGHT;
            case BED -> LOUNGE_BED;
            case RUG -> LOUNGE_RUG;
            case UMBRELLA, SUN -> LOUNGE_NEAR;
            case BESIDE -> lounge.nap() ? LOUNGE_NAP : LOUNGE_SIT;
        };
        boolean night = lounge != null && lounge.spot() == KohakuLife.Spot.NIGHT;
        // 还没坐下、附近有掉落：先捡完（你睡下了就不捡）
        if (!night && !life.posed() && scores.getOrDefault(Kind.SCAVENGE, 0.0) > 0) loungeScore = Math.min(loungeScore, LOUNGE_AFTER_LOOT);
        scores.put(Kind.LOUNGE, loungeScore);
        if (night) scores.put(Kind.REST, 0.0);
        scores.put(Kind.GIFT, s.calm() && life.giftPending() ? GIFT_SCORE : 0);
        scores.put(Kind.FETCH, s.calm() && life.fetching() ? life.inHand().isEmpty() ? FETCH_SCORE : FETCH_CARRY : 0);
    }

    /** 她当作威胁的：她能伤的、敌对生物或冲着她和主人来的。 */
    private boolean threatens(Mob e) {
        return c.canHarm(e) && (e instanceof Enemy || c.hostileTo(e));
    }

    /** 猫步：找落点（开阔、背离敌群、往主人那边偏、看得见主人），蹲身一跃闪过去。找不到就照常打。 */
    private void blink(ServerLevel level, ServerPlayer p, long now) {
        List<Mob> foes = level.getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(8), e -> e.isAlive() && threatens(e));
        Vec3 to = KohakuMoves.openGround(level, c, p, foes);
        if (to == null || !c.moves.startBlink(to, false, now)) {
            if (foe != null) fight(level, foe, now, false);
        }
    }

    /** 两个人的猫步：给主人找落点，拉着主人一起闪过去。一场一次。 */
    private void carry(ServerLevel level, ServerPlayer p, long now) {
        List<Mob> foes = level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(8), e -> e.isAlive() && threatens(e));
        Vec3 to = KohakuMoves.openGround(level, p, p, foes);
        if (to != null && c.moves.startBlink(to, true, now)) carried = true;
    }

    // ---------------------------------------------------------------- 支援

    /** 有余力（第 7.3 节）：魔力（身上的加杖里存的）五成以上、护膜还在（打着的时候）、6 格内没有冲着她来的敌人。 */
    private boolean spare(ServerLevel level, long now) {
        if (c.available() < c.maxMana() * SPARE_MANA || !c.magic.wardUp() && foe != null) return false;
        return level.getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(6), m -> m.isAlive() && m.getTarget() == c).isEmpty();
    }

    /** 精灵公主也在外面：治疗让给她。 */
    private boolean princessOut(ServerPlayer p) {
        return Companions.princess(p) != null;
    }

    /** 身边血最少、低于 {@code below} 成的同伴（她自己只在别人都不急时才排上）。 */
    private @Nullable LivingEntity wounded(ServerLevel level, ServerPlayer p, double below) {
        LivingEntity best = null;
        double worst = below;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(REACH + 4), e -> KohakuMagic.ally(e, p, c))) {
            double f = e.getHealth() / Math.max(1, e.getMaxHealth()) + (e == c ? .15 : 0);
            if (f < worst) {
                worst = f;
                best = e;
            }
        }
        return best;
    }

    /**
     * 开场的支援（维护者定的：随机一个）：她会的守护、强化、治疗页和你给的这几类页里随机挑一页。守护给你（伞罩住你身边的同伴），
     * 强化给你（你手上已经缠着东西时不给），治疗给最需要的那位（都满血时不算）。
     */
    private @Nullable Plan opening(ServerLevel level, ServerPlayer p, long now) {
        List<Plan> options = new ArrayList<>();
        LivingEntity hurt = princessOut(p) ? null : wounded(level, p, .85);
        for (KohakuSpells.Yours y : yoursPages()) {
            if (!c.magic.ready(y, now)) continue;
            LivingEntity t = y.aim() == KohakuSpells.Aim.SELF ? selfTarget(y.intent()) : supportTarget(y.intent(), p, hurt);
            if (t != null) options.add(new Plan(null, y, t, t.position(), true));
        }
        for (KohakuSpells s : List.of(KohakuSpells.MISTY_UMBRELLA, KohakuSpells.QUICK_ENCHANT, KohakuSpells.GREENHOUSE)) {
            if (!c.magic.usable(s, now)) continue;
            LivingEntity t = supportTarget(s.intent(), p, hurt);
            if (t != null) options.add(new Plan(s, null, t, t.position(), true));
        }
        return options.isEmpty() ? null : options.get(c.getRandom().nextInt(options.size()));
    }

    /** 只作用自己的那一页（你写给自己的守护、治疗）：守护在她护膜碎了时，治疗在她伤着时，给她自己。 */
    private @Nullable LivingEntity selfTarget(Intent intent) {
        return switch (intent) {
            case WARD -> c.magic.wardUp() ? null : c;
            case HEAL -> c.getHealth() < c.getMaxHealth() * .85f ? c : null;
            default -> null;
        };
    }

    /** 这一类支援给谁：守护、强化给你（强化在你手上空着时），治疗给伤着的那位。 */
    private @Nullable LivingEntity supportTarget(Intent intent, ServerPlayer p, @Nullable LivingEntity hurt) {
        return switch (intent) {
            case WARD -> p;
            case EMPOWER -> PrincessMagic.wrapped(p) ? null : p;
            case HEAL -> hurt;
            default -> null;
        };
    }

    /** 有余力时照顾伤得重的那位（六成血以下）：先治疗（公主在外面时不治），没有治疗页就给护盾。你给的页先。 */
    private @Nullable Plan care(ServerLevel level, ServerPlayer p, long now) {
        LivingEntity t = wounded(level, p, CARE_BELOW);
        if (t == null) return null;
        boolean heal = !princessOut(p);
        for (Intent want : heal ? List.of(Intent.HEAL, Intent.WARD) : List.of(Intent.WARD)) {
            for (KohakuSpells.Yours y : yoursPages()) {
                if (y.intent() != want || !c.magic.ready(y, now)) continue;
                if (y.aim() == KohakuSpells.Aim.SELF && t != c) continue;
                return new Plan(null, y, t, t.position(), false);
            }
            KohakuSpells own = want == Intent.HEAL ? KohakuSpells.GREENHOUSE : KohakuSpells.MISTY_UMBRELLA;
            if (c.magic.usable(own, now)) return new Plan(own, null, t, t.position(), false);
        }
        return null;
    }

    /** 猫眼药剂：你周围暗（亮度不到 6）、又没有夜视时递给你一瓶。 */
    private @Nullable Plan catEye(ServerLevel level, ServerPlayer p, long now) {
        if (!c.magic.usable(KohakuSpells.CAT_EYE, now) || p.hasEffect(MobEffects.NIGHT_VISION)) return null;
        if (level.getMaxLocalRawBrightness(BlockPos.containing(p.getEyePosition())) >= 6) return null;
        return new Plan(KohakuSpells.CAT_EYE, null, p, p.position(), false);
    }

    /** 走到够得着的地方（{@link #REACH} 格内、看得见），朝那位拄杖放出去。 */
    private void support(long now) {
        if (c.magic.casting()) {
            c.getNavigation().stop();
            return;
        }
        Plan pl = plan;
        if (pl == null || !pl.target().isAlive()) return;
        LivingEntity t = pl.target();
        if (t != c && (c.distanceTo(t) > REACH || !c.hasLineOfSight(t))) {
            if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(t, 1.3);
            return;
        }
        c.getNavigation().stop();
        if (t != c) face(t);
        Vec3 at = pl.target().position();
        boolean ok = pl.own() != null ? c.magic.startPage(pl.own(), t, at, now) : pl.yours() != null && c.magic.startYours(pl.yours(), t, at, now);
        if (ok && pl.opening()) opened = true;
        if (!ok) plan = null;
    }

    /** 你给她的那根杖上她能放的页（不是你给的杖时没有）。 */
    private List<KohakuSpells.Yours> yoursPages() {
        return c.staff.yours() ? c.staff.yoursPages() : List.of();
    }

    @Override
    protected void afterThink(ServerLevel level, ServerPlayer p, long now) {
        if (engaged() || current == Kind.RETREAT || current == Kind.HISS) c.fought(now);
    }

    @Override
    protected boolean canTurnToTalk() {
        return !c.magic.casting() && !c.moves.busy();
    }

    /** 坐着、撑着伞时你和她说话：她不站起来，就这样说（打着盹时先醒过来）。 */
    @Override
    protected boolean talkBlocked() {
        return c.life.posed() && !c.life.napping();
    }

    // ---------------------------------------------------------------- 做

    @Override
    protected void actOwn(Kind kind, ServerLevel level, ServerPlayer p, long now) {
        switch (kind) {
            case WARD -> {
                c.getNavigation().stop();
                c.setSprinting(false);
                if (foe != null) c.getLookControl().setLookAt(foe, 30, 30);
                if (!c.magic.casting()) c.magic.startWard(now);
            }
            case HISS -> hiss(level, now);
            case SUPPORT, UTILITY -> support(now);
            case BLINK -> blink(level, p, now);
            case CARRY -> carry(level, p, now);
            case LOUNGE -> c.life.lounge(level, p, now);
            case GIFT -> c.life.gift(p, now);
            case FETCH -> c.life.fetch(level, p, now);
            default -> idle(p, now, true);
        }
    }

    /** 朝冲过来的敌人哈一口气：站住、转过去、耳朵压平、尾巴炸开（模型的 {@code act_guard}）。 */
    private void hiss(ServerLevel level, long now) {
        c.getNavigation().stop();
        c.setSprinting(false);
        if (foe == null) return;
        face(foe);
        if (now < nextHiss) return;
        nextHiss = now + HISS_GAP;
        c.mood.act("act_guard");
        level.playSound(null, c.getX(), c.getY(), c.getZ(), KohakuCompanion.cat().hissSound().value(), SoundSource.NEUTRAL, .9f, 1.15f);
        hisses++;
    }

    /**
     * 幻翼（同原版的猫）：她身边 {@link #PHANTOM_RANGE} 格内的幻翼，冲着主人、她或附近的人来的，一律不敢扑下来——
     * 丢掉目标、往上飞开。她看见了就朝它哈一口气（隔一阵一次）。
     */
    private void scarePhantoms(ServerLevel level, ServerPlayer p, long now) {
        boolean any = false;
        Phantom near = null;
        for (Phantom ph : level.getEntitiesOfClass(Phantom.class, c.getBoundingBox().inflate(PHANTOM_RANGE), Phantom::isAlive)) {
            LivingEntity t = ph.getTarget();
            if (t != null && t != p && t != c && t.distanceTo(c) > PHANTOM_RANGE) continue;
            if (t != null) {
                ph.setTarget(null);
                Vec3 away = ph.position().subtract(c.position()).multiply(1, 0, 1);
                away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
                ph.setDeltaMovement(away.scale(.5).add(0, .6, 0));
                ph.hurtMarked = true;
                phantomsScared++;
            }
            any = true;
            if (near == null || ph.distanceToSqr(c) < near.distanceToSqr(c)) near = ph;
        }
        if (!any || now < nextPhantomHiss || c.magic.casting() || c.eating()) return;
        nextPhantomHiss = now + PHANTOM_GAP;
        if (!c.mood.acting()) c.mood.act("act_guard");
        c.getLookControl().setLookAt(near, 40, 60);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), KohakuCompanion.cat().hissSound().value(), SoundSource.NEUTRAL, 1, 1.1f);
        hisses++;
    }

    /**
     * 迎战就是施法：放着术式时站住；离敌人近过「隔开」就往外退（退向主人那边）；不然先站到主人身后，站定了再放。
     * {@code hold} 时站在原地（护主）。
     */
    @Override
    protected void fight(ServerLevel level, LivingEntity target, long now, boolean hold) {
        c.fought(now);
        KohakuMagic m = c.magic;
        if (m.casting()) {
            c.getNavigation().stop();
            return;
        }
        double d = c.distanceTo(target);
        if (!hold) {
            double keep = tactic == CompanionTactic.RANGED ? KEEP_FAR : tactic == CompanionTactic.MELEE ? KEEP_NEAR : KEEP;
            // 贴到跟前：凝出凝辉横扫一圈再往后跳（不参与攻击时不斩；优先远程时只在猫步还没好时斩）
            double sweepAt = tactic == CompanionTactic.MELEE ? SWEEP_AT_MELEE : tactic == CompanionTactic.RANGED ? SWEEP_AT_RANGED : SWEEP_AT;
            if (d < sweepAt && !holdsBack() && (tactic != CompanionTactic.RANGED || !c.moves.blinkReady(now)) && c.hasLineOfSight(target)
                    && c.moves.startSweep(target, now)) return;
            // 被贴脸、或者冲着她来的近过「隔开」：先往外退；冲主人去的不退，站定了照打
            boolean atMe = target instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == c;
            if (d < CLOSE || atMe && d < keep - 1.5) {
                backOff(target, now);
                return;
            }
            if (owner != null && d >= keep - 1.5) {
                if (!sideChosen) {
                    side = sideOf(owner, target, c.position());
                    sideChosen = true;
                }
                Vec3 spot = castSpot(owner, target, keep, side);
                if (c.position().distanceTo(spot) > PrincessMind.SUPPORT_SLACK) {
                    if (now % THINK == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.3);
                    c.setSprinting(c.position().distanceTo(spot) > 8);
                    c.getLookControl().setLookAt(target, 40, 40);
                    return;
                }
            }
        }
        c.getNavigation().stop();
        c.setSprinting(false);
        c.getLookControl().setLookAt(target, 40, 40);
        castAt(target, now);
    }

    /** 往外退开一点（退向主人那边），边退边看着它。 */
    private void backOff(LivingEntity target, long now) {
        Vec3 away = c.position().subtract(target.position()).multiply(1, 0, 1);
        away = away.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, c.getYRot() + 180) : away.normalize();
        Vec3 to = c.position().add(away.scale(4));
        if (owner != null && to.distanceTo(owner.position()) > 10) to = owner.position();
        if (now % 5 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(to.x, to.y, to.z, 1.35);
        c.setSprinting(true);
        c.getLookControl().setLookAt(target, 40, 40);
    }

    /**
     * 按处境挑一页打 {@code target}（第 5、7 节）：
     * <ol>
     *   <li>魔力少：只放一滴就够；</li>
     *   <li>强敌：先封进琥珀（控制）；</li>
     *   <li>强敌或扎堆三个以上：炼成阵·星辰花（范围），再是午睡结界（控制）；</li>
     *   <li>扎堆两个以上：沸腾烧瓶（范围）；</li>
     *   <li>不然一滴就够（单体）。</li>
     * </ol>
     * 每一步都先看你给的杖上有没有这一类的页，有就用你的（范围页落点离你太近时不放你的）。直线飞的弹道上有主人就往旁边挪一步。
     */
    private void castAt(LivingEntity target, long now) {
        if (!c.hasLineOfSight(target) || c.distanceTo(target) > KohakuMagic.RANGE || c.magic.casting()) return;
        KohakuMagic m = c.magic;
        boolean clear = owner == null || clearShot(c, owner, target);
        if (m.low()) {
            if (clear) m.startPage(KohakuSpells.ONE_DROP, target, now);
            else sidestep(target);
            return;
        }
        List<Mob> crowd = c.level().getEntitiesOfClass(Mob.class, target.getBoundingBox().inflate(CLUSTER),
                e -> e.isAlive() && c.canHarm(e) && (e instanceof Enemy || e == target || c.hostileTo(e)));
        Vec3 center = target.position();
        if (crowd.size() > 1) {
            Vec3 sum = Vec3.ZERO;
            for (Mob e : crowd) sum = sum.add(e.position());
            center = sum.scale(1.0 / crowd.size());
        }
        boolean strong = PrincessMagic.strong(target, threats);
        if (strong && (tryYours(Intent.CONTROL, target, center, now, clear) || m.startPage(KohakuSpells.TIME_AMBER, target, now))) return;
        if ((strong || crowd.size() >= CROWD) && (tryYours(Intent.AREA, target, center, now, clear)
                || m.startPage(KohakuSpells.STARBLOOM, target, center, now) || m.startPage(KohakuSpells.NAP_WARD, target, center, now))) return;
        if (crowd.size() >= 2 && m.startPage(KohakuSpells.BOILING_FLASK, target, center, now)) return;
        if (tryYours(Intent.STRIKE, target, center, now, clear)) return;
        if (!clear) {
            sidestep(target);
            return;
        }
        m.startPage(KohakuSpells.ONE_DROP, target, now);
    }

    /** 你给的页里有没有 {@code intent} 这一类、冷却好了的，有就放（直线的要弹道干净，范围的落点要离你够远）。 */
    private boolean tryYours(Intent intent, LivingEntity target, Vec3 center, long now, boolean clear) {
        for (KohakuSpells.Yours y : yoursPages()) {
            if (y.intent() != intent || !c.magic.ready(y, now)) continue;
            if (y.aim() == KohakuSpells.Aim.FOE && !clear) continue;
            if (y.aim() == KohakuSpells.Aim.CLUSTER && owner != null && center.distanceTo(owner.position()) < AREA_SAFE) continue;
            if (c.magic.startYours(y, target, y.aim() == KohakuSpells.Aim.CLUSTER ? center : null, now)) return true;
        }
        return false;
    }

    /** 弹道上有主人：往旁边挪一步。 */
    private void sidestep(LivingEntity target) {
        Vec3 line = target.position().subtract(c.position()).multiply(1, 0, 1);
        Vec3 perp = line.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : new Vec3(-line.z, 0, line.x).normalize();
        Vec3 to = c.position().add(perp.scale(side * 2));
        c.getNavigation().moveTo(to.x, to.y, to.z, 1.2);
    }

    /**
     * 要对付的那一个：通用的那些之外，有余力时她也去打围着你的使魔、宠物、别的同伴的（第 7.3 节：谁被围就去打围着谁的）。
     */
    @Override
    protected @Nullable LivingEntity pickFoe(ServerLevel level, ServerPlayer p, OwnerWatch w, long now) {
        LivingEntity best = super.pickFoe(level, p, w, now);
        if (best != null || c.staying() || tactic == CompanionTactic.PASSIVE) return best;
        Mob pick = null;
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(16), m -> m.isAlive() && c.canHarm(m) && m.getTarget() != null
                && m.getTarget() != p && KohakuMagic.ally(m.getTarget(), p, c))) {
            if (pick == null || m.distanceToSqr(c) < pick.distanceToSqr(c)) pick = m;
        }
        return pick;
    }

    /** 后撤（血少了，或不参与攻击时敌人近了）：退到主人身后；站好了、没在躲的话照常施法。 */
    @Override
    protected void retreat(ServerPlayer p, LivingEntity threat, long now) {
        if (!holdsBack() && c.distanceTo(threat) < SWEEP_AT && c.moves.startSweep(threat, now)) return;
        if (c.magic.casting()) {
            c.getNavigation().stop();
            return;
        }
        Vec3 back = p.position().subtract(threat.position()).multiply(1, 0, 1);
        Vec3 spot = p.position().add(back.lengthSqr() < 1e-4 ? Vec3.ZERO : back.normalize().scale(2.5));
        if (c.position().distanceTo(spot) > 1.2) {
            if (now % THINK == 0) c.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.4);
            c.setSprinting(true);
            c.getLookControl().setLookAt(threat, 30, 30);
            return;
        }
        c.getNavigation().stop();
        c.setSprinting(false);
        c.getLookControl().setLookAt(threat, 30, 30);
        if (!holdsBack()) castAt(threat, now);
    }

    /** 打仗时她站的地方：主人身后（{@link PrincessMind#supportSpot}），再往 {@code side} 那一侧偏 {@link #SIDE_OFFSET} 格。 */
    static Vec3 castSpot(LivingEntity owner, LivingEntity foe, double keep, int side) {
        Vec3 base = PrincessMind.supportSpot(owner, foe, keep);
        Vec3 line = owner.position().subtract(foe.position()).multiply(1, 0, 1);
        Vec3 dir = line.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : line.normalize();
        return base.add(new Vec3(-dir.z, 0, dir.x).scale(side * SIDE_OFFSET));
    }

    /** {@code at} 在主人到敌人那条线的哪一侧（1 或 -1）。 */
    static int sideOf(LivingEntity owner, LivingEntity foe, Vec3 at) {
        Vec3 dir = owner.position().subtract(foe.position()), to = at.subtract(owner.position());
        return dir.x * to.z - dir.z * to.x >= 0 ? 1 : -1;
    }

    /** 从她眼睛到 {@code t} 身子中间那一段，离主人身子中间远过 {@link #CLEAR} 格。 */
    static boolean clearShot(LivingEntity c, LivingEntity owner, LivingEntity t) {
        Vec3 a = c.getEyePosition(), b = t.position().add(0, t.getBbHeight() * .55, 0), o = owner.position().add(0, owner.getBbHeight() * .5, 0);
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1e-6) return true;
        double k = Mth.clamp(o.subtract(a).dot(ab) / len2, 0, 1);
        return a.add(ab.scale(k)).distanceTo(o) > CLEAR;
    }

    @Override
    protected void switched(Kind from, Kind to, long now) {
        // 打完一仗再打下一仗时，重新按她在哪边定站位
        if (to != Kind.ENGAGE && to != Kind.WARD) sideChosen = false;
        if (!(c.level() instanceof ServerLevel level)) return;
        // 不陪你了就起来（来了敌人时直接站起来）；来了敌人时手里要递的先放下
        if (from == Kind.LOUNGE) c.life.rise(level, now, foe != null);
        if ((from == Kind.GIFT || from == Kind.FETCH) && foe != null) c.life.dropHand(level);
    }

    /** 转过去正对着 {@code t} 的身子中间（放弹射物前：她的视线就是术式的方向）。 */
    static void faceExactly(KohakuCompanion c, LivingEntity t) {
        Vec3 eye = c.getEyePosition(), at = t.position().add(0, t.getBbHeight() * .55, 0);
        Vec3 d = at.subtract(eye);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90, pitch = (float) -(Mth.atan2(d.y, h) * Mth.RAD_TO_DEG);
        c.setYRot(yaw);
        c.setXRot(pitch);
        c.yBodyRot = yaw;
        c.setYHeadRot(yaw);
    }

    @Override
    protected void describeOwn(StringBuilder sb) {
        long now = c.level().getGameTime();
        sb.append(" cast=").append(c.magic.describe(now)).append(" moves=").append(c.moves.describe(now)).append(" life=").append(c.life.describe()).append(String.format(" power=%.2f mana=%.0f/%.0f store=%.0f staff=%s",
                c.spellPower(), c.mana(), c.maxMana(), c.staff.store(), c.staff.held().getItem()));
    }
}
