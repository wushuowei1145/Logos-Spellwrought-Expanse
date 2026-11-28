package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.foxfire.FoxfireBearer;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.shiraume.FoxfireBolt;
import dev.logos.dimension.ultimate.StandInUltimate;
import dev.logos.dimension.ultimate.UltimateDef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 白梅（少女形态），玩家的同伴（docs/SHIRAUME_COMPANION.md）。每位同伴都有的那些在 {@link CompanionEntity}；这里是她自己的。
 *
 * <p>同一种实体有三种样子（{@link Mode}）：
 * <ul>
 *   <li>{@link Mode#WAITING}：Boss 战之后在封印之间跪坐等她的那位玩家（分霊，只同步给那位玩家，打不到、推不动）；
 *   <li>{@link Mode#PACT}：那位玩家来说话了，立契的过场（{@link ShiraumePact}）；
 *   <li>{@link Mode#COMPANION}：跟着主人的同伴。决策在 {@link ShiraumeMind}（效用 AI），表情和小动作在 {@link ShiraumeMood}，
 *       数值在 {@link CompanionStats}，流火在 {@link CompanionBlade}。
 * </ul>
 *
 * <p>动画走附属的 NPC 驱动：走、跑、死亡由主控制器按状态播；入场、表情、小动作、蜷着睡、跪坐、泡温泉、吃东西由 {@code misc} 播；
 * 施放狐火和流火招式由 {@code combat} 播。
 */
public class ShiraumeCompanion extends CompanionEntity implements FoxfireBearer, Parry.Reactive {
    public static final Identifier ASSET = LogosDimension.id("shiraume_companion");
    /** 奥义（docs/ULTIMATES.md）：绯梅落 · 九尾残月斩。冷却 35 秒、基准耗魔 260；专属演出做好以前先用占位的那一段。 */
    public static final UltimateDef ULTIMATE = new UltimateDef(700, 260, StandInUltimate::new);

    public enum Mode { COMPANION, WAITING, PACT, LEAVING }

    /** 入场动画的长度（刻）。 */
    static final int SUMMON_TICKS = 56;
    /** 施放狐火：动作 0.6 秒，第 0.22 秒出手（动画的 release 标记）。 */
    static final int CAST_TICKS = 12, CAST_RELEASE = 4;
    /** 离场的时间点（刻），与动画的时间标记一致：消散、放下形代、魂火飞回、保险的移除。 */
    static final int DEATH_DISSOLVE = 24, DEATH_KATASHIRO = 29, DEATH_SPIRIT = 30, DEATH_END = 60, DISSOLVE_TICKS = 9;
    /** 主人在这么远以内，魂火才飞回去。 */
    static final double SPIRIT_RANGE = 32;
    /** 化作花瓣回到形代里（立契过场的分霊）用多久。 */
    static final int VANISH = 16;
    /** 给主人缠的狐火：每下附带她狐火伤害的这么多，缠这么多秒，之后冷却。 */
    static final double KINDLE_SHARE = .5, KINDLE_SECONDS = 30;
    static final int KINDLE_COOLDOWN = 1200;
    /** 第四印（{@link CompanionBond#BRAND_SEAL}）：狐火弹伤害乘多少；打中减速多久（刻，缓慢 II）。 */
    public static final double BRAND_DAMAGE = 1.3;
    public static final int BRAND_SLOW = 60;
    /** 摘浆果（pick，1.2 秒）：第 0.5 秒摘下来。与动画的时间标记一致。 */
    static final int PICK_TICKS = 24, PICK_PLUCK = 10;
    /** 立契过场的长度（刻），同动画 pact。 */
    static final int PACT_TICKS = ShiraumePact.END;

    private static final EntityDataAccessor<Byte> MODE = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.BYTE);
    /** 流火看得见没有、从哪一刻起（"1|刻" 凝出来，"0|刻" 散掉）。 */
    private static final EntityDataAccessor<String> BLADE_STATE = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.STRING);
    /** 狐火灯亮着（客户端按它画她身边飘着的那团淡粉狐火）。 */
    private static final EntityDataAccessor<Boolean> LANTERN = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.BOOLEAN);
    /** 当前装束的 ID（{@link CompanionOutfit}）。 */
    private static final EntityDataAccessor<String> OUTFIT = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.STRING);

    final CompanionBlade blade = new CompanionBlade(this);
    private boolean soaking;
    /** 她自己选的装束（对话里让她换的，打完仗会换回这一套）。 */
    private CompanionOutfit preferred = CompanionOutfit.MIKO;
    private long vanishAt = -1;
    private long castStart = -1, kindleReady;
    private @Nullable LivingEntity castTarget;
    private boolean castForOwner, leftHand;
    // ---- 摘浆果
    private long pickStart = -1;
    private @Nullable BlockPos picking;
    // ---- 立契过场
    private long pactStart = -1;
    private boolean pactAgain;
    private record Task(int at, Runnable run) {}
    private final List<Task> pactTasks = new ArrayList<>();
    // ---- 客户端
    /** 狐火灯亮起、熄灭的渐变（0–1）；立契过场从客户端第几刻开始（发抖用）。 */
    private float lanternGlow, lanternGlowO;
    private int pactSeenAt = -1;

    public ShiraumeCompanion(EntityType<? extends ShiraumeCompanion> type, Level level) {
        super(type, level);
        // 浆果丛扎不到她（同原版狐狸），寻路也不绕开
        setPathfindingMalus(PathType.DAMAGING_IN_NEIGHBOR, 0);
        setPathfindingMalus(PathType.DAMAGING, 0);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, CompanionStats.BASE_HEALTH).add(Attributes.MOVEMENT_SPEED, .3)
                .add(Attributes.FOLLOW_RANGE, 32).add(Attributes.ARMOR, CompanionStats.BASE_ARMOR).add(Attributes.STEP_HEIGHT, 1)
                .add(Attributes.ATTACK_DAMAGE, 1);
    }

    @Override
    public CompanionDef def() {
        return Companions.SHIRAUME_DEF;
    }

    @Override
    protected CompanionMood createMood() {
        return new ShiraumeMood(this);
    }

    @Override
    protected CompanionMind createMind() {
        return new ShiraumeMind(this);
    }

    @Override
    protected int summonTicks() {
        return SUMMON_TICKS;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MODE, (byte) Mode.COMPANION.ordinal());
        builder.define(BLADE_STATE, "0|0");
        builder.define(LANTERN, false);
        builder.define(OUTFIT, CompanionOutfit.MIKO.id);
    }

    /** 泡温泉时站在池底，不往上浮。 */
    @Override
    protected boolean canFloat() {
        return !soaking;
    }

    GoalSelector goalSelectorAccess() {
        return goalSelector;
    }

    // ---------------------------------------------------------------- 主人与样子

    public Mode mode() {
        return Mode.values()[Math.floorMod(entityData.get(MODE), Mode.values().length)];
    }

    private void setMode(Mode m) {
        entityData.set(MODE, (byte) m.ordinal());
    }

    @Override
    public boolean isCompanion() {
        return mode() == Mode.COMPANION;
    }

    /** 在封印之间等人（含说话的过场）：只给她等的那位玩家看。 */
    public boolean waiting() {
        Mode m = mode();
        return m == Mode.WAITING || m == Mode.PACT;
    }

    /** 屏幕上说一声：留在原地、跟上。 */
    @Override
    protected void stayToggled(ServerPlayer p, boolean staying) {
        p.sendOverlayMessage(Component.translatable(staying ? "message.logos_dimension.companion.stay" : "message.logos_dimension.companion.follow"));
    }

    @Override
    public @Nullable ShiraumeMind mind() {
        return (ShiraumeMind) super.mind();
    }

    public CompanionBlade blade() {
        return blade;
    }

    @Override
    protected @Nullable LivingEntity aimed() {
        return blade.aimed();
    }

    // ---------------------------------------------------------------- 封印之间：等你、立契

    /** 跪坐在封印之间等 {@code player}（分霊）。 */
    void waitFor(UUID player) {
        owner = player;
        setMode(Mode.WAITING);
        setMisc("pact_wait");
    }

    /** 等的人来说话了：过场开始。{@code again}：他已经立过契（这是另一块杀生石里的分霊）。 */
    void startPact(boolean again) {
        setMode(Mode.PACT);
        pactStart = level().getGameTime();
        pactAgain = again;
        pactTasks.clear();
        getNavigation().stop();
        mood.play("pact", PACT_TICKS + 4);
    }

    void pactLater(int at, Runnable run) {
        pactTasks.add(new Task(at, run));
    }

    /** 立契完成：成为 {@code p} 的同伴（所有人都看得见了）。 */
    void becomeCompanion(ServerPlayer p) {
        setMode(Mode.COMPANION);
        bind(p);
        setBladeShown(false);
        mood.play("act_head_tilt", 36);
        refreshTracking();
    }

    /** 已经立过契的那位分霊：化作花瓣回到主人的形代里。 */
    void vanishInto(ServerPlayer p) {
        setMode(Mode.LEAVING);
        vanishAt = level().getGameTime();
        if (level() instanceof ServerLevel level) ShiraumePact.spirit(level, this, p);
    }

    /** 让附近别的玩家也开始看得见她（等待时只同步给主人）。 */
    private void refreshTracking() {
        if (!(level() instanceof ServerLevel level)) return;
        for (ServerPlayer other : level.players()) if (other.distanceToSqr(this) < 64 * 64) level.getChunkSource().move(other);
    }

    /** 等待时只同步给她等的那位玩家：别人的画面里没有她（第 8.2 节）。 */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        if (waiting()) return owner != null && owner.equals(player.getUUID());
        return super.broadcastToPlayer(player);
    }

    @Override
    public boolean isPushable() {
        return !waiting() && !soaking && super.isPushable();
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity entity) {
        return !waiting() && super.canBeCollidedWith(entity);
    }

    // ---------------------------------------------------------------- 入场、收回

    @Override
    protected void appearBurst(ServerLevel level, Vec3 at) {
        Companions.appearBurst(level, at);
    }

    @Override
    protected void onLeave() {
        blade.drop();
    }

    @Override
    protected void dissolveAway(int ticks) {
        Companions.dissolve(this, ticks);
    }

    @Override
    public boolean leaving() {
        return super.leaving() || mode() == Mode.LEAVING;
    }

    /** 闪回、闪开时两头的一团淡粉狐火和樱花瓣。 */
    @Override
    public void puff(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 16, .3, .45, .3, .03);
        level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.3f), at.x, at.y, at.z, 14, .25, .4, .25, 0);
    }

    // ---------------------------------------------------------------- 每刻

    /** 客户端：狐火灯渐亮渐暗，亮着时往上飘几点淡粉的火星。 */
    @Override
    protected void clientTick() {
        lanternGlowO = lanternGlow;
        lanternGlow = Mth.clamp(lanternGlow + (lanternOn() && !isDeadOrDying() ? .1f : -.08f), 0, 1);
        if (lanternGlow > .6f && tickCount % 4 == 0) {
            Vec3 at = position().add(lanternOffset(tickCount));
            level().addParticle(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, .45f), at.x + (random.nextDouble() - .5) * .14, at.y + .08,
                    at.z + (random.nextDouble() - .5) * .14, 0, .03, 0);
        }
    }

    /** 狐火灯在她身边的位置（相对脚底，格）：绕着她慢慢转，一圈七秒，在肩头的高度上下浮。画火和放火星都用它。 */
    public static Vec3 lanternOffset(float age) {
        double tau = Math.PI * 2, a = age * tau / 140, r = .62 + .06 * Math.sin(age * tau / 90);
        return new Vec3(Math.cos(a) * r, 1.05 + .09 * Math.sin(age * tau / 46), Math.sin(a) * r);
    }

    /** 狐火灯此刻有多亮（0–1，客户端）。 */
    public float lanternGlow(float partialTick) {
        return Mth.lerp(partialTick, lanternGlowO, lanternGlow);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (MODE.equals(key) && level().isClientSide() && mode() == Mode.PACT) pactSeenAt = tickCount;
    }

    /**
     * 立契前后发抖的幅度（0–1，客户端用，docs/SHIRAUME_COMPANION.md 第 8.1 节）：刚被打败，跪坐着等你时一直在抖；
     * 献上流火以后慢慢平静一点，说完「请让我做您的式神」、递过形代就不抖了。
     */
    public float tremble(float partialTick) {
        Mode m = mode();
        if (m == Mode.WAITING) return 1;
        if (m != Mode.PACT || pactSeenAt < 0) return 0;
        float t = tickCount - pactSeenAt + partialTick;
        if (t < ShiraumePact.PRESENT) return 1;
        if (t < ShiraumePact.LINE_3) return Mth.lerp((t - ShiraumePact.PRESENT) / (ShiraumePact.LINE_3 - ShiraumePact.PRESENT), 1, .45f);
        if (t < ShiraumePact.LINE_4) return .45f;
        if (t < ShiraumePact.GIVE_KATASHIRO) return Mth.lerp((t - ShiraumePact.LINE_4) / (ShiraumePact.GIVE_KATASHIRO - ShiraumePact.LINE_4), .45f, 0);
        return 0;
    }

    @Override
    protected boolean tickSpecial(ServerLevel level, long now) {
        switch (mode()) {
            case WAITING -> {
                tickWaiting(level);
                return true;
            }
            case PACT -> {
                tickPact(level, now);
                return true;
            }
            case LEAVING -> {
                if (now - vanishAt >= VANISH) discard();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    protected void tickWithOwner(ServerLevel level, ServerPlayer p) {
        if (tickCount % 5 == 0) lantern(level, p);
    }

    @Override
    protected void tickActions(ServerLevel level, long now, @Nullable ServerPlayer p) {
        if (castStart >= 0) {
            long t = now - castStart;
            if (castTarget != null && castTarget.isAlive()) getLookControl().setLookAt(castTarget, 40, 40);
            if (t == CAST_RELEASE) release(level);
            if (t >= CAST_TICKS) {
                castStart = -1;
                castTarget = null;
                if (!blade.drawn()) setMove("");
            }
        }
        blade.tick(level);
    }

    @Override
    protected void tickAfterEating(ServerLevel level, long now, @Nullable ServerPlayer p) {
        tickPicking(level, now, p);
    }

    private void tickWaiting(ServerLevel level) {
        getNavigation().stop();
        if (!"pact_wait".equals(misc())) setMisc("pact_wait");
    }

    private void tickPact(ServerLevel level, long now) {
        ServerPlayer p = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        if (p == null || p.level() != level || p.distanceTo(this) > 24) {
            // 人走了：接着等
            setMode(Mode.WAITING);
            setBladeShown(false);
            setMisc("pact_wait");
            return;
        }
        int t = (int) (now - pactStart);
        for (Task task : List.copyOf(pactTasks)) {
            if (task.at() <= t) {
                pactTasks.remove(task);
                task.run().run();
            }
        }
        ShiraumePact.tick(this, level, p, t, pactAgain);
        mood.tick(now);
    }

    /** 主人脚下那一格的亮度不超过这么多就点狐火灯（0–15）。 */
    static final int LANTERN_DARK = 7;

    /**
     * 狐火灯：主人脚下那一格暗的时候，她身边飘起一团淡粉的狐火，周围亮起来（第 8.6 节；火由客户端画，位置见 {@link #lanternOffset}）。
     * 亮度同原版的算法：天光按现在的时辰减暗，和方块光取亮的那个。所以洞里、屋里，还有露天的夜里都会点，白天露天不点。
     * 点着以后她自己的光也照到主人脚下：脚下的方块光不比她那盏照过来的亮，就当作是她自己的，不算（不然一点就灭、一灭又点）。
     */
    private void lantern(ServerLevel level, ServerPlayer p) {
        if (soaking || distanceTo(p) > 12 || mood.curled()) {
            setLantern(false);
            return;
        }
        // 脚所在的那一格；脚底可能差一点点陷进地面（浮点误差），往上抬一点再取，不然取到的是地面那一格（实心方块里没有光）
        BlockPos feet = BlockPos.containing(p.getX(), p.getY() + .2, p.getZ());
        if (lanternBrightness(level, feet) > LANTERN_DARK) {
            setLantern(false);
            return;
        }
        BlockPos head = blockPosition().above();
        setLantern(CompanionLight.hold(level, head) || CompanionLight.hold(level, blockPosition()));
    }

    /** 主人脚下 {@code feet} 有多亮，她自己那盏灯照过来的不算。 */
    int lanternBrightness(ServerLevel level, BlockPos feet) {
        int sky = Math.max(0, level.getBrightness(LightLayer.SKY, feet) - level.getSkyDarken());
        int block = level.getBrightness(LightLayer.BLOCK, feet);
        if (lanternOn()) {
            BlockPos head = blockPosition().above();
            int mine = CompanionLight.LEVEL - Math.min(head.distManhattan(feet), blockPosition().distManhattan(feet));
            if (block <= mine) block = 0;
        }
        return Math.max(sky, block);
    }

    // ---------------------------------------------------------------- 装束

    private long dressedAt;

    public CompanionOutfit outfit() {
        return CompanionOutfit.byId(entityData.get(OUTFIT));
    }

    @Override
    public String outfitId() {
        return outfit().id;
    }

    /** 换上 {@code o}（已经穿着就只记下时间）：身边一阵花瓣和淡粉狐火，像变身一样换好。 */
    void dress(CompanionOutfit o, long now) {
        dressedAt = now;
        if (outfit() == o) return;
        entityData.set(OUTFIT, o.id);
        if (level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + .7, getZ(), 24, .35, .5, .35, .03);
            level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.2f), getX(), getY() + .7, getZ(), 16, .3, .45, .3, 0);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, .7f, 1.4f);
        }
    }

    /** 她自己选的那一套（对话里换的）；平时是巫女服。 */
    public CompanionOutfit preferredOutfit() {
        return preferred;
    }

    /** 对话里让她换上 {@code o}：记下来，打完仗也换回这一套。 */
    void chooseOutfit(CompanionOutfit o, long now) {
        preferred = o;
        dress(o, now);
    }

    /** 上次换装（或泡着温泉、续上浴衣）是哪一刻。 */
    long dressedAt() {
        return dressedAt;
    }

    public boolean lanternOn() {
        return entityData.get(LANTERN);
    }

    private void setLantern(boolean on) {
        if (lanternOn() != on) entityData.set(LANTERN, on);
    }

    // ---------------------------------------------------------------- 狐火

    public float foxfireDamage() {
        return attack();
    }

    void setFoxfireDamage(float damage) {
        setAttack(damage);
    }

    /** 流火一刀的伤害：攻击 × 随契印涨的倍数（{@link CompanionStats#blade}）。 */
    public float bladeDamage() {
        return (float) (attack() * CompanionStats.blade(seals()));
    }

    /** 一发狐火弹的伤害：攻击；第四印以后再乘 {@link #BRAND_DAMAGE}。 */
    public float boltDamage() {
        return (float) (attack() * (CompanionBond.unlocked(seals(), CompanionBond.BRAND_SEAL) ? BRAND_DAMAGE : 1));
    }

    /** 第四印：狐火弹打中 {@code victim} 以后减速，留一枚狐火印（满了爆开，和流火的刀攒的是同一种）。 */
    public void brand(LivingEntity victim) {
        if (!CompanionBond.unlocked(seals(), CompanionBond.BRAND_SEAL) || !victim.isAlive()) return;
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, BRAND_SLOW, 1), this);
        dev.logos.dimension.ryuka.FoxMarks.add(this, victim);
    }

    public boolean casting() {
        return castStart >= 0;
    }

    @Override
    protected boolean busyHands() {
        return blade.drawn() || casting();
    }

    boolean kindleReady(long now) {
        return now >= kindleReady;
    }

    /** 开始一次施放：对 {@code target} 放狐火弹，或（{@code forOwner}）给主人的手缠上狐火。左右手交替。拿着刀时不放。 */
    boolean startCast(LivingEntity target, boolean forOwner) {
        if (casting() || blade.drawn() || eating() || !(level() instanceof ServerLevel level)) return false;
        leftHand = !leftHand;
        castStart = level.getGameTime();
        castTarget = target;
        castForOwner = forOwner;
        setMove((leftHand ? "cast_foxfire_l" : "cast_foxfire_r") + "|" + castStart + "|1|false");
        getLookControl().setLookAt(target, 40, 40);
        return true;
    }

    private void release(ServerLevel level) {
        LivingEntity target = castTarget;
        if (target == null || !target.isAlive()) return;
        Vec3 side = Vec3.directionFromRotation(0, yBodyRot + (leftHand ? -90 : 90)).scale(.25);
        Vec3 from = getEyePosition().add(side).add(0, -.1, 0);
        if (castForOwner) {
            FoxfireWraps.wrap(target, FoxfireFlame.SHIRAUME, attack() * KINDLE_SHARE, KINDLE_SECONDS);
            kindleReady = level.getGameTime() + KINDLE_COOLDOWN;
            Vec3 hand = target.getEyePosition().add(0, -.6, 0);
            level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.3f), hand.x, hand.y, hand.z, 18, .25, .2, .25, 0);
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, hand.x, hand.y, hand.z, 10, .3, .2, .3, .02);
            level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .6f, 1.7f);
            if (target instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.translatable("message.logos_dimension.companion.kindled"));
            return;
        }
        Vec3 aim = target.position().add(0, target.getBbHeight() * .55, 0).subtract(from);
        FoxfireBolt.companion(level, this, from, aim, target, boltDamage());
        casts++;
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .45f, 1.9f);
    }

    // ---------------------------------------------------------------- 摘浆果

    public boolean picking() {
        return pickStart >= 0;
    }

    /** 去摘 {@code bush} 上熟了的浆果（人已经站到旁边）。 */
    boolean startPicking(BlockPos bush) {
        if (picking() || eating() || blade.drawn() || !(level() instanceof ServerLevel level)) return false;
        picking = bush;
        pickStart = level.getGameTime();
        getNavigation().stop();
        getLookControl().setLookAt(bush.getX() + .5, bush.getY() + .6, bush.getZ() + .5);
        mood.play("pick", PICK_TICKS);
        return true;
    }

    private void tickPicking(ServerLevel level, long now, @Nullable ServerPlayer p) {
        if (pickStart < 0 || picking == null) return;
        int t = (int) (now - pickStart);
        if (t == PICK_PLUCK) {
            BlockState state = level.getBlockState(picking);
            if (state.getBlock() instanceof SweetBerryBushBlock && state.getValue(SweetBerryBushBlock.AGE) >= 2) {
                int age = state.getValue(SweetBerryBushBlock.AGE);
                net.minecraft.world.item.Item berry = state.getBlock().asItem() == Items.AIR ? Items.SWEET_BERRIES : state.getBlock().asItem();
                int total = 1 + getRandom().nextInt(2) + (age == 3 ? 1 : 0);
                level.setBlock(picking, state.setValue(SweetBerryBushBlock.AGE, 1), 2);
                level.playSound(null, picking, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.NEUTRAL, 1, .9f + getRandom().nextFloat() * .3f);
                picked++;
                // 饿了先吃一颗，其余放进包袱；不饿就都收起来
                boolean hungry = p != null && p.getData(Companions.BOND).satiety() < CompanionBond.HUNGRY;
                int keep = hungry ? total - 1 : total;
                if (p != null && keep > 0) {
                    ItemStack left = Companions.pouch(p).put(new ItemStack(berry, keep));
                    if (!left.isEmpty()) spawnAtLocation(level, left);
                }
                ItemStack one = new ItemStack(berry);
                pickStart = -1;
                picking = null;
                if (hungry) startEating(one, false);
                return;
            }
        }
        if (t >= PICK_TICKS) {
            pickStart = -1;
            picking = null;
        }
    }

    /** 测试用：摘过几次、放过几发狐火弹。 */
    private static int picked, casts;

    public static int casts() {
        return casts;
    }

    public static int meals() {
        return CompanionEntity.meals();
    }

    public static int picked() {
        return picked;
    }

    // ---------------------------------------------------------------- 温泉

    public boolean soaking() {
        return soaking;
    }

    /** 开始、停止泡温泉：泡着时不往下沉（深一点的池子里她浮在水里，水面停在肩头），不被推开，也不会呛水。 */
    void setSoaking(boolean on) {
        if (soaking == on) return;
        soaking = on;
        setNoGravity(on);
        if (on) setDeltaMovement(Vec3.ZERO);
    }

    @Override
    public boolean canBreatheUnderwater() {
        return soaking || super.canBreatheUnderwater();
    }

    // ---------------------------------------------------------------- 互动

    /** 等她的那位玩家来说话：立契（第 8.11 节）；不是同伴的样子时不理。 */
    @Override
    protected @Nullable InteractionResult interactSpecial(ServerPlayer sp) {
        if (mode() == Mode.WAITING) {
            ShiraumePact.begin(this, sp);
            return InteractionResult.SUCCESS;
        }
        return mode() != Mode.COMPANION ? InteractionResult.PASS : null;
    }

    @Override
    protected boolean blankToken(ItemStack held) {
        return KatashiroItem.isBlank(held);
    }

    @Override
    protected void sealed(ServerPlayer sp, int seals) {
        mood.act("act_proud");
        CompanionLines.say(sp, "seal", 50);
        sp.sendOverlayMessage(Component.translatable("message.logos_dimension.companion.sealed", seals));
        if (level() instanceof ServerLevel level) {
            level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.6f), getX(), getY() + .6, getZ(), 40, .5, .5, .5, 0);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1, 1.5f);
        }
    }

    /** 绑定的形代丢了：拿一张纸让她再折一张。 */
    @Override
    protected boolean remake(ServerPlayer sp, ItemStack held) {
        if (!held.is(Items.PAPER) || KatashiroItem.carries(sp)) return false;
        if (!sp.getAbilities().instabuild) held.shrink(1);
        ItemStack k = KatashiroItem.bound(sp);
        if (!sp.getInventory().add(k)) sp.drop(k, false);
        mood.act("act_head_tilt");
        CompanionLines.say(sp, "refold", 50);
        return true;
    }

    // ---------------------------------------------------------------- 受伤与离场

    @Override
    protected boolean immune() {
        return waiting() || mode() == Mode.LEAVING;
    }

    /** 浆果丛扎不到她（同原版狐狸）。 */
    @Override
    protected boolean ignores(DamageSource source) {
        return source.is(DamageTypes.SWEET_BERRY_BUSH);
    }

    @Override
    protected boolean blocked(ServerLevel level, DamageSource source) {
        return blade.tryBlock(level, source);
    }

    @Override
    public void makeStuckInBlock(BlockState state, Vec3 speedMultiplier) {
        // 浆果丛拖不慢她
        if (state.getBlock() instanceof SweetBerryBushBlock) return;
        super.makeStuckInBlock(state, speedMultiplier);
    }

    /** 她这一刀被弹开了。 */
    @Override
    public boolean parried(LivingEntity by) {
        return blade.parried(by);
    }

    @Override
    public boolean staggered() {
        return blade.staggered();
    }

    @Override
    protected void onDie() {
        blade.drop();
        castStart = -1;
    }

    @Override
    protected void fell(ServerPlayer p) {
        p.sendOverlayMessage(Component.translatable("message.logos_dimension.companion.fell", (Companions.REST_TICKS + 19) / 20));
    }

    /**
     * 离场（docs/SHIRAUME_COMPANION.md 第 7.5 节）：死亡动画自己播（挨打 → 跪下 → 侧倒），
     * 这里按时间点放消散、形代和飞回主人身边的魂火。
     */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (!(level() instanceof ServerLevel level) || isRemoved()) return;
        if (deathTime == DEATH_DISSOLVE) Companions.dissolve(this, DISSOLVE_TICKS);
        if (deathTime == DEATH_KATASHIRO) Katashiro.drop(level, groundBelow(level));
        if (deathTime == DEATH_SPIRIT && owner != null) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
            if (p != null && p.level() == level && p.distanceTo(this) <= SPIRIT_RANGE) {
                FoxfireBolt.spirit(level, this, position().add(0, .5, 0), p);
            }
        }
        if (deathTime >= DEATH_END) remove(RemovalReason.KILLED);
    }

    private Vec3 groundBelow(ServerLevel level) {
        BlockPos at = blockPosition();
        for (int i = 0; i < 4 && level.getBlockState(at.below()).getCollisionShape(level, at.below()).isEmpty(); i++) at = at.below();
        return new Vec3(getX(), at.getY(), getZ());
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("Outfit", outfit().id);
        output.putString("PreferredOutfit", preferred.id);
        // 过场中途存档：读回来接着等
        output.putBoolean("Waiting", waiting());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        entityData.set(OUTFIT, CompanionOutfit.byId(input.getStringOr("Outfit", CompanionOutfit.MIKO.id)).id);
        preferred = CompanionOutfit.byId(input.getStringOr("PreferredOutfit", CompanionOutfit.MIKO.id));
        // 泡温泉时存的档：泡的状态不存，失重也别带回来
        setNoGravity(false);
        if (input.getBooleanOr("Waiting", false) && owner != null) waitFor(owner);
    }

    // ---------------------------------------------------------------- 动画与刀

    /** 流火凝出来（真）或散掉（假）。 */
    void setBladeShown(boolean shown) {
        if (bladeShown() == shown) return;
        entityData.set(BLADE_STATE, (shown ? "1|" : "0|") + level().getGameTime());
    }

    public boolean bladeShown() {
        return entityData.get(BLADE_STATE).startsWith("1");
    }

    /** 流火最近一次凝出或散掉的游戏刻（客户端按它画凝聚、消散）。 */
    public long bladeChangedAt() {
        String s = entityData.get(BLADE_STATE);
        try {
            return Long.parseLong(s.substring(2));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public Identifier npcAnimationAsset() {
        return ASSET;
    }

    // ---------------------------------------------------------------- 说话（CompanionTalk.Talker）

    @Override
    public CompanionSheet talkSheet(ServerPlayer p) {
        return ShiraumeTalk.sheet(this, p);
    }

    @Override
    public List<CompanionTalk.Line> talkGreeting(ServerPlayer p) {
        return ShiraumeTalk.greeting(this, p);
    }

    @Override
    public CompanionTalk.Reply talkPick(ServerPlayer p, String option) {
        return ShiraumeTalk.pick(this, p, option);
    }

    @Override
    protected void talkFinished(ServerPlayer p, boolean bye) {
        ShiraumeTalk.ended(this, p, bye);
    }

    @Override
    public void talkInterrupted(ServerPlayer p) {
        ShiraumeTalk.interrupted(this, p);
    }

    @Override
    public CompanionTalk.@Nullable Line talkBack(ServerPlayer p) {
        return ShiraumeTalk.back(this, p);
    }

    /**
     * 角色面板里展示的那一位（客户端临时造的，不放进世界）：穿 {@code outfit}，表情 {@code face}（{@code face_*}，循环），
     * 身体播 {@code pose}（{@code ryuka_idle} 这类循环动作，可以为 null），{@code blade} 时流火凝在手上。
     */
    public void showcase(CompanionOutfit outfit, String face, @Nullable String pose, boolean blade) {
        entityData.set(OUTFIT, outfit.id);
        entityData.set(MISC, face);
        entityData.set(MOVE, pose == null ? "" : pose + "|0|1|true");
        entityData.set(BLADE_STATE, (blade ? "1|" : "0|") + (level().getGameTime() - 1000));
        entityData.set(LANTERN, false);
    }

    /** 蜷着睡、跪坐、泡温泉：整个人由 misc 动画摆，客户端这时不叠加视线。 */
    @Override
    public boolean curled() {
        String m = misc();
        return "sleep".equals(m) || "pact_wait".equals(m) || "soak".equals(m) || "pact".equals(m);
    }

    @Override
    public FoxfireFlame foxfireFlame() {
        return FoxfireFlame.SHIRAUME;
    }
}
