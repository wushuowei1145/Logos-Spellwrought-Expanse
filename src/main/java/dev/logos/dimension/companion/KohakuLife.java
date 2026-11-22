package dev.logos.dimension.companion;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀的日常（docs/KOHAKU_COMPANION.md 第 8、10 节）：没在打的时候她做什么。
 * <ul>
 *   <li>闲着陪你（心智的 LOUNGE）：你站着不动 {@link #STILL} 刻，她凑过来——
 *     <ol>
 *       <li>下雨、你在露天：站在你身边拄起长杖撑伞（伞面是特效）；羁绊到「亲近」、你在她身边 {@link #WIDE_NEAR} 格内时伞变大，把你也罩进去。</li>
 *       <li>在家（她认下的地毯 {@link #RUG_HOME} 格内）：去她的地毯上鸭子坐；你挂机了就在那里趴着打盹，每天第一次在那里睡着加一点羁绊。
 *           还没有地毯时，你在屋里、{@link #RUG_FIND} 格内有地毯，她就去坐上，说「这里归我了」，记下来。</li>
 *       <li>晴天白天、晒得到太阳（露天或玻璃顶，看天光）：在你旁边趴成猫饼晒太阳；晒满两分钟「晒饱了」，下一次的琥珀护膜多 {@link #SUN_BONUS} 倍。</li>
 *       <li>不然：在你身边鸭子坐，尾巴轻轻摇，头顶偶尔冒一个琥珀色的音符；你挂机了就在你身边趴着打盹。</li>
 *     </ol>
 *     你一动她就站起来；来了敌人时不播起身，直接站起来。</li>
 *   <li>给你吃的（GIFT）：你饿了（饥饿值 ≤ {@link #FOOD_HUNGRY}）或血不满、没在打时，每 {@link #FOOD_ROLL} 刻判一次，成了就走过来递给你一份；
 *       给过以后 {@link #FOOD_GAP} 刻内不再给，一天最多 {@link #FOOD_PER_DAY} 次。</li>
 *   <li>猫的战利品（FETCH）：一场打完，她偶尔（{@link #FETCH_CHANCE}）把这场掉落里她觉得最有意思的一样叼到你脚边，一脸得意。</li>
 * </ul>
 * 整身姿势（坐、趴、撑伞）走 combat 控制器（她的 MOVE），表情和小动作照常在 misc 上播：她坐着也会慢眨眼、洗脸、打哈欠、被摸头。
 * 下雨天的嫌弃脸在 {@link KohakuMood}。
 */
final class KohakuLife {
    /** 你站着不动多久（刻）她凑过来。 */
    static final int STILL = 200;
    /** 坐在你身边：离你多远（你的侧边）；离要坐的地方多近就不寻路、直接走过去。 */
    static final double BESIDE = 1.25, FINAL_STRETCH = 2.5;
    /** 姿势和过渡的长度（刻），与动画文件一致（单元测试对过）。 */
    static final int SIT_DOWN = 16, STAND_UP = 12, LOAF_DOWN = 24, LOAF_UP = 16, UMBRELLA_OPEN = 10, UMBRELLA_CLOSE = 8;
    /** 递东西：动画多长、第几刻交出去（{@code give} 标记）。 */
    static final int GIVE_TICKS = 32, GIVE_AT = 16;
    /** 给你吃的：每隔多久判一次、成功率（羁绊每段多几成）、给过以后多久不再给、一天最多几次、你饿到多少算饿。 */
    static final int FOOD_ROLL = 600, FOOD_GAP = 6000, FOOD_PER_DAY = 4, FOOD_HUNGRY = 14;
    static final float FOOD_CHANCE = .2f, FOOD_STAGE = .05f;
    /** 吃的和概率（百分比，第 8.3 节）：熟鳕鱼、熟鲑鱼、带饱和效果的谜之炖菜、面包、曲奇、金胡萝卜。 */
    static final int[] FOOD_WEIGHTS = {30, 30, 25, 8, 5, 2};
    /** 猫的战利品：一场打完多大概率去叼、找多远的掉落。 */
    static final float FETCH_CHANCE = .35f;
    static final double FETCH_RADIUS = 12;
    /** 地毯：你身边多远内找、离她的地毯多远算在家；每天第一次在那里睡着加多少羁绊、睡着多久才算。 */
    static final int RUG_FIND = 8, RUG_HOME = 16, RUG_NAP = 100;
    static final float RUG_BOND = 2;
    /** 晒太阳：晒满多久（刻）晒饱，晒饱了下一次护膜多几倍。 */
    static final int SUN_FULL = 2400;
    static final double SUN_BONUS = .25;
    /** 伞变大：羁绊到哪一段、你离她多近。 */
    static final CompanionBond.Stage WIDE_STAGE = CompanionBond.Stage.CLOSE;
    static final double WIDE_NEAR = 2;
    /** 测试用：给你吃的、叼战利品都必成。 */
    static boolean lucky;
    /** 测试用：塞过几次吃的、叼过几次战利品、认过几次地毯、在地毯上睡着加过几次羁绊、晒饱过几次。 */
    static int foodGifts, fetches, rugClaims, rugNaps, sunFulls;

    /** 闲着陪你的去处。 */
    enum Spot { BESIDE, RUG, SUN, UMBRELLA }

    /** 整身姿势（坐、趴、撑伞），和它在 MOVE 上的动画名。 */
    enum Pose {
        NONE(null, null, 0, null, 0), SIT("sit_down", "sit_beside", SIT_DOWN, "stand_up", STAND_UP),
        LOAF("loaf_down", "sleep_loaf", LOAF_DOWN, "loaf_up", LOAF_UP),
        UMBRELLA("umbrella_open", "umbrella", UMBRELLA_OPEN, "umbrella_close", UMBRELLA_CLOSE);

        final @Nullable String down, loop, up;
        final int downTicks, upTicks;

        Pose(@Nullable String down, @Nullable String loop, int downTicks, @Nullable String up, int upTicks) {
            this.down = down;
            this.loop = loop;
            this.downTicks = downTicks;
            this.up = up;
            this.upTicks = upTicks;
        }
    }

    /** 这一回陪你：去哪、站在哪、朝哪，是不是打盹，是不是第一次认这块地毯。 */
    record Plan(Spot spot, Vec3 at, @Nullable BlockPos rug, boolean nap, boolean claim) {
        Pose pose() {
            return spot == Spot.UMBRELLA ? Pose.UMBRELLA : nap || spot == Spot.SUN ? Pose.LOAF : Pose.SIT;
        }
    }

    private final KohakuCompanion c;
    private @Nullable Plan plan;
    private Pose pose = Pose.NONE;
    private long settleAt, risenAt = -1, napSince = -1;
    /** 起身播完要伸懒腰（猫饼起来）；正在起身（这一刻醒来不伸懒腰）。 */
    private boolean stretchAfter, rising;
    private int sunTicks, approach;
    private boolean sunned;
    private long nextNote;
    /** 找地毯：上一次找的时刻和结果（隔一阵才找一次）。 */
    private long rugLooked = -10_000;
    private @Nullable BlockPos rugFound;
    /** 给你吃的：下一次判的时刻、上一次给的时刻、判成了要给的那一份。 */
    private long nextFoodRoll, lastFood = -100_000;
    private ItemStack pendingFood = ItemStack.EMPTY;
    /** 猫的战利品：这一场从哪一刻打起、要去叼的那一样、叼在嘴里的。 */
    private long fightStart = -1;
    private @Nullable ItemEntity fetchTarget;
    private ItemStack carrying = ItemStack.EMPTY;
    /** 正在递：递的是什么、哪一刻开始、递完以后得意一下（战利品）。 */
    private ItemStack handing = ItemStack.EMPTY;
    private long handAt = -1;
    private boolean handingLoot;

    KohakuLife(KohakuCompanion c) {
        this.c = c;
    }

    // ---------------------------------------------------------------- 给心智用

    /** 正在起身、正在递东西：这几刻站着不动、不换别的事。 */
    boolean busy(long now) {
        return risenAt >= 0 && now < risenAt || handAt >= 0;
    }

    /** 摆着一个姿势（坐、趴、撑伞）。 */
    boolean posed() {
        return pose != Pose.NONE;
    }

    Pose pose() {
        return pose;
    }

    /** 坐着或趴着（长杖收起来；只播坐着也能播的小动作）。 */
    boolean seated() {
        return pose == Pose.SIT || pose == Pose.LOAF;
    }

    /** 趴着打盹（猫饼）。 */
    boolean napping() {
        return pose == Pose.LOAF;
    }

    @Nullable Plan plan() {
        return plan;
    }

    /** 手里拿着要递的东西（吃的、叼来的战利品）：这时右手里画它，不画长杖。 */
    ItemStack inHand() {
        return !handing.isEmpty() ? handing : carrying;
    }

    /** 下雨天她在露天（嫌弃脸）。 */
    boolean inRain() {
        return c.level().isRainingAt(BlockPos.containing(c.getEyePosition()));
    }

    /** 晒饱了：下一次的护膜多 {@link #SUN_BONUS} 倍；用掉一次（凝护膜时调用）。 */
    double takeSunBonus() {
        if (!sunned) return 0;
        sunned = false;
        sunTicks = 0;
        return SUN_BONUS;
    }

    boolean sunned() {
        return sunned;
    }

    /** 测试用：晒到差 {@code left} 刻就饱。 */
    void sunAlmost(int left) {
        sunTicks = Math.max(0, SUN_FULL - left);
        sunned = false;
    }

    /** 这一回陪你的打算；没有为 null（你在动、在打、在睡、她被叫留下……）。打分时每半秒算一次。 */
    @Nullable Plan choose(ServerLevel level, ServerPlayer p, OwnerWatch w, long now, boolean calm, @Nullable Vec3 order) {
        plan = null;
        if (!calm || order != null || c.staying() || p.isSleeping() || c.eating() || !p.onGround() || w.still(now) < STILL) return null;
        if (c.talking() && !posed()) return null;
        if (p.distanceTo(c) > 24) return null;
        boolean afk = w.still(now) >= OwnerWatch.AFK_TICKS;
        // 1. 下雨、你在露天：撑伞
        if (level.isRainingAt(BlockPos.containing(p.getEyePosition()))) {
            Vec3 at = beside(level, p, wideUmbrella(p) ? .95 : BESIDE);
            return plan = at == null ? null : new Plan(Spot.UMBRELLA, at, null, false, false);
        }
        // 2. 在家：她的地毯（还没有就认一块）
        BlockPos rug = homeRug(level, p, now);
        if (rug != null) {
            boolean claim = !rug.equals(claimedRug(level, p));
            return plan = new Plan(Spot.RUG, onRug(rug), rug, afk && !claim, claim);
        }
        // 3. 晴天白天、晒得到太阳：猫饼
        if (!level.isRaining() && !level.isDarkOutside()) {
            Vec3 sun = sunSpot(level, p);
            if (sun != null) return plan = new Plan(Spot.SUN, sun, null, true, false);
        }
        // 4. 你身边鸭子坐；挂机了就在你身边趴着打盹
        Vec3 at = beside(level, p, BESIDE);
        return plan = at == null ? null : new Plan(Spot.BESIDE, at, null, afk, false);
    }

    /** 伞要不要变大把你也罩进去：羁绊到「亲近」，你在她身边。 */
    boolean wideUmbrella(ServerPlayer p) {
        return CompanionBond.Stage.of(c.bond(p).affection()).ordinal() >= WIDE_STAGE.ordinal() && p.distanceTo(c) < WIDE_NEAR + 1.5;
    }

    // ---------------------------------------------------------------- 闲着陪你

    /** 走到打算的地方，到了摆好姿势；要换姿势时先起身。 */
    void lounge(ServerLevel level, ServerPlayer p, long now) {
        Plan pl = plan;
        if (pl == null) return;
        Pose want = pl.pose();
        if (pose != Pose.NONE && pose != want) {
            rise(level, now, false);
            return;
        }
        Vec3 at = pl.at();
        double flat = Math.hypot(c.getX() - at.x, c.getZ() - at.z);
        if (pose == Pose.NONE) {
            if (flat > .45 || Math.abs(c.getY() - at.y) > 1.2) {
                if (flat > FINAL_STRETCH || Math.abs(c.getY() - at.y) > 1.2) {
                    approach = 0;
                    if (now % 10 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(at.x, at.y, at.z, 1);
                    return;
                }
                // 最后一小段直接走过去（寻路到了那一格附近就算到，差的这一点走不过去）；走了一阵还没到就挪过去
                c.getNavigation().stop();
                c.getMoveControl().setWantedPosition(at.x, at.y, at.z, .8);
                if (++approach > 60) c.setPos(at);
                return;
            }
            approach = 0;
            c.getNavigation().stop();
            c.setSprinting(false);
            c.setPos(c.position().lerp(at, .5));
            if (pl.spot() == Spot.UMBRELLA || pl.spot() == Spot.BESIDE) faceAlong(p);
            settle(level, p, want, now, pl);
            return;
        }
        // 摆着姿势：定在那里，看着你（打盹时不看）
        c.getNavigation().stop();
        c.setDeltaMovement(c.getDeltaMovement().multiply(0, 1, 0));
        if (!napping()) c.getLookControl().setLookAt(p, 10, 20);
        if (pose == Pose.UMBRELLA) c.setUmbrella(wideUmbrella(p) ? 2 : 1);
        tickPosed(level, p, now, pl);
    }

    /** 摆上姿势：先播坐下 / 趴下 / 撑伞，播完换成循环的那一段。认地毯就在这时候。 */
    private void settle(ServerLevel level, ServerPlayer p, Pose want, long now, Plan pl) {
        pose = want;
        settleAt = now + want.downTicks;
        c.setMove(want.down + "|" + now + "|1|false");
        if (want == Pose.UMBRELLA) {
            c.setUmbrella(wideUmbrella(p) ? 2 : 1);
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, .5f, 1.5f);
        }
        if (pl.claim() && pl.rug() != null) {
            p.setData(Companions.KOHAKU_DAYS, days(p).withRug(Optional.of(GlobalPos.of(level.dimension(), pl.rug()))));
            rugClaims++;
            CompanionLines.say(c, p, "rug_claim", 60);
            ((KohakuMood) c.mood).smug(now);
        }
        nextNote = now + 60 + c.getRandom().nextInt(80);
    }

    /** 摆着姿势的每一刻：过渡播完换循环；打盹时算晒太阳、地毯上的羁绊；坐着时偶尔冒一个音符。 */
    private void tickPosed(ServerLevel level, ServerPlayer p, long now, Plan pl) {
        if (now == settleAt || now > settleAt && !c.move().startsWith(pose.loop)) {
            c.setMove(pose.loop + "|" + now + "|1|true");
            if (pose == Pose.LOAF) {
                c.mood.curl(true);
                napSince = now;
            }
        }
        if (now < settleAt) return;
        if (pose == Pose.LOAF && pl.spot() == Spot.SUN && now % 20 == 0 && sunny(level, c.blockPosition())) {
            sunTicks += 20;
            if (sunTicks >= SUN_FULL && !sunned) {
                sunned = true;
                sunFulls++;
                CompanionLines.say(c, p, "sunned", 50);
                level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1f), c.getX(), c.getY() + .5, c.getZ(), 12, .5, .2, .5, 0);
            }
        }
        if (pose == Pose.LOAF && pl.spot() == Spot.RUG && napSince >= 0 && now - napSince >= RUG_NAP) {
            long day = level.getOverworldClockTime() / 24000;
            KohakuDays d = days(p);
            if (d.rugNapDay() != day) {
                p.setData(Companions.KOHAKU_DAYS, d.withRugNap(day));
                var b = c.bond(p);
                c.setBond(p, b.withAffection(b.affection() + RUG_BOND));
                rugNaps++;
            }
        }
        if (pose == Pose.SIT && now >= nextNote) {
            nextNote = now + 80 + c.getRandom().nextInt(120);
            Vec3 at = c.position().add(0, 1.55, 0);
            if (c.getRandom().nextBoolean()) level.sendParticles(ParticleTypes.NOTE, at.x, at.y, at.z, 0, .12, 0, 0, 1);
            else level.sendParticles(new DustParticleOptions(0xFFD86A, .7f), at.x, at.y, at.z, 3, .15, .1, .15, 0);
        }
    }

    /** 起身：{@code hurry}（来了敌人）时直接站起来，不然播起身那一段（猫饼起来以后伸个懒腰）。 */
    void rise(ServerLevel level, long now, boolean hurry) {
        if (pose == Pose.NONE) {
            plan = null;
            return;
        }
        Pose was = pose;
        pose = Pose.NONE;
        plan = null;
        napSince = -1;
        stretchAfter = was == Pose.LOAF && !hurry;
        boolean down = was.loop != null && c.move().startsWith(was.loop) || was.down != null && c.move().startsWith(was.down);
        // 醒来的伸懒腰由这里管（猫饼要先起来；来了敌人就不伸了）
        rising = true;
        if (c.mood.curled()) c.mood.curl(false);
        rising = false;
        if (hurry || !down) {
            if (down) c.setMove("");
            risenAt = -1;
            return;
        }
        c.setMove(was.up + "|" + now + "|1|false");
        risenAt = now + was.upTicks;
    }

    /** 每刻（她自己的 tickActions 里）：起身播完收尾，递东西走到交出去那一刻。 */
    void tick(ServerLevel level, long now, @Nullable ServerPlayer p) {
        if (risenAt >= 0 && now >= risenAt) {
            risenAt = -1;
            if (c.move().startsWith("stand_up") || c.move().startsWith("loaf_up") || c.move().startsWith("umbrella_close")) c.setMove("");
            if (stretchAfter) c.mood.act("act_cat_stretch");
            stretchAfter = false;
        }
        if (handAt >= 0) tickHand(level, now, p);
        // 不撑了：收伞那一段播完，伞面才收掉
        if (pose == Pose.NONE && c.umbrella() != 0 && !c.move().startsWith("umbrella_close")) c.setUmbrella(0);
    }

    /** 正在从姿势里起身：这时醒来不由通用的那一下伸懒腰（猫饼要先起来，播完 {@code loaf_up} 再伸）。 */
    boolean rising() {
        return rising;
    }

    private static boolean sunny(ServerLevel level, BlockPos feet) {
        return level.getBrightness(LightLayer.SKY, feet.above()) >= 15;
    }

    /** 你身边晒得到太阳、站得住的一格（她自己那格晒得到就不挪）。 */
    private @Nullable Vec3 sunSpot(ServerLevel level, ServerPlayer p) {
        if (c.distanceTo(p) < 3.2 && c.distanceTo(p) > .9 && c.onGround() && sunny(level, c.blockPosition())) return c.position();
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(p.getYRot() + 90 + (i % 2 == 0 ? 1 : -1) * (i / 2) * 30);
            Vec3 s = stand(level, p.position().add(Math.cos(a) * 1.8, 0, Math.sin(a) * 1.8));
            if (s != null && sunny(level, BlockPos.containing(s))) return s;
        }
        return null;
    }

    /** 你侧边 {@code dist} 格、站得住的地方（右边、左边，再往后挪一点的几处）。 */
    private @Nullable Vec3 beside(ServerLevel level, ServerPlayer p, double dist) {
        Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
        Vec3 right = new Vec3(-look.z, 0, look.x);
        // 先看她已经在的那一侧
        double side = c.position().subtract(p.position()).dot(right) >= 0 ? 1 : -1;
        for (double s : new double[] {side, -side}) {
            for (double back : new double[] {.25, .8}) {
                Vec3 v = stand(level, p.position().add(right.scale(s * dist)).add(look.scale(-back)));
                if (v != null) return v;
            }
        }
        return null;
    }

    /** {@code at} 附近（上下两格）站得住的位置：脚下是实心的面，身子那两格空着、没有水。 */
    private @Nullable Vec3 stand(ServerLevel level, Vec3 at) {
        BlockPos base = BlockPos.containing(at.x, at.y + .2, at.z);
        for (int dy = 1; dy >= -2; dy--) {
            BlockPos f = base.above(dy);
            if (!level.getFluidState(f).isEmpty()) continue;
            if (level.getBlockState(f.below()).isFaceSturdy(level, f.below(), net.minecraft.core.Direction.UP)
                    && level.noCollision(c, c.getDimensions(c.getPose()).makeBoundingBox(new Vec3(at.x, f.getY(), at.z)))) {
                return new Vec3(at.x, f.getY(), at.z);
            }
        }
        return null;
    }

    /** 转过去和你朝同一边（坐在你身边一起看前面）。 */
    private void faceAlong(ServerPlayer p) {
        float yaw = p.getYRot();
        c.setYRot(yaw);
        c.yBodyRot = yaw;
        c.setYHeadRot(yaw);
    }

    // ---------------------------------------------------------------- 地毯

    static KohakuDays days(ServerPlayer p) {
        return p.getData(Companions.KOHAKU_DAYS);
    }

    /** 她认下的地毯（这个维度里、地毯还在、上面空着）；不然 null，地毯被拿走了顺便忘掉。 */
    private @Nullable BlockPos claimedRug(ServerLevel level, ServerPlayer p) {
        Optional<GlobalPos> g = days(p).rug();
        if (g.isEmpty() || !g.get().dimension().equals(level.dimension())) return null;
        BlockPos at = g.get().pos();
        if (!level.isLoaded(at)) return null;
        if (!isRug(level, at)) {
            p.setData(Companions.KOHAKU_DAYS, days(p).withRug(Optional.empty()));
            return null;
        }
        return at;
    }

    /** 在家：你在她认下的地毯 {@link #RUG_HOME} 格内，就是那块；还没认过时，你在屋里、身边有地毯就是那块（要去认）。 */
    private @Nullable BlockPos homeRug(ServerLevel level, ServerPlayer p, long now) {
        BlockPos mine = claimedRug(level, p);
        if (mine != null) {
            return mine.distSqr(p.blockPosition()) <= RUG_HOME * RUG_HOME && Math.abs(mine.getY() - p.getY()) <= 4 ? mine : null;
        }
        if (now - rugLooked >= 40) {
            rugLooked = now;
            rugFound = level.canSeeSky(p.blockPosition().above()) ? null : findRug(level, p);
        }
        return rugFound != null && isRug(level, rugFound) ? rugFound : null;
    }

    /** 你身边 {@link #RUG_FIND} 格内、屋顶底下、上面空着的一块地毯（离你最近的）。 */
    private @Nullable BlockPos findRug(ServerLevel level, ServerPlayer p) {
        BlockPos base = p.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-RUG_FIND, -2, -RUG_FIND), base.offset(RUG_FIND, 2, RUG_FIND))) {
            if (!isRug(level, pos) || level.canSeeSky(pos.above())) continue;
            double d = pos.distSqr(base);
            if (d < 1.5 || d >= bestD) continue;
            bestD = d;
            best = pos.immutable();
        }
        return best;
    }

    static boolean isRug(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.WOOL_CARPETS) && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty();
    }

    /** 坐在地毯正中（地毯有 1/16 格厚）。 */
    private static Vec3 onRug(BlockPos rug) {
        return new Vec3(rug.getX() + .5, rug.getY() + 1 / 16.0, rug.getZ() + .5);
    }

    // ---------------------------------------------------------------- 给你吃的

    /** 吃的按概率（{@code roll} 是 0–99）。 */
    static int foodIndex(int roll) {
        for (int i = 0; i < FOOD_WEIGHTS.length; i++) {
            roll -= FOOD_WEIGHTS[i];
            if (roll < 0) return i;
        }
        return FOOD_WEIGHTS.length - 1;
    }

    static ItemStack food(int index) {
        return switch (index) {
            case 0 -> new ItemStack(Items.COOKED_COD);
            case 1 -> new ItemStack(Items.COOKED_SALMON);
            case 2 -> {
                ItemStack stew = new ItemStack(Items.SUSPICIOUS_STEW);
                stew.set(DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(List.of(new SuspiciousStewEffects.Entry(MobEffects.SATURATION, 7))));
                yield stew;
            }
            case 3 -> new ItemStack(Items.BREAD);
            case 4 -> new ItemStack(Items.COOKIE);
            default -> new ItemStack(Items.GOLDEN_CARROT);
        };
    }

    /** 每半秒（打分前）：你饿了或伤着、没在打、今天还没给够，隔一阵判一次要不要给你吃的。 */
    void rollFood(ServerLevel level, ServerPlayer p, long now, boolean calm) {
        if (!pendingFood.isEmpty() || !calm || c.talking() || now < nextFoodRoll) return;
        nextFoodRoll = now + FOOD_ROLL;
        if (p.getFoodData().getFoodLevel() > FOOD_HUNGRY && p.getHealth() >= p.getMaxHealth()) return;
        if (now - lastFood < FOOD_GAP) return;
        long day = level.getOverworldClockTime() / 24000;
        if (days(p).foodsOn(day) >= FOOD_PER_DAY) return;
        float chance = FOOD_CHANCE + FOOD_STAGE * CompanionBond.Stage.of(c.bond(p).affection()).ordinal();
        if (!lucky && c.getRandom().nextFloat() >= chance) return;
        pendingFood = food(foodIndex(c.getRandom().nextInt(100)));
    }

    /** 判成了、还没递出去。 */
    boolean giftPending() {
        return !pendingFood.isEmpty() || handAt >= 0 && !handingLoot;
    }

    /** 走到你跟前，递给你（{@code give}，第 0.8 秒交出去）。 */
    void gift(ServerPlayer p, long now) {
        if (handAt >= 0) {
            hold(p);
            return;
        }
        if (pendingFood.isEmpty()) return;
        if (approach(p, now)) return;
        handing = pendingFood;
        pendingFood = ItemStack.EMPTY;
        handingLoot = false;
        startHand(p, now);
    }

    // ---------------------------------------------------------------- 猫的战利品

    /** 每半秒（打分前）：记下这一场从什么时候打起；一场刚打完，偶尔挑一样掉落去叼。 */
    void watchFight(ServerLevel level, ServerPlayer p, long now, boolean foe) {
        if (foe) {
            if (fightStart < 0) fightStart = now;
            fetchTarget = null;
            return;
        }
        if (fightStart < 0) return;
        long since = fightStart;
        fightStart = -1;
        if (!carrying.isEmpty() || handAt >= 0 || !lucky && c.getRandom().nextFloat() >= FETCH_CHANCE) return;
        ItemEntity best = null;
        int bestScore = -1;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(FETCH_RADIUS),
                e -> e.isAlive() && !e.getItem().isEmpty() && e.getOwner() == null && e.getAge() <= now - since + 60 && e.distanceTo(p) > 1.5)) {
            int s = interest(e.getItem()) * 10 + c.getRandom().nextInt(10);
            if (s > bestScore) {
                bestScore = s;
                best = e;
            }
        }
        fetchTarget = best;
    }

    /** 她觉得一样东西有多有意思：稀有的、附了魔的、不能叠的、有名字的、叠得少的。 */
    static int interest(ItemStack s) {
        int v = s.getRarity().ordinal() * 10;
        if (s.isEnchanted()) v += 8;
        if (s.getMaxStackSize() == 1) v += 6;
        else if (s.getMaxStackSize() <= 16) v += 2;
        if (s.has(DataComponents.CUSTOM_NAME)) v += 4;
        return v;
    }

    /** 有要去叼的、或叼在嘴里还没给你的。 */
    boolean fetching() {
        return fetchTarget != null && fetchTarget.isAlive() || !carrying.isEmpty() || handAt >= 0 && handingLoot;
    }

    /** 走过去叼起来（{@code pick}），叼到你脚边放下（{@code give}），一脸得意。 */
    void fetch(ServerLevel level, ServerPlayer p, long now) {
        if (handAt >= 0) {
            hold(p);
            return;
        }
        if (carrying.isEmpty()) {
            ItemEntity e = fetchTarget;
            if (e == null || !e.isAlive()) {
                fetchTarget = null;
                return;
            }
            if (c.distanceTo(e) > 1.2) {
                if (now % 5 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(e, 1.2);
                return;
            }
            c.getNavigation().stop();
            c.mood.act("pick");
            carrying = e.getItem().copy();
            c.take(e, carrying.getCount());
            e.discard();
            fetchTarget = null;
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, .3f, 1.3f);
            return;
        }
        if (c.mood.playing() != null && "pick".equals(c.mood.playing())) return;
        if (approach(p, now)) return;
        handing = carrying;
        carrying = ItemStack.EMPTY;
        handingLoot = true;
        startHand(p, now);
    }

    // ---------------------------------------------------------------- 递东西

    /** 走到你跟前（1.6 格内）；还在走返回真。 */
    private boolean approach(ServerPlayer p, long now) {
        if (c.distanceTo(p) > 1.7) {
            if (now % 10 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(p, 1.1);
            return true;
        }
        c.getNavigation().stop();
        return false;
    }

    private void startHand(ServerPlayer p, long now) {
        handAt = now;
        c.mood.cut();
        c.mood.act("give");
        hold(p);
    }

    private void hold(ServerPlayer p) {
        c.getNavigation().stop();
        c.getLookControl().setLookAt(p, 30, 30);
    }

    private void tickHand(ServerLevel level, long now, @Nullable ServerPlayer p) {
        long t = now - handAt;
        if (t == GIVE_AT && !handing.isEmpty()) {
            ItemStack stack = handing;
            handing = ItemStack.EMPTY;
            if (p == null) {
                c.spawnAtLocation(level, stack);
            } else if (handingLoot) {
                // 叼到你脚边：放在你脚下，一脸得意
                ItemEntity e = new ItemEntity(level, p.getX(), p.getY() + .1, p.getZ(), stack, 0, .1, 0);
                e.setPickUpDelay(20);
                level.addFreshEntity(e);
                ((KohakuMood) c.mood).smug(now);
                CompanionLines.say(c, p, "fetch", 50);
                fetches++;
            } else {
                if (!p.getInventory().add(stack)) p.drop(stack, false);
                long day = level.getOverworldClockTime() / 24000;
                p.setData(Companions.KOHAKU_DAYS, days(p).withFood(day));
                lastFood = now;
                CompanionLines.say(c, p, "give_food", 60);
                foodGifts++;
            }
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, .3f, 1.1f);
        }
        if (t >= GIVE_TICKS) handAt = -1;
    }

    /** 被打断（来了敌人、你走远了）：手里的放下（叼来的掉在她脚边，吃的下次再给）。 */
    void dropHand(ServerLevel level) {
        if (!carrying.isEmpty()) c.spawnAtLocation(level, carrying);
        carrying = ItemStack.EMPTY;
        if (!handing.isEmpty()) {
            if (handingLoot) c.spawnAtLocation(level, handing);
            else pendingFood = handing;
        }
        handing = ItemStack.EMPTY;
        handAt = -1;
    }

    String describe() {
        return "pose=" + pose.name().toLowerCase() + (plan == null ? "" : " spot=" + plan.spot().name().toLowerCase()
                + String.format(" at=(%.2f %.2f %.2f) me=(%.2f %.2f %.2f) nav=%s", plan.at().x, plan.at().y, plan.at().z, c.getX(), c.getY(), c.getZ(),
                c.getNavigation().isDone() ? "done" : "going")) + " sun=" + sunTicks
                + (sunned ? "(full)" : "") + (pendingFood.isEmpty() ? "" : " food=" + pendingFood.getItem()) + (fetching() ? " fetch" : "");
    }
}
