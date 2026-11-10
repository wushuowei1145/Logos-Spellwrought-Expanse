package dev.logos.dimension.companion;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.foxfire.FoxfireBearer;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.npc.LogosMagicBridge;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcDeathPose;
import dev.logos.dimension.shiraume.FoxfireBolt;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 白梅（少女形态），玩家的同伴（docs/SHIRAUME_COMPANION.md）。
 *
 * <p>决策在 {@link CompanionMind}（效用 AI），表情和小动作在 {@link CompanionMood}，数值在 {@link CompanionStats}。
 * 她用狐火作战：远远放狐火弹，主人打强敌时给主人的手缠上淡粉狐火。
 *
 * <p>动画走附属的 NPC 驱动：走、跑、死亡由主控制器按状态播；入场、表情、小动作、蜷着睡由 {@code misc} 播；
 * 施放狐火由 {@code combat} 播。
 */
public class ShiraumeCompanion extends PathfinderMob implements NpcAnimated, FoxfireBearer {
    public static final Identifier ASSET = LogosDimension.id("shiraume_companion");
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
    /** 收回时先挥手这么多刻，再散去。 */
    static final int LEAVE_WAVE = 20, LEAVE_DISSOLVE = 12;
    /** 给主人缠的狐火：每下附带她狐火伤害的这么多，缠这么多秒，之后冷却。 */
    static final double KINDLE_SHARE = .5, KINDLE_SECONDS = 30;
    static final int KINDLE_COOLDOWN = 1200;

    private static final EntityDataAccessor<String> MISC = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(ShiraumeCompanion.class, EntityDataSerializers.STRING);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    final CompanionMood mood = new CompanionMood(this);
    private @Nullable CompanionMind mind;
    private @Nullable UUID owner;
    private float foxfireDamage = (float) CompanionStats.BASE_FOXFIRE;
    private boolean staying;
    private long leavingAt = -1;
    private long castStart = -1, kindleReady;
    private @Nullable LivingEntity castTarget;
    private boolean castForOwner, leftHand;
    private float deathYaw = Float.NaN;
    private int sinceHurt = 1000;
    private String moveFrom = "";
    private @Nullable CombatMove clientMove;

    public ShiraumeCompanion(EntityType<? extends ShiraumeCompanion> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
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
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        mind = new CompanionMind(this);
        goalSelector.addGoal(1, mind);
    }

    // ---------------------------------------------------------------- 主人

    /** 认主并按主人算一次数值。 */
    void bind(ServerPlayer player) {
        owner = player.getUUID();
        CompanionStats.apply(this, player, player.getData(Companions.BOND).tails());
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

    public @Nullable CompanionMind mind() {
        return mind;
    }

    /** 她能不能伤到这个东西：不伤玩家、主人的宠物和使魔，也不伤别的白梅。 */
    public boolean canHarm(Entity e) {
        if (e == this || e instanceof Player || e instanceof ShiraumeCompanion) return false;
        if (owner != null && e instanceof TamableAnimal pet && pet.getOwner() != null && owner.equals(pet.getOwner().getUUID())) return false;
        return owner == null || !owner.equals(LogosMagicBridge.masterOf(e));
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
        mood.act("act_wave");
    }

    public boolean leaving() {
        return leavingAt >= 0;
    }

    // ---------------------------------------------------------------- 每刻

    @Override
    public void tick() {
        super.tick();
        deathYaw = isDeadOrDying() ? NpcDeathPose.hold(this, deathYaw) : Float.NaN;
        if (level() instanceof ServerLevel level && !isDeadOrDying()) serverTick(level);
    }

    private void serverTick(ServerLevel level) {
        long now = level.getGameTime();
        sinceHurt++;
        if (leavingAt >= 0) {
            if (now - leavingAt == LEAVE_WAVE) Companions.dissolve(this, LEAVE_DISSOLVE);
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
        if (p != null && tickCount % 100 == 0) CompanionStats.apply(this, p, p.getData(Companions.BOND).tails());
        if (sinceHurt > 100 && tickCount % 40 == 0 && getHealth() < getMaxHealth()) heal(1);
        if (castStart >= 0) {
            long t = now - castStart;
            if (castTarget != null && castTarget.isAlive()) getLookControl().setLookAt(castTarget, 40, 40);
            if (t == CAST_RELEASE) release(level);
            if (t >= CAST_TICKS) {
                castStart = -1;
                castTarget = null;
                entityData.set(MOVE, "");
            }
        }
        mood.tick(now);
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

    /** 开始一次施放：对 {@code target} 放狐火弹，或（{@code forOwner}）给主人的手缠上狐火。左右手交替。 */
    boolean startCast(LivingEntity target, boolean forOwner) {
        if (casting() || !(level() instanceof ServerLevel level)) return false;
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

    // ---------------------------------------------------------------- 互动

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).isEmpty() || isDeadOrDying()) return super.mobInteract(player, hand);
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        if (!player.getUUID().equals(owner) || leaving()) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            staying = !staying;
            mood.act(staying ? "act_head_tilt" : "act_wave");
            ((ServerPlayer) player).sendOverlayMessage(Component.translatable(staying
                    ? "message.logos_dimension.companion.stay" : "message.logos_dimension.companion.follow"));
        } else {
            getNavigation().stop();
            getLookControl().setLookAt(player, 40, 40);
            mood.petted(level().getGameTime());
        }
        return InteractionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- 受伤与离场

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        Entity by = source.getEntity();
        if (by != null && (by.getUUID().equals(owner) || by instanceof ShiraumeCompanion)) {
            // 主人打她：不疼，但会闹别扭
            if (by.getUUID().equals(owner)) mood.pouted(level.getGameTime());
            return false;
        }
        if (leaving()) return false;
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) sinceHurt = 0;
        return hurt;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public void die(DamageSource source) {
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
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
        staying = input.getBooleanOr("Staying", false);
    }

    // ---------------------------------------------------------------- 动画

    void setMisc(String name) {
        if (!entityData.get(MISC).equals(name)) entityData.set(MISC, name);
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

    /** 蜷着睡（客户端用：这时不叠加视线）。 */
    public boolean curled() {
        return "sleep".equals(entityData.get(MISC));
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
