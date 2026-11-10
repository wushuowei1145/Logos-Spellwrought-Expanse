package dev.logos.dimension.companion;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.foxfire.FoxfireBearer;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.npc.LogosMagicBridge;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcDeathPose;
import dev.logos.dimension.shiraume.FoxfireBolt;
import dev.logos.runtime.LastingCompanion;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 白梅（少女形态），玩家的同伴（docs/SHIRAUME_COMPANION.md）。
 *
 * <p>同一种实体有三种样子（{@link Mode}）：
 * <ul>
 *   <li>{@link Mode#WAITING}：Boss 战之后在封印之间跪坐等她的那位玩家（分霊，只同步给那位玩家，打不到、推不动）；
 *   <li>{@link Mode#PACT}：那位玩家来说话了，立契的过场（{@link ShiraumePact}）；
 *   <li>{@link Mode#COMPANION}：跟着主人的同伴。决策在 {@link CompanionMind}（效用 AI），表情和小动作在 {@link CompanionMood}，
 *       数值在 {@link CompanionStats}，流火在 {@link CompanionBlade}。
 * </ul>
 * 对 Logos 来说她是主人的长期同伴（{@link LastingCompanion}）：一人一个，没有时限，跟随和收回都由她自己做。
 *
 * <p>动画走附属的 NPC 驱动：走、跑、死亡由主控制器按状态播；入场、表情、小动作、蜷着睡、跪坐、泡温泉、吃东西由 {@code misc} 播；
 * 施放狐火和流火招式由 {@code combat} 播。
 */
public class ShiraumeCompanion extends PathfinderMob implements NpcAnimated, FoxfireBearer, LastingCompanion, Parry.Reactive {
    public static final Identifier ASSET = LogosDimension.id("shiraume_companion");

    public enum Mode { COMPANION, WAITING, PACT, LEAVING }

    /** 生命上限到这么多算强敌：给主人缠火、主人打倒后她得意。 */
    public static final float STRONG = 40;
    /** 入场动画的长度（刻）；最初 {@link #HIDDEN_TICKS} 刻客户端不画她（misc 控制器有 2 刻过渡，免得先看到原尺寸的她）。 */
    static final int SUMMON_TICKS = 56, HIDDEN_TICKS = 2;
    /** 施放狐火：动作 0.6 秒，第 0.22 秒出手（动画的 release 标记）。 */
    static final int CAST_TICKS = 12, CAST_RELEASE = 4;
    /** 离场的时间点（刻），与动画的时间标记一致：消散、放下形代、魂火飞回、保险的移除。 */
    static final int DEATH_DISSOLVE = 24, DEATH_KATASHIRO = 29, DEATH_SPIRIT = 30, DEATH_END = 60, DISSOLVE_TICKS = 9;
    /** 主人在这么远以内，魂火才飞回去。 */
    static final double SPIRIT_RANGE = 32;
    /** 收回时先挥手这么多刻，再散去；化作花瓣回到形代里（立契过场的分霊）用多久。 */
    static final int LEAVE_WAVE = 20, LEAVE_DISSOLVE = 12, VANISH = 16;
    /** 给主人缠的狐火：每下附带她狐火伤害的这么多，缠这么多秒，之后冷却。 */
    static final double KINDLE_SHARE = .5, KINDLE_SECONDS = 30;
    static final int KINDLE_COOLDOWN = 1200;
    /** 吃东西（eat，2.4 秒）：三口、咽下去；摘浆果（pick，1.2 秒）：第 0.5 秒摘下来。与动画的时间标记一致。 */
    static final int EAT_TICKS = 48, EAT_SWALLOW = 35, PICK_TICKS = 24, PICK_PLUCK = 10;
    static final int[] EAT_BITES = {12, 20, 28};
    /** 立契过场的长度（刻），同动画 pact。 */
    static final int PACT_TICKS = ShiraumePact.END;

    private static final EntityDataAccessor<String> MISC = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> MODE = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.BYTE);
    /** 流火看得见没有、从哪一刻起（"1|刻" 凝出来，"0|刻" 散掉）。 */
    private static final EntityDataAccessor<String> BLADE_STATE = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.STRING);
    /** 狐火灯亮着（客户端按它画她身边飘着的那团淡粉狐火）。 */
    private static final EntityDataAccessor<Boolean> LANTERN = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.BOOLEAN);
    /** 当前装束的 ID（{@link CompanionOutfit}）。 */
    private static final EntityDataAccessor<String> OUTFIT = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.STRING);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    final CompanionMood mood = new CompanionMood(this);
    final CompanionBlade blade = new CompanionBlade(this);
    private @Nullable CompanionMind mind;
    private @Nullable UUID owner;
    private float foxfireDamage = (float) CompanionStats.BASE_FOXFIRE;
    private boolean staying, soaking;
    private long leavingAt = -1, vanishAt = -1;
    private long castStart = -1, kindleReady;
    private @Nullable LivingEntity castTarget;
    private boolean castForOwner, leftHand;
    private float deathYaw = Float.NaN;
    private int sinceHurt = 1000;
    // ---- 吃东西、摘浆果
    private long eatStart = -1, pickStart = -1;
    private ItemStack eating = ItemStack.EMPTY;
    private boolean eatShared;
    private @Nullable BlockPos picking;
    // ---- 立契过场
    private long pactStart = -1;
    private boolean pactAgain;
    private record Task(int at, Runnable run) {}
    private final List<Task> pactTasks = new ArrayList<>();
    // ---- 客户端
    private String moveFrom = "";
    private @Nullable CombatMove clientMove;
    /** 狐火灯亮起、熄灭的渐变（0–1）；立契过场从客户端第几刻开始（发抖用）。 */
    private float lanternGlow, lanternGlowO;
    private int pactSeenAt = -1;

    public ShiraumeCompanion(EntityType<? extends ShiraumeCompanion> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
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
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MISC, "");
        builder.define(MOVE, "");
        builder.define(MODE, (byte) Mode.COMPANION.ordinal());
        builder.define(BLADE_STATE, "0|0");
        builder.define(LANTERN, false);
        builder.define(OUTFIT, CompanionOutfit.MIKO.id);
    }

    @Override
    protected void registerGoals() {
        // 泡温泉时站在池底，不往上浮
        goalSelector.addGoal(0, new FloatGoal(this) {
            @Override
            public boolean canUse() {
                return !soaking && super.canUse();
            }
        });
        mind = new CompanionMind(this);
        goalSelector.addGoal(1, mind);
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

    public boolean isCompanion() {
        return mode() == Mode.COMPANION;
    }

    /** 在封印之间等人（含说话的过场）：只给她等的那位玩家看。 */
    public boolean waiting() {
        Mode m = mode();
        return m == Mode.WAITING || m == Mode.PACT;
    }

    /** 认主并按主人算一次数值。 */
    void bind(ServerPlayer player) {
        owner = player.getUUID();
        CompanionStats.apply(this, player, player.getData(Companions.BOND).seals());
        setHealth(getMaxHealth());
    }

    public @Nullable UUID ownerId() {
        return owner;
    }

    /** 主人：在线、活着、和她在同一个维度时才有。 */
    public @Nullable ServerPlayer ownerPlayer() {
        if (owner == null || !(level() instanceof ServerLevel level)) return null;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        return p != null && p.isAlive() && p.level() == level ? p : null;
    }

    public boolean staying() {
        return staying;
    }

    void toggleStay(ServerPlayer p) {
        staying = !staying;
        mood.act(staying ? "act_head_tilt" : "act_wave");
        p.sendOverlayMessage(Component.translatable(staying ? "message.logos_dimension.companion.stay" : "message.logos_dimension.companion.follow"));
    }

    public @Nullable CompanionMind mind() {
        return mind;
    }

    public CompanionBlade blade() {
        return blade;
    }

    /** 她能不能伤到这个东西：不伤玩家、主人的宠物和使魔，也不伤别的白梅。 */
    public boolean canHarm(Entity e) {
        if (e == this || e instanceof Player || e instanceof ShiraumeCompanion) return false;
        if (owner != null && e instanceof TamableAnimal pet && pet.getOwner() != null && owner.equals(pet.getOwner().getUUID())) return false;
        return owner == null || !owner.equals(LogosMagicBridge.masterOf(e));
    }

    /** 刀扫过去时砍不砍它：敌对生物、她的目标、冲着她或主人来的。路过的动物和村民不砍。 */
    boolean hostileTo(LivingEntity e) {
        if (e == getTarget() || e instanceof Enemy) return true;
        return e instanceof Mob m && (m.getTarget() == this || m.getTarget() != null && m.getTarget().getUUID().equals(owner));
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

    void startSummon() {
        mood.play("summon", SUMMON_TICKS);
    }

    /** 收回：挥挥手，然后化作花瓣散去。 */
    void leave() {
        if (!(level() instanceof ServerLevel level)) return;
        leavingAt = level.getGameTime();
        getNavigation().stop();
        blade.drop();
        stopEating();
        mood.act("act_wave");
    }

    public boolean leaving() {
        return leavingAt >= 0 || mode() == Mode.LEAVING;
    }

    /** Logos 要她回去（主人叫出了别的长期同伴，或者对她用了 {@code dismiss}）：挥手散去。 */
    @Override
    public void sendHome() {
        ServerPlayer p = ownerPlayer();
        if (p != null && p.getData(Companions.BOND).out().map(getUUID()::equals).orElse(false)) {
            Companions.dismiss(p);
            return;
        }
        if (!leaving()) leave();
    }

    // ---------------------------------------------------------------- 每刻

    @Override
    public void tick() {
        super.tick();
        deathYaw = isDeadOrDying() ? NpcDeathPose.hold(this, deathYaw) : Float.NaN;
        if (level() instanceof ServerLevel level) {
            if (!isDeadOrDying()) serverTick(level);
        } else {
            clientTick();
        }
    }

    /** 客户端：狐火灯渐亮渐暗，亮着时往上飘几点淡粉的火星。 */
    private void clientTick() {
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

    private void serverTick(ServerLevel level) {
        long now = level.getGameTime();
        sinceHurt++;
        switch (mode()) {
            case WAITING -> {
                tickWaiting(level);
                return;
            }
            case PACT -> {
                tickPact(level, now);
                return;
            }
            case LEAVING -> {
                if (now - vanishAt >= VANISH) discard();
                return;
            }
            default -> {
            }
        }
        if (leavingAt >= 0) {
            if (now - leavingAt == LEAVE_WAVE) Companions.dissolve(this, LEAVE_DISSOLVE);
            if (now - leavingAt > LEAVE_WAVE + LEAVE_DISSOLVE + 20) discard();
            mood.tick(now);
            return;
        }
        ServerPlayer p = ownerPlayer();
        if (tickCount % 20 == 0) {
            // 主人不在（下线、死亡、去了别的维度），或者契上记的已经不是她（旧存档里留下的那一位）：悄悄离开
            if (p == null || !p.getData(Companions.BOND).out().map(getUUID()::equals).orElse(false)) {
                discard();
                return;
            }
        }
        if (p != null) {
            if (tickCount % 100 == 0) CompanionStats.apply(this, p, p.getData(Companions.BOND).seals());
            if (tickCount % CompanionBond.HUNGER_TICKS == 0 && tickCount > 0) {
                var b = p.getData(Companions.BOND);
                p.setData(Companions.BOND, b.withSatiety(b.satiety() - 1));
            }
            if (tickCount % 5 == 0) lantern(level, p);
        }
        if (sinceHurt > 100 && tickCount % 40 == 0 && getHealth() < getMaxHealth()) heal(1);
        if (castStart >= 0) {
            long t = now - castStart;
            if (castTarget != null && castTarget.isAlive()) getLookControl().setLookAt(castTarget, 40, 40);
            if (t == CAST_RELEASE) release(level);
            if (t >= CAST_TICKS) {
                castStart = -1;
                castTarget = null;
                if (!blade.drawn()) entityData.set(MOVE, "");
            }
        }
        blade.tick(level);
        tickEating(level, now, p);
        tickPicking(level, now, p);
        mood.tick(now);
    }

    private void tickWaiting(ServerLevel level) {
        getNavigation().stop();
        if (!"pact_wait".equals(entityData.get(MISC))) setMisc("pact_wait");
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

    /**
     * 狐火灯：主人在洞里或屋里（头顶有方块）而且暗的时候，她身边飘起一团淡粉的狐火，周围亮起来（第 8.6 节；火由客户端画，
     * 位置见 {@link #lanternOffset}）。露天看高度图，不看天光：天光要等光照引擎算完，刚改过的地方会读错。
     * 点着以后只要还在顶下就不灭（她自己的光会把周围照亮）。
     */
    private void lantern(ServerLevel level, ServerPlayer p) {
        if (soaking || distanceTo(p) > 12 || mood.curled()) {
            setLantern(false);
            return;
        }
        BlockPos at = p.blockPosition();
        boolean open = level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) <= at.getY();
        if (open || !lanternOn() && level.getBrightness(LightLayer.BLOCK, at) > 7) {
            setLantern(false);
            return;
        }
        BlockPos head = blockPosition().above();
        setLantern(CompanionLight.hold(level, head) || CompanionLight.hold(level, blockPosition()));
    }

    // ---------------------------------------------------------------- 装束

    private long dressedAt;

    public CompanionOutfit outfit() {
        return CompanionOutfit.byId(entityData.get(OUTFIT));
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
        return foxfireDamage;
    }

    void setFoxfireDamage(float damage) {
        foxfireDamage = damage;
    }

    public boolean casting() {
        return castStart >= 0;
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
        entityData.set(MOVE, (leftHand ? "cast_foxfire_l" : "cast_foxfire_r") + "|" + castStart + "|1|false");
        getLookControl().setLookAt(target, 40, 40);
        return true;
    }

    private void release(ServerLevel level) {
        LivingEntity target = castTarget;
        if (target == null || !target.isAlive()) return;
        Vec3 side = Vec3.directionFromRotation(0, yBodyRot + (leftHand ? -90 : 90)).scale(.25);
        Vec3 from = getEyePosition().add(side).add(0, -.1, 0);
        if (castForOwner) {
            FoxfireWraps.wrap(target, FoxfireFlame.SHIRAUME, foxfireDamage * KINDLE_SHARE, KINDLE_SECONDS);
            kindleReady = level.getGameTime() + KINDLE_COOLDOWN;
            Vec3 hand = target.getEyePosition().add(0, -.6, 0);
            level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.3f), hand.x, hand.y, hand.z, 18, .25, .2, .25, 0);
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, hand.x, hand.y, hand.z, 10, .3, .2, .3, .02);
            level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .6f, 1.7f);
            if (target instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.translatable("message.logos_dimension.companion.kindled"));
            return;
        }
        Vec3 aim = target.position().add(0, target.getBbHeight() * .55, 0).subtract(from);
        FoxfireBolt.companion(level, this, from, aim, target, foxfireDamage);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .45f, 1.9f);
    }

    // ---------------------------------------------------------------- 吃东西、摘浆果

    public boolean eating() {
        return eatStart >= 0;
    }

    public boolean picking() {
        return pickStart >= 0;
    }

    /** 吃一口 {@code food}（一份）。{@code shared}：主人亲手给的（一起吃饭，加好感）。 */
    boolean startEating(ItemStack food, boolean shared) {
        if (eating() || blade.drawn() || casting() || !(level() instanceof ServerLevel level)) return false;
        eating = food.copyWithCount(1);
        eatShared = shared;
        eatStart = level.getGameTime();
        setItemSlot(EquipmentSlot.MAINHAND, eating.copy());
        getNavigation().stop();
        mood.play("eat", EAT_TICKS);
        return true;
    }

    private void tickEating(ServerLevel level, long now, @Nullable ServerPlayer p) {
        if (eatStart < 0) return;
        int t = (int) (now - eatStart);
        for (int bite : EAT_BITES) {
            if (t == bite) {
                Vec3 mouth = getEyePosition().add(Vec3.directionFromRotation(0, yBodyRot).scale(.22)).add(0, -.12, 0);
                level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, eating.getItem()), mouth.x, mouth.y, mouth.z, 4, .05, .05, .05, .04);
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, .5f, 1.4f + getRandom().nextFloat() * .2f);
            }
        }
        if (t == EAT_SWALLOW && p != null) {
            CompanionFood.Taste taste = CompanionFood.taste(eating);
            heal(CompanionFood.nutrition(eating) * 1.5f);
            var b = p.getData(Companions.BOND);
            long day = level.getOverworldClockTime() / 24000;
            float gain = eatShared ? CompanionBond.meal(taste == CompanionFood.Taste.FAVORITE, b.mealsOn(day), b.satiety()) : 0;
            b = b.withSatiety(CompanionBond.fed(b.satiety(), CompanionFood.nutrition(eating))).withAffection(b.affection() + gain);
            if (eatShared) b = b.withMeal(day, b.mealsOn(day) + 1);
            p.setData(Companions.BOND, b);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_BURP, SoundSource.NEUTRAL, .35f, 1.6f);
            if (taste == CompanionFood.Taste.FAVORITE) {
                level.sendParticles(ParticleTypes.HEART, getX(), getY() + 1.35, getZ(), 2, .2, .1, .2, 0);
                mood.delighted(now);
            }
            meals++;
        }
        if (t >= EAT_TICKS) stopEating();
    }

    private void stopEating() {
        if (eatStart < 0) return;
        eatStart = -1;
        eating = ItemStack.EMPTY;
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
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

    /** 测试用：吃过几口、摘过几次。 */
    private static int meals, picked;

    public static int meals() {
        return meals;
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

    /**
     * 右键她（docs/SHIRAUME_COMPANION.md 第 8.11 节）：等她的那位玩家来说话；主人空手摸头、潜行空手开包袱、
     * 拿吃的喂她、拿空白形代盖契印、绑定的形代丢了时拿一张纸让她再折一张。
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || isDeadOrDying()) return super.mobInteract(player, hand);
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || !sp.getUUID().equals(owner) || leaving()) return InteractionResult.PASS;
        if (mode() == Mode.WAITING) {
            ShiraumePact.begin(this, sp);
            return InteractionResult.SUCCESS;
        }
        if (mode() != Mode.COMPANION) return InteractionResult.PASS;
        ItemStack held = sp.getItemInHand(hand);
        if (held.isEmpty()) {
            if (sp.isShiftKeyDown()) {
                CompanionPouch.open(sp);
            } else {
                getNavigation().stop();
                getLookControl().setLookAt(sp, 40, 40);
                mood.petted(level().getGameTime());
                var b = sp.getData(Companions.BOND);
                long day = level().getOverworldClockTime() / 24000;
                sp.setData(Companions.BOND, b.withAffection(b.affection() + CompanionBond.pat(b.patsOn(day))).withPats(day, b.patsOn(day) + 1));
            }
            return InteractionResult.SUCCESS;
        }
        if (KatashiroItem.isBlank(held)) {
            if (!sp.getAbilities().instabuild) held.shrink(1);
            int seals = Companions.addSeal(sp);
            mood.act("act_proud");
            CompanionLines.say(sp, "seal", 50);
            sp.sendOverlayMessage(Component.translatable("message.logos_dimension.companion.sealed", seals));
            if (level() instanceof ServerLevel level) {
                level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.6f), getX(), getY() + .6, getZ(), 40, .5, .5, .5, 0);
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1, 1.5f);
            }
            return InteractionResult.SUCCESS;
        }
        if (held.is(Items.PAPER) && !KatashiroItem.carries(sp)) {
            if (!sp.getAbilities().instabuild) held.shrink(1);
            ItemStack k = KatashiroItem.bound(sp);
            if (!sp.getInventory().add(k)) sp.drop(k, false);
            mood.act("act_head_tilt");
            CompanionLines.say(sp, "refold", 50);
            return InteractionResult.SUCCESS;
        }
        CompanionFood.Taste taste = CompanionFood.taste(held);
        if (taste != CompanionFood.Taste.NOT_FOOD) {
            if (eating() || blade.drawn()) return InteractionResult.PASS;
            if (taste == CompanionFood.Taste.REFUSED) {
                mood.act("refuse");
                CompanionLines.say(sp, "refuse", 40);
                return InteractionResult.SUCCESS;
            }
            if (sp.getData(Companions.BOND).satiety() >= CompanionBond.SATIETY_MAX - .5f && taste != CompanionFood.Taste.FAVORITE) {
                CompanionLines.say(sp, "full", 40);
                return InteractionResult.SUCCESS;
            }
            if (startEating(held, true) && !sp.getAbilities().instabuild) held.shrink(1);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    // ---------------------------------------------------------------- 受伤与离场

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (waiting() || mode() == Mode.LEAVING) return false;
        Entity by = source.getEntity();
        if (by != null && (by.getUUID().equals(owner) || by instanceof ShiraumeCompanion)) {
            // 主人打她：不疼，但会闹别扭
            if (by.getUUID().equals(owner)) mood.pouted(level.getGameTime());
            return false;
        }
        if (leaving()) return false;
        // 浆果丛扎不到她（同原版狐狸）
        if (source.is(DamageTypes.SWEET_BERRY_BUSH)) return false;
        if (amount > 0 && !isInvulnerableTo(level, source) && blade.tryBlock(level, source)) return false;
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) sinceHurt = 0;
        return hurt;
    }

    @Override
    public void makeStuckInBlock(BlockState state, Vec3 speedMultiplier) {
        // 浆果丛拖不慢她
        if (state.getBlock() instanceof SweetBerryBushBlock) return;
        super.makeStuckInBlock(state, speedMultiplier);
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
        return false;
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
    public void die(DamageSource source) {
        // 先把手里的吃的收掉，免得当作装备掉出来
        stopEating();
        blade.drop();
        super.die(source);
        entityData.set(MOVE, "");
        entityData.set(MISC, "");
        castStart = -1;
        ServerPlayer p = ownerPlayer();
        if (p != null) {
            Companions.fell(p, this);
            p.sendOverlayMessage(Component.translatable("message.logos_dimension.companion.fell", (Companions.REST_TICKS + 19) / 20));
        }
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

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (owner != null) output.store("Owner", UUIDUtil.CODEC, owner);
        output.putBoolean("Staying", staying);
        output.putString("Outfit", outfit().id);
        // 过场中途存档：读回来接着等
        output.putBoolean("Waiting", waiting());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
        staying = input.getBooleanOr("Staying", false);
        entityData.set(OUTFIT, CompanionOutfit.byId(input.getStringOr("Outfit", CompanionOutfit.MIKO.id)).id);
        // 泡温泉时存的档：泡的状态不存，失重也别带回来
        setNoGravity(false);
        if (input.getBooleanOr("Waiting", false) && owner != null) waitFor(owner);
    }

    // ---------------------------------------------------------------- 动画与刀

    void setMisc(String name) {
        if (!entityData.get(MISC).equals(name)) entityData.set(MISC, name);
    }

    void setMove(String text) {
        if (!entityData.get(MOVE).equals(text)) entityData.set(MOVE, text);
    }

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

    @Override
    public String npcMiscAnimation() {
        String name = entityData.get(MISC);
        return name.isEmpty() ? null : name;
    }

    /** 蜷着睡、跪坐、泡温泉：整个人由 misc 动画摆，客户端这时不叠加视线。 */
    public boolean curled() {
        String m = entityData.get(MISC);
        return "sleep".equals(m) || "pact_wait".equals(m) || "soak".equals(m) || "pact".equals(m);
    }

    /** 入场最初几刻不画（客户端用）。 */
    public boolean hiddenForSummon() {
        return "summon".equals(entityData.get(MISC)) && tickCount <= HIDDEN_TICKS;
    }

    @Override
    public CombatMove npcCombatMove() {
        String text = entityData.get(MOVE);
        if (!text.equals(moveFrom)) {
            moveFrom = text;
            clientMove = null;
            String[] p = text.split("\\|");
            if (p.length == 4) {
                try {
                    clientMove = new CombatMove(p[0], Long.parseLong(p[1]), Float.parseFloat(p[2]), Boolean.parseBoolean(p[3]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return clientMove;
    }

    @Override
    public FoxfireFlame foxfireFlame() {
        return FoxfireFlame.SHIRAUME;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        NpcAnimations.registerControllers(this, controllers);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
