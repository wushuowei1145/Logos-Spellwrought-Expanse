package dev.logos.dimension.companion;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔的表情和小动作（docs/LACERTA_COMPANION.md 第 10 节；动画设计见模型的 tools/DESIGN14.md）。这里只决定什么时候用哪一个：
 * <ul>
 *   <li>两个声部：独处、对敌是凛（{@code face_poise} 的自信浅笑、{@code face_battle} 的竖瞳，独处的小动作都帅）；对着你是端着再破功
 *       （被看着 {@code face_aloof}，看久了抓住自己乱摇的尾巴 {@code act_tail_grab}）。</li>
 *   <li>挨打那一刻就切到 {@code face_battle}（0.5 + 1.5 秒），掐掉正在演的互动小动作（第 14 版评审：不然会顶着笑眼去格挡）。</li>
 *   <li>打赢一场硬仗：叉腰亮翅等夸（{@code act_doya}），3 秒内摸头就绷不住（{@code act_praised}），没人理就鼓嘴喷烟（{@code face_pout}）。</li>
 *   <li>摸头：「信赖」以前 {@code act_petted}，「信赖」起不演了，双翼把你圈进怀里（{@code act_snuggle}）；平时的脸也换成 {@code face_sweet}。</li>
 *   <li>睡醒：共枕的那一晚先恋恋不舍地起来（{@code sleep_together_up}），然后伸个懒腰（{@code act_stretch}），之后一阵子是困的；守夜的那一晚她没睡，不伸懒腰。</li>
 *   <li>闲着：望远方（{@code act_watch}）、晴天晒翅（{@code act_wingspan}）、夜里掌心燃一簇火（{@code act_ember}）、甩披风（{@code act_wing_cape}）、
 *       附近有敌意还没打起来时拽手套（{@code act_glove_tug}）、酣战之后尾刃挽花（{@code act_tail_flourish}）；坐着时不插小动作；翅膀收着时不插大幅动翼的。</li>
 *   <li>阶段 D：营火、烟熏炉、熔炉烧着或你举着吃的，闻着味凑过来（{@code act_sniff} + {@code face_foodie}）；冷的时候 {@code face_sad} 和
 *       {@code face_pout} 轮着来；你当着她的面摸、喂别的同伴，偶尔撇开脸哼一声（{@code act_hmph} + {@code face_pout}），摸摸她、喂她就好；
 *       你平地摔一跤（没伤着），先忍不住得意地笑半秒，再歪头看你没事吧（{@code act_head_tilt}）。</li>
 *   <li>巨龙形态（模型 {@code lacerta_dragon_beast}，没有表情）：被摸 {@code petted}（低沉的呼噜、冒爱心）、等夸时被摸 {@code act_praised}、
 *       打赢了振翅 {@code wing_flap}、被盯着先 {@code act_hmph} 再 {@code act_tail_grab}、出水抖翅 {@code act_wing_shake}、闲着用鼻吻蹭你
 *       {@code act_sniff_nudge}、伸懒腰 {@code act_stretch}。</li>
 * </ul>
 */
final class LacertaMood extends CompanionMood {
    /** 每段 misc 动画的长度（刻），与动画文件一致（单元测试对过）。 */
    static final Map<String, Integer> LENGTH = Map.ofEntries(Map.entry("summon", 74), Map.entry("death", 116), Map.entry("act_wave", 44),
            Map.entry("act_head_tilt", 40), Map.entry("act_praised", 68), Map.entry("act_petted", 68), Map.entry("act_snuggle", 72),
            Map.entry("act_hmph", 56), Map.entry("act_tail_grab", 80), Map.entry("act_doya", 64), Map.entry("act_sniff", 56), Map.entry("act_oath", 92),
            Map.entry("act_salute", 32), Map.entry("eat", 48), Map.entry("refuse", 32), Map.entry("pick", 26), Map.entry("act_wing_cape", 56),
            Map.entry("act_wingspan", 72), Map.entry("act_watch", 84), Map.entry("act_tail_flourish", 56), Map.entry("act_glove_tug", 48),
            Map.entry("act_guard", 52), Map.entry("act_shake_off", 44), Map.entry("act_ember", 64), Map.entry("act_stretch", 72),
            Map.entry("sleep_together_down", 52), Map.entry("sleep_together_up", 64), Map.entry("taunt", 24), Map.entry("flame_throw", 18),
            Map.entry("catch_hand", 56), Map.entry("catch_embrace", 64));
    /** 巨龙形态那几段的长度（刻），与 {@code lacerta_dragon_beast} 的动画文件一致（单元测试对过）。 */
    static final Map<String, Integer> DRAGON_LENGTH = Map.ofEntries(Map.entry("act_hmph", 56), Map.entry("act_tail_grab", 72),
            Map.entry("act_praised", 60), Map.entry("act_stretch", 68), Map.entry("act_wing_shake", 40), Map.entry("act_sniff_nudge", 56),
            Map.entry("petted", 52), Map.entry("wing_flap", 32));
    /** 对着你的小动作：挨打时掐掉。 */
    static final Set<String> INTERACT = Set.of("act_wave", "act_head_tilt", "act_praised", "act_petted", "act_snuggle", "act_hmph", "act_tail_grab",
            "act_doya", "act_sniff", "refuse", "act_salute", "petted", "act_sniff_nudge");
    /** 大幅动翼的小动作：翅膀收着时不插。 */
    static final Set<String> WING_ACTS = Set.of("act_wing_cape", "act_wingspan", "act_shake_off", "act_stretch");
    /** 挨打以后 face_battle 多久（受击 0.5 秒 + 1.5 秒）；打赢后等夸多久；被看着多久算看久了、看久了多久抓一次尾巴。 */
    static final int BATTLE = 40, PRAISE_WINDOW = 60, LOOKED_LONG = 120, TAIL_GRAB_GAP = 1200;
    /** 一阵没事干：你不动多久她开始自己做点什么；闲着的小动作隔多久。 */
    static final int IDLE_AFTER = 400, IDLE_GAP = 900;
    /** 睡醒以后困多久。 */
    static final int SLEEPY = 600;
    /** 吃醋：几率、两次之间至少多久；闻到炊烟后馋多久。 */
    static final float JEALOUS = .5f;
    static final int JEALOUS_GAP = 2400, CRAVE = 160;
    /** 你摔一跤她先笑多久。 */
    static final int SNICKER = 12;
    /** 测试用：吃过几次醋；必定吃醋（不掷几率）。 */
    static int jealousies;
    static boolean alwaysJealous;

    private long battleUntil = -10_000, surprisedUntil = -10_000, sleepyUntil = -10_000, doyaAt = -10_000, foodieUntil = -10_000;
    private long nextIdleAct, nextTailGrab, nextSniff, nextGloveTug, strongKillSeen, farStaySince = -1;
    private long nextJealous, fallSeen, snickerUntil = -10_000, cookLooked = -1000;
    private boolean cookNear, hmphedAtLook;
    private int lookedAt;
    private boolean wet, stretchAfter, skipWake;

    LacertaMood(CompanionEntity c) {
        super(c, LENGTH);
    }

    private LacertaCompanion lacerta() {
        return (LacertaCompanion) c;
    }

    private boolean dragon() {
        return lacerta().isDragonForm();
    }

    /** 巨龙形态时那几段用巨龙模型的长度。 */
    @Override
    int length(String name) {
        Integer t = dragon() ? DRAGON_LENGTH.get(name) : null;
        return t != null ? t : super.length(name);
    }

    /** 闻到了吃的、正馋着（心智让她凑到你跟前）。 */
    boolean craving() {
        return now() < foodieUntil;
    }

    private CompanionBond.Stage stage(ServerPlayer owner) {
        return CompanionBond.Stage.of(c.bond(owner).affection());
    }

    // ---------------------------------------------------------------- 事情

    /** 挨打了：立刻切竖瞳的战斗脸，掐掉对着你的小动作。 */
    void hit(long now) {
        battleUntil = now + BATTLE;
        String a = playing();
        if (a != null && INTERACT.contains(a)) cut();
    }

    /** 主人挨了很重的一下（她没在打）：骑士的警觉。 */
    void surprised(long now) {
        surprisedUntil = now + 40;
    }

    /** 下一次起来不播醒来的样子（被收回、来了敌人时直接站起来）。 */
    void skipWake() {
        skipWake = true;
    }

    /** 被摸头：打赢等夸的那 3 秒里就绷不住（被夸），「信赖」起直接贴上来用翅膀圈住你，不然先一僵再化掉。 */
    @Override
    void petted(long now) {
        pets = now - pettedAt < 600 ? pets + 1 : 1;
        pettedAt = now;
        poutAt = -10_000;
        ServerPlayer owner = c.ownerPlayer();
        if (now - doyaAt < PRAISE_WINDOW + length(dragon() ? "wing_flap" : "act_doya")) {
            doyaAt = -10_000;
            act("act_praised");
            return;
        }
        if (dragon()) {
            // 撸龙头：低沉的呼噜、冒爱心
            act("petted");
            if (c.level() instanceof net.minecraft.server.level.ServerLevel level) {
                Vec3 head = LacertaDragon.mouth(c.position(), c.yBodyRot).add(0, .6, 0);
                // 原版猫的呼噜放低八度：大猫一样的低沉呼噜
                var purr = net.minecraft.sounds.SoundEvents.CAT_SOUNDS.get(net.minecraft.world.entity.animal.feline.CatSoundVariants.SoundSet.CLASSIC)
                        .adultSounds().purrSound();
                level.playSound(null, head.x, head.y, head.z, purr, net.minecraft.sounds.SoundSource.NEUTRAL, 1.2f, .5f);
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART, head.x, head.y + .4, head.z, 3, .4, .2, .4, 0);
            }
            return;
        }
        boolean trusted = owner != null && stage(owner).ordinal() >= CompanionBond.Stage.TRUSTED.ordinal();
        act(trusted ? "act_snuggle" : "act_petted");
    }

    /** 你当着她的面摸了、喂了别的同伴：偶尔撇开脸哼一声、鼓嘴（太平时；隔一阵一次）。摸摸她、喂她就好了。 */
    void jealous(ServerPlayer owner, long now) {
        if (engaged() || curled() || acting() || now < nextJealous || !alwaysJealous && c.getRandom().nextFloat() >= JEALOUS) return;
        nextJealous = now + JEALOUS_GAP;
        jealousies++;
        poutAt = now;
        act("act_hmph");
        CompanionLines.say(c, owner, "jealous", 60);
    }

    /** 吃到东西：吃醋的气消了。 */
    void fed(long now) {
        poutAt = -10_000;
    }

    @Override
    protected void wokeUp() {
        sleepyUntil = now() + SLEEPY;
        if (skipWake) {
            skipWake = false;
            return;
        }
        if (dragon()) {
            act("act_stretch");
            return;
        }
        LacertaRest.Tier t = lacerta().rest.lastTier();
        if (t == LacertaRest.Tier.TOGETHER) {
            act("sleep_together_up");
            stretchAfter = true;
        } else if (t != LacertaRest.Tier.WATCH) {
            act("act_stretch");
        }
    }

    /** 共枕的那一晚起来以后，紧接着伸懒腰（不等小动作之间的间隔）。 */
    @Override
    void tick(long now) {
        if (stretchAfter && !acting() && !curled()) {
            stretchAfter = false;
            play("act_stretch", length("act_stretch"));
        }
        super.tick(now);
    }

    @Override
    protected String sleepAnimation() {
        return lacerta().rest.sleepAnimation(now());
    }

    // ---------------------------------------------------------------- 遇到事情：小动作

    @Override
    protected void react(ServerPlayer owner, long now) {
        OwnerWatch w = OwnerWatch.of(owner);
        LacertaCompanion l = lacerta();
        boolean engaged = engaged();
        boolean dragon = dragon();
        double dist = owner.distanceTo(c);
        if (ownerReturned(dist, now) && !engaged && !dragon) {
            act("act_wave");
            return;
        }
        // 主人挨了很重的一下（她没在打）：警觉
        if (now - w.lastHurt < 10 && w.lastHurtAmount >= 8 && !engaged) surprised(now);
        // 你平地摔了一跤、没伤着：先忍不住得意地笑半秒，再歪头看你没事吧
        if (w.lastFall > fallSeen && now - w.lastFall < 30 && owner.getHealth() > owner.getMaxHealth() * .5f && !engaged && !dragon) {
            fallSeen = w.lastFall;
            snickerUntil = now + SNICKER;
            return;
        }
        if (snickerUntil > 0 && now >= snickerUntil) {
            snickerUntil = -10_000;
            act("act_head_tilt");
            return;
        }
        // 打赢一场硬仗（你或她打倒了强敌）：叉腰亮翅等夸（巨龙振一振翅）
        if (w.lastStrongKill > strongKillSeen && now - w.lastStrongKill < 60 && !engaged) {
            strongKillSeen = w.lastStrongKill;
            act(dragon ? "wing_flap" : "act_doya");
            doyaAt = now;
            return;
        }
        // 出了水、雨停了：抖一抖、撩一下湿刘海（巨龙抖翅）
        boolean wetNow = c.isInWaterOrRain();
        if (wet && !wetNow && !engaged && (dragon || !l.wingsFolded())) {
            wet = false;
            act(dragon ? "act_wing_shake" : "act_shake_off");
            return;
        }
        wet = wetNow;
        // 飞着时不插日常的小动作
        if (engaged || l.rest.seated() || l.isFlying()) return;
        var bond = c.bond(owner);
        if (bond.satiety() < CompanionBond.HUNGRY && now >= nextHungry && dist < 10) {
            nextHungry = now + 3600;
            CompanionLines.say(c, owner, "hungry", 50);
            act("act_hmph");
            return;
        }
        // 你手里举着她爱吃的、或身边的营火 / 烟熏炉 / 熔炉烧着：捕食者似的一锁定，下一秒馋得眼睛发亮，凑过来
        if (now - cookLooked >= 100 && c.level() instanceof net.minecraft.server.level.ServerLevel level) {
            cookLooked = now;
            cookNear = LacertaLife.cooking(level, owner.blockPosition());
        }
        CompanionFood.Taste held = CompanionFood.taste(LacertaCompanion.ID, owner.getMainHandItem());
        boolean food = dist < 8 && (held == CompanionFood.Taste.FAVORITE || held == CompanionFood.Taste.LIKED) || cookNear;
        if (now >= nextSniff && food) {
            nextSniff = now + 600;
            foodieUntil = now + CRAVE;
            act(dragon ? "act_sniff_nudge" : "act_sniff");
            return;
        }
        // 被你盯着看：装高冷；看久了发现自己的尾巴在出卖她，两手抓住（巨龙先哼一声）
        if (lookingAtMe(owner) && w.still(now) > 20) lookedAt += 10;
        else {
            lookedAt = 0;
            hmphedAtLook = false;
        }
        if (dragon && lookedAt >= LOOKED_LONG / 2 && !hmphedAtLook) {
            hmphedAtLook = true;
            act("act_hmph");
            return;
        }
        if (lookedAt >= LOOKED_LONG && now >= nextTailGrab && stage(owner).ordinal() < CompanionBond.Stage.TRUSTED.ordinal()) {
            nextTailGrab = now + TAIL_GRAB_GAP;
            act("act_tail_grab");
            return;
        }
        // 附近有敌意、还没打起来：拽一下手套，「准备好了」
        if (!dragon && now >= nextGloveTug && hostileNear(16)) {
            nextGloveTug = now + 1200;
            act("act_glove_tug");
            return;
        }
        if (w.still(now) > IDLE_AFTER && now >= nextIdleAct && c.getRandom().nextInt(4) == 0) {
            String a = idleAct(now);
            if (a != null) {
                nextIdleAct = now + IDLE_GAP + c.getRandom().nextInt(IDLE_GAP);
                act(a);
            }
        }
    }

    private boolean hostileNear(double r) {
        return !c.level().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(r),
                m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy && c.canHarm(m) && m.distanceTo(c) > 6).isEmpty();
    }

    /** 闲着时自己做的（都是独处的帅）：夜里燃一簇掌心火；晴天露天晒翅；望远方；甩披风；偶尔尾刃挽花。翅膀收着时不挑大幅动翼的。 */
    private @Nullable String idleAct(long now) {
        LacertaCompanion l = lacerta();
        if (dragon()) {
            // 巨龙：用鼻吻蹭蹭你（你在跟前时）、伸懒腰、抖抖翅膀
            ServerPlayer owner = c.ownerPlayer();
            boolean near = owner != null && owner.distanceTo(c) < 7;
            int r = c.getRandom().nextInt(10);
            return near && r < 5 ? "act_sniff_nudge" : r < 8 ? "act_stretch" : "act_wing_shake";
        }
        boolean open = c.level().canSeeSky(c.blockPosition().above());
        boolean night = night(c.level());
        int r = c.getRandom().nextInt(10);
        String a;
        if (night) a = r < 5 ? "act_ember" : r < 8 ? "act_watch" : "act_tail_flourish";
        else if (open && !c.level().isRaining()) a = r < 3 ? "act_wingspan" : r < 6 ? "act_watch" : r < 8 ? "act_wing_cape" : "act_tail_flourish";
        else a = r < 5 ? "act_wing_cape" : r < 8 ? "act_watch" : "act_glove_tug";
        if (l.wingsFolded() && WING_ACTS.contains(a)) return "act_glove_tug";
        return a;
    }

    // ---------------------------------------------------------------- 平时：情绪

    @Override
    protected @Nullable String chooseFace(ServerPlayer owner, long now) {
        noticeBiome(now);
        if (now < surprisedUntil && !engaged()) return "face_surprised";
        if (engaged() || now < battleUntil) return "face_battle";
        boolean trusted = stage(owner).ordinal() >= CompanionBond.Stage.TRUSTED.ordinal();
        if (c.talking()) return now < talkFaceUntil && talkFace != null ? talkFace : trusted ? "face_sweet" : "face_poise";
        if (now < delightUntil) return "face_happy";
        if (now - pettedAt < 200) return pets >= 2 ? "face_flustered" : trusted ? "face_sweet" : "face_happy";
        if (now - poutAt < 300) return "face_pout";
        if (now < snickerUntil) return "face_smug";
        // 打赢了等夸，3 秒没人理：鼓嘴生闷气
        if (doyaAt > 0 && now - doyaAt > length("act_doya") && now - doyaAt < length("act_doya") + PRAISE_WINDOW) return "face_pout";
        // 让她留在原地你却走远了：生气（可爱版，攥拳头）
        if (c.staying() && owner.distanceTo(c) > 24) {
            if (farStaySince < 0) farStaySince = now;
            if (now - farStaySince > 200) return "face_angry";
        } else {
            farStaySince = -1;
        }
        if (owner.getHealth() < owner.getMaxHealth() * .35f) return "face_sad";
        if (now < foodieUntil) return "face_foodie";
        // 冷：嘴上死撑，委屈和鼓嘴轮着来
        if (lacerta().life.cold()) return (now / 40) % 2 == 0 ? "face_sad" : "face_pout";
        if (lookedAt >= 40) return trusted ? "face_sweet" : "face_aloof";
        if (now < curiousUntil) return "face_curious";
        boolean idle = OwnerWatch.of(owner).activity(now, owner.isSleeping()).ordinal() >= OwnerWatch.Activity.IDLE.ordinal();
        if (now < sleepyUntil || night(c.level()) && idle) return "face_sleepy";
        if (trusted && owner.distanceTo(c) < 6) return "face_sweet";
        return "face_poise";
    }
}
