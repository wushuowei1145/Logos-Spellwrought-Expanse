package dev.logos.dimension.companion;

import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.LoopType;
import com.geckolib.animation.object.PlayState;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.ManaVessel;
import dev.logos.runtime.SpellModifiers;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔，玩家的同伴（docs/LACERTA_COMPANION.md）：沉睡了数百年的太古赤焰真龙，被你营火上的烤肉香勾下山，自己选了当你的骑士。
 * 骨子里是真龙的高傲，对你三分傲七分娇，尾巴和翅膀永远先出卖她。每位同伴都有的在 {@link CompanionEntity}；这里是她自己的：
 * <ul>
 *   <li>数值（{@link LacertaStats}）：顶级前排，防御向继承分段上升不封顶，超出原版上限的护甲、韧性、魔咒保护挨打时再折减伤；
 *       魔力、法术强度刻意压低（她是 Logos 的 {@link ManaVessel}，魔力只用来放掌心赤焰）。</li>
 *   <li>炽焰长剑（{@link LacertaBlade}），打法在 {@link LacertaMind}：全招式按处境轮转、赤龙威压拉仇恨、够不着的用掌心赤焰（{@link #throwFlame}）。</li>
 *   <li>真龙之鳞：不怕火和岩浆（实体类型 fireImmune）、击退抗性 0.6、龙炎只受四分之一（实体标签 {@code dragonfire_kin}）、不摔伤；
 *       掉下去时张开翅膀滑翔（{@link #glide}）。</li>
 *   <li>翅膀：狭窄的地方收起来，出来再展开（{@link #tickWings}，客户端的 {@code wings} 控制器排在最后）。</li>
 *   <li>睡觉和陪你坐着（{@link LacertaRest}）：按羁绊四档睡姿；你站着不动时在你身边侧坐（主状态 {@code sit}）。</li>
 *   <li>不会死：撑不住时坠入龙眠——挨下这一下（受击），演完「最后的骑士」（{@code death}），化作樱粉赤金的火星飘回龙鳞里歇着（{@link #withdrawing}）。</li>
 * </ul>
 * 召唤和收回用誓约逆鳞（{@link DragonScaleItem}）。表情和小动作在 {@link LacertaMood}，对话在 {@link LacertaTalk}。
 */
public class LacertaCompanion extends CompanionEntity implements ManaVessel {
    public static final String ID = "lacerta";
    public static final Identifier ASSET = LogosDimension.id("lacerta_companion");
    /** 巨龙形态的模型、动画、贴图（{@link LacertaDragon}）。 */
    public static final Identifier DRAGON_ASSET = LogosDimension.id("lacerta_dragon_beast");
    /**
     * 定义里的成长只用到她自己的基础值（属性的底），生命、护甲、韧性、攻击的契印成长和继承按 {@link LacertaStats} 自己算
     * （{@link #statParts}）。
     */
    public static final CompanionDef.Growth GROWTH = new CompanionDef.Growth(LacertaStats.HEALTH, LacertaStats.ARMOR, LacertaStats.TOUGHNESS,
            LacertaStats.ATTACK, LacertaStats.POWER, LacertaStats.SEAL_HEALTH, LacertaStats.SEAL_ARMOR, LacertaStats.SEAL_TOUGHNESS,
            LacertaStats.SEAL_POWER, LacertaStats.BOND_HEALTH, LacertaStats.BOND_ATTACK, LacertaStats.BOND_POWER, .5, .125, 2, 9);
    /** 龙眠多久（5 分钟）；契印（鳞之契）面板上画几枚（盖得再多也照样长，见 {@link LacertaStats}）。 */
    public static final int REST_TICKS = 6000, MAX_SEALS = 9;
    /** 入场（模型 summon，3.7 秒）多长、落地在第几刻（land 标记 0.9 秒）。 */
    static final int SUMMON_TICKS = 74, SUMMON_LAND = 18;
    /** 龙眠：挨了致命一下以后停多久（受击）、「最后的骑士」多长（模型 death 5.8 秒）、化作火星散去多久。 */
    static final int WITHDRAW_HURT = 10, DEATH_TICKS = 116, WITHDRAW_FADE = 24;
    /** 主控制器换状态过渡几刻：待机左手叉腰，走路两手摆，2 刻时一起步手就跳过去（第 14 版评审）。 */
    static final int MAIN_TRANSITION = 5;
    /** 她的颜色：珊瑚红、熔金。 */
    static final int CORAL = 0xE0566B, GOLD = 0xF2B544;
    /** 展翼滑翔：掉了这么多格以后张开翅膀，往下最快这么快（格/刻）。 */
    static final double GLIDE_AFTER = 3, GLIDE_FALL = -.22;
    /** 掌心赤焰：花她多少魔力、多久一发、推出去以后第几刻出手（flame_throw 的 throw 标记 0.32 秒）。 */
    static final double FLAME_COST = 30;
    static final int FLAME_COOLDOWN = 60, FLAME_RELEASE = 6, FLAME_TICKS = 18;
    /** 收翅、展翅动画多长（刻）。 */
    static final int WINGS_FOLD = 24, WINGS_UNFOLD = 20;
    /** 整个人由 misc 动画摆的（入场、睡、倒下）：这时客户端不叠视线，翅膀控制器不管。 */
    static final Set<String> WHOLE_BODY = Set.of("summon", "death", "sleep_watch", "sleep", "sleep_rest", "sleep_together_down", "sleep_together",
            "sleep_together_up", "catch_hand", "catch_embrace");

    private static final EntityDataAccessor<Boolean> SWORD = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> SWORD_AT = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Boolean> WINGS_FOLDED = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> WINGS_AT = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Boolean> SITTING = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);
    /** 巨龙形态；在喷龙炎、从哪一刻开始（或停下）。 */
    private static final EntityDataAccessor<Boolean> DRAGON = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> BREATH = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> BREATH_AT = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.LONG);
    /** 龙炎往哪喷（yaw、pitch，度）：客户端沿它画火。 */
    private static final EntityDataAccessor<Float> BREATH_YAW = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BREATH_PITCH = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.FLOAT);
    /** 护落时抱着谁（实体 ID，-1 没有）、抱住还是拉住手、她在你哪一侧（yaw）：客户端照你在画面上的位置画她（{@link #carryOffset}）。 */
    private static final EntityDataAccessor<Integer> CARRY = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> CARRY_HUG = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> CARRY_SIDE = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.FLOAT);

    final LacertaBlade blade = new LacertaBlade(this);
    final LacertaRest rest = new LacertaRest(this);
    final LacertaFlight flight = new LacertaFlight(this);
    final LacertaDragon dragon = new LacertaDragon(this);
    final LacertaRide ride = new LacertaRide(this);
    /** 想以巨龙登场却变不了（太挤、魔力不够）：入场后嘀咕一句。 */
    private @Nullable String appearComplaint;
    /** 客户端：巨龙离地多少刻了（飞的动画要离地几刻才换，台阶不算）；飞的动画换到哪一段、哪一刻换的。 */
    private int airTicks;
    private String flyClip = "fly_glide";
    private int flyClipAt;
    private CompanionStats.Parts parts = LacertaStats.parts(new CompanionStats.Owner(0, 0, 0, 0, 1), 0, 0);
    private CompanionStats.Part power = new CompanionStats.Part(LacertaStats.POWER, 0, 0), manaParts = new CompanionStats.Part(LacertaStats.MANA, 0, 0);
    private float spellPower = (float) LacertaStats.POWER;
    private double mana = LacertaStats.MANA, maxMana = LacertaStats.MANA, regen = LacertaStats.REGEN;
    private long summonAt = -1, withdrawAt = -1, lastFight = -10_000, flameAt = -10_000, nextFlame;
    private @Nullable LivingEntity flameTarget;

    public LacertaCompanion(EntityType<? extends LacertaCompanion> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SWORD, false);
        builder.define(SWORD_AT, -1000L);
        builder.define(WINGS_FOLDED, false);
        builder.define(WINGS_AT, -1000L);
        builder.define(SITTING, false);
        builder.define(FLYING, false);
        builder.define(DRAGON, false);
        builder.define(BREATH, false);
        builder.define(BREATH_AT, -1000L);
        builder.define(BREATH_YAW, 0f);
        builder.define(BREATH_PITCH, 0f);
        builder.define(CARRY, -1);
        builder.define(CARRY_HUG, false);
        builder.define(CARRY_SIDE, 0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, LacertaStats.HEALTH).add(Attributes.MOVEMENT_SPEED, .3)
                .add(Attributes.FOLLOW_RANGE, 48).add(Attributes.ARMOR, LacertaStats.ARMOR).add(Attributes.ARMOR_TOUGHNESS, LacertaStats.TOUGHNESS)
                .add(Attributes.KNOCKBACK_RESISTANCE, LacertaStats.KNOCKBACK).add(Attributes.STEP_HEIGHT, 1).add(Attributes.ATTACK_DAMAGE, 1);
    }

    @Override
    public CompanionDef def() {
        return Companions.LACERTA_DEF;
    }

    @Override
    protected CompanionMood createMood() {
        return new LacertaMood(this);
    }

    @Override
    protected CompanionMind createMind() {
        return new LacertaMind(this);
    }

    @Override
    public @Nullable LacertaMind mind() {
        return (LacertaMind) super.mind();
    }

    LacertaMood lacertaMood() {
        return (LacertaMood) mood;
    }

    @Override
    protected int summonTicks() {
        return SUMMON_TICKS;
    }

    @Override
    public Identifier npcAnimationAsset() {
        return isDragonForm() ? DRAGON_ASSET : ASSET;
    }

    @Override
    public int npcMainTransition() {
        return MAIN_TRANSITION;
    }

    // ---------------------------------------------------------------- 数值

    @Override
    protected CompanionStats.Parts statParts(CompanionStats.Owner owner, int seals, int stage) {
        parts = LacertaStats.parts(owner, seals, stage);
        return parts;
    }

    /** 生命、攻击、护甲、韧性的三部分（面板用；护甲、韧性是超出原版上限之前的真实值）。 */
    CompanionStats.Parts parts() {
        return parts;
    }

    @Override
    protected double protectionRatio() {
        return LacertaStats.tankRatio(seals());
    }

    /** 超出原版上限的魔咒保护、护甲、韧性：挨打时再折一点减伤（绕过护甲的伤害不算护甲那一份）。 */
    @Override
    protected float reduceDamage(DamageSource source, float damage, float protection) {
        float d = LacertaStats.afterProtection(damage, protection);
        double k = isDragonForm() ? 2 : 1;
        if (!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR)) {
            d *= (float) (1 - LacertaStats.overflow(parts.armor().total() * k, parts.toughness().total() * k));
        }
        // 巨龙：同末影龙的躯干，挨的每一下只受四分之一
        if (isDragonForm()) d *= (float) LacertaDragon.TAKEN;
        return d;
    }

    @Override
    protected void statsApplied(Player owner, int seals, int stage) {
        power = LacertaStats.power(CompanionStats.spellPower(owner, "spell_power", "arcane_spell_power"), seals, stage);
        spellPower = (float) power.total();
        CompanionStats.set(this, SpellModifiers.SPELL_POWER, spellPower - 1);
        manaParts = LacertaStats.mana(CompanionStats.lasting(owner, SpellModifiers.MAX_MANA), seals, stage);
        maxMana = manaParts.total();
        regen = LacertaStats.regen(seals, stage);
        if (mana > maxMana) mana = maxMana;
    }

    public float spellPower() {
        return spellPower;
    }

    CompanionStats.Part powerParts() {
        return power;
    }

    CompanionStats.Part manaParts() {
        return manaParts;
    }

    double regenPerSecond() {
        return regen;
    }

    @Override
    public double mana() {
        return mana;
    }

    @Override
    public double maxMana() {
        return maxMana;
    }

    @Override
    public double receiveMana(double amount) {
        double took = Math.max(0, Math.min(amount, maxMana - mana));
        mana += took;
        return took;
    }

    /** 测试用：魔力加满。 */
    public void topUp() {
        mana = maxMana;
    }

    // ---------------------------------------------------------------- 剑、翅膀、坐着（同步给客户端）

    void setSwordShown(boolean on) {
        if (entityData.get(SWORD) == on) return;
        entityData.set(SWORD, on);
        entityData.set(SWORD_AT, level().getGameTime());
    }

    /** 剑在不在手上（客户端按它和变化的那一刻画凝聚、消散）。 */
    public boolean swordShown() {
        return entityData.get(SWORD);
    }

    public long swordChangedAt() {
        return entityData.get(SWORD_AT);
    }

    public boolean wingsFolded() {
        return entityData.get(WINGS_FOLDED);
    }

    private void setWingsFolded(boolean on) {
        if (entityData.get(WINGS_FOLDED) == on) return;
        entityData.set(WINGS_FOLDED, on);
        entityData.set(WINGS_AT, level().getGameTime());
    }

    void setSitting(boolean on) {
        if (entityData.get(SITTING) != on) entityData.set(SITTING, on);
    }

    /** 坐着（主状态 {@code sit}：日系的淑女侧坐）。 */
    @Override
    public boolean isNpcSitting() {
        return entityData.get(SITTING);
    }

    /** 在飞（{@link LacertaFlight}；关了重力，客户端主状态播飞的动画）。 */
    public boolean isFlying() {
        return entityData.get(FLYING);
    }

    void setFlying(boolean on) {
        if (entityData.get(FLYING) != on) entityData.set(FLYING, on);
    }

    /** 护落时抱着 {@code id}（-1 放开）：{@code hug} 抱住，不然拉住手；她在你 {@code sideYaw} 那一侧。 */
    void setCarry(int id, boolean hug, float sideYaw) {
        entityData.set(CARRY, id);
        entityData.set(CARRY_HUG, hug);
        entityData.set(CARRY_SIDE, sideYaw);
    }

    public int carryingId() {
        return entityData.get(CARRY);
    }

    public boolean carryHug() {
        return entityData.get(CARRY_HUG);
    }

    public float carrySide() {
        return entityData.get(CARRY_SIDE);
    }

    /**
     * 护落时她相对你的位置（她在你 {@code sideYaw} 那一侧、面朝你）：抱住在你身前，胸口对着胸口；拉住手在你身前偏上，握着你举起的手。
     * 服务端按它挪她，客户端按你在画面上的位置画她（不然她的位置同步、插值比你的画面慢几刻，往下降时总在你上方）。
     */
    public static Vec3 carryOffset(boolean hug, float sideYaw) {
        Vec3 side = Vec3.directionFromRotation(0, sideYaw);
        return hug ? side.scale(.45).add(0, .3, 0) : side.scale(.4).add(0, 1.25, 0);
    }

    /**
     * 飞着时主状态。人形：水平快过 {@link LacertaFlight#FLY_MOVE_SPEED} 格/秒播 {@code fly_move}，不然 {@code fly_hover}。
     * 巨龙（飞着，或离地几刻以上：被你骑着飞、收了重力往下滑）：{@link #dragonFlyClip}。
     */
    @Override
    public String npcMainOverride() {
        if (isDragonForm()) return isFlying() || airTicks > 3 && !isInWater() ? dragonFlyClip() : null;
        if (!isFlying()) return null;
        return Math.hypot(getX() - xo, getZ() - zo) * 20 > LacertaFlight.FLY_MOVE_SPEED ? "fly_move" : "fly_hover";
    }

    /**
     * 巨龙在天上播哪一段（末影龙式）：往下扎得快 {@code fly_dive}，转得快 {@code fly_bank_left / right}，几乎不动 {@code fly_hover}，
     * 往上 {@code fly_flap}，不然 {@code fly_glide}。一段至少播 {@link #FLY_CLIP_HOLD} 刻再换，免得来回闪。
     */
    private String dragonFlyClip() {
        double h = Math.hypot(getX() - xo, getZ() - zo), vy = getY() - yo;
        float turn = net.minecraft.util.Mth.wrapDegrees(yBodyRot - yBodyRotO);
        String want = dragonFlyClip(h, vy, turn);
        if (!want.equals(flyClip) && tickCount - flyClipAt >= FLY_CLIP_HOLD) {
            flyClip = want;
            flyClipAt = tickCount;
        }
        return flyClip;
    }

    /** 巨龙飞的那一段：水平每刻 {@code h} 格、往上 {@code vy} 格、每刻转 {@code turn} 度。 */
    static String dragonFlyClip(double h, double vy, float turn) {
        if (vy < -.3 && h > .25) return "fly_dive";
        if (Math.abs(turn) > 3.5 && h > .3) return turn > 0 ? "fly_bank_right" : "fly_bank_left";
        if (h < .12 && Math.abs(vy) < .15) return "fly_hover";
        if (vy > .04) return "fly_flap";
        return "fly_glide";
    }

    static final int FLY_CLIP_HOLD = 8;

    // ---------------------------------------------------------------- 巨龙形态（LacertaDragon）

    public boolean isDragonForm() {
        return entityData.get(DRAGON);
    }

    void setDragonForm(boolean on) {
        if (entityData.get(DRAGON) != on) entityData.set(DRAGON, on);
        refreshDimensions();
    }

    /** 在喷龙炎（客户端：吐息控制器按它和 {@link #breathChangedAt} 播三段）。 */
    public boolean breathingFire() {
        return entityData.get(BREATH);
    }

    public long breathChangedAt() {
        return entityData.get(BREATH_AT);
    }

    void setBreathing(boolean on, long at) {
        entityData.set(BREATH, on);
        entityData.set(BREATH_AT, at);
    }

    /** 龙炎往哪喷（单位向量）。 */
    public Vec3 breathDir() {
        return Vec3.directionFromRotation(entityData.get(BREATH_PITCH), entityData.get(BREATH_YAW));
    }

    void setBreathDir(Vec3 dir) {
        entityData.set(BREATH_YAW, (float) (net.minecraft.util.Mth.atan2(-dir.x, dir.z) * net.minecraft.util.Mth.RAD_TO_DEG));
        entityData.set(BREATH_PITCH, (float) (-net.minecraft.util.Mth.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)) * net.minecraft.util.Mth.RAD_TO_DEG));
    }

    @Override
    public net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        return isDragonForm() ? LacertaDragon.SIZE : super.getDefaultDimensions(pose);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DRAGON.equals(key)) {
            refreshDimensions();
            // 换了模型和动画文件：所有控制器从头来（不然还在播人形那份动画）
            if (level().isClientSide()) {
                getAnimatableInstanceCache().getManagerForId(getId()).getAnimationControllers().values().forEach(AnimationController::reset);
            }
        }
    }

    @Override
    protected void clientTick() {
        super.clientTick();
        airTicks = onGround() || isInWater() ? 0 : airTicks + 1;
    }

    // ---------------------------------------------------------------- 骑巨龙（LacertaRide）

    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        if (isDragonForm() && getFirstPassenger() instanceof Player p) return p;
        return super.getControllingPassenger();
    }

    @Override
    protected boolean canAddPassenger(net.minecraft.world.entity.Entity passenger) {
        return isDragonForm() && getPassengers().isEmpty();
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(net.minecraft.world.entity.Entity passenger, net.minecraft.world.entity.EntityDimensions dims, float scale) {
        if (!isDragonForm()) return super.getPassengerAttachmentPoint(passenger, dims, scale);
        return new Vec3(0, LacertaRide.SEAT_UP, LacertaRide.SEAT_FORWARD).yRot(-getYRot() * net.minecraft.util.Mth.DEG_TO_RAD);
    }

    @Override
    protected void tickRidden(Player controller, Vec3 input) {
        super.tickRidden(controller, input);
        ride.tick(controller);
    }

    @Override
    protected Vec3 getRiddenInput(Player controller, Vec3 self) {
        return ride.input(controller);
    }

    @Override
    protected float getRiddenSpeed(Player controller) {
        return ride.speed(controller);
    }

    @Override
    protected void removePassenger(net.minecraft.world.entity.Entity passenger) {
        super.removePassenger(passenger);
        ride.dismounted(passenger);
    }

    /** 巨龙形态时空手右键：骑上去（潜行右键照样说话）。 */
    @Override
    protected net.minecraft.world.@Nullable InteractionResult interactSpecial(ServerPlayer sp) {
        if (!isDragonForm() || sp.isShiftKeyDown() || !sp.getMainHandItem().isEmpty() || isVehicle() || dragon.appearing()) return null;
        sp.startRiding(this);
        rides++;
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    @Override
    public boolean curled() {
        return WHOLE_BODY.contains(misc()) && !"summon".equals(misc());
    }

    @Override
    protected @Nullable LivingEntity aimed() {
        return blade.aimed();
    }

    @Override
    protected boolean busyHands() {
        return blade.drawn() || throwing();
    }

    // ---------------------------------------------------------------- 每刻

    /** 入场：上次是巨龙（你在对话里让她化身的）就以巨龙登场；这里太挤、你的魔力不够就以人形来，入场后嘀咕一句。 */
    @Override
    void startSummon() {
        ServerPlayer p = ownerPlayer();
        if (p != null && level() instanceof ServerLevel level && LacertaDays.of(p).dragonForm()) {
            String why = dragon.cannotTransform(level, p);
            if (why == null) {
                dragon.appear();
                return;
            }
            appearComplaint = "appear_" + why;
        }
        summonAt = level().getGameTime();
        super.startSummon();
    }

    @Override
    protected boolean tickSpecial(ServerLevel level, long now) {
        if (withdrawAt < 0) return false;
        tickWithdraw(level, now);
        return true;
    }

    @Override
    protected void tickActions(ServerLevel level, long now, @Nullable ServerPlayer p) {
        if (summonAt >= 0) summonEffects(level, now - summonAt);
        if (appearComplaint != null && summonAt < 0 && p != null) {
            CompanionLines.say(this, p, appearComplaint, 40);
            appearComplaint = null;
        }
        mana = Math.min(maxMana, mana + regen / 20);
        blade.tick(level);
        tickFlame(level, now);
        if (p != null) rest.tick(level, p, now);
        flight.tick(level, p, now);
        dragon.tick(level, p, now);
        glide();
        if (tickCount % 10 == 0) tickWings();
    }

    /** 入场：从空中俯冲急刹落地，落地那一下脚下炸开一圈龙炎、扬起风。 */
    private void summonEffects(ServerLevel level, long t) {
        Vec3 at = position();
        if (t == 0) level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, .7f, 1.5f);
        if (t == SUMMON_LAND) {
            FoxfireCue.burst(level, at.add(0, .3, 0), FoxfireFlame.DRAGON, 1.3f);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, at.x, at.y + .1, at.z, 14, .6, .05, .6, .04);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, .9f, 1.2f);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.ARMOR_EQUIP_NETHERITE.value(), SoundSource.NEUTRAL, .8f, 1.1f);
        }
        if (t > SUMMON_TICKS) summonAt = -1;
    }

    /** 展翼滑翔：掉了 {@link #GLIDE_AFTER} 格以上（不在水里、没在坠星）就张开翅膀慢慢往下飘。 */
    private void glide() {
        if (isFlying() || onGround() || isInWater() || isPassenger() || fallDistance < GLIDE_AFTER) return;
        var m = blade.move();
        if (m != null && "skyfall_judgement".equals(m.name())) return;
        Vec3 v = getDeltaMovement();
        if (v.y < GLIDE_FALL) {
            setDeltaMovement(v.x, GLIDE_FALL, v.z);
            hurtMarked = true;
        }
    }

    /**
     * 翅膀（docs/LACERTA_COMPANION.md 第 7 节）：头顶 4 格内有实心顶、或左右 1 格都是墙（走廊、矿道），以及坐船、坐矿车、在水里时收起来；
     * 拿着剑在打的时候、出来到开阔的地方展开。
     */
    private void tickWings() {
        if (isDragonForm()) {
            setWingsFolded(false);
            return;
        }
        boolean fold = !blade.drawn() && !isFlying() && (isPassenger() || isInWater() || cramped());
        setWingsFolded(fold);
    }

    /** 狭窄的地方：头顶 4 格内有实心的顶，或左右两边 1 格内都是墙。 */
    boolean cramped() {
        BlockPos feet = blockPosition();
        Level level = level();
        for (int dy = 2; dy <= 4; dy++) {
            BlockPos up = feet.above(dy);
            if (level.getBlockState(up).isCollisionShapeFullBlock(level, up)) return true;
        }
        var side = net.minecraft.core.Direction.fromYRot(yBodyRot).getClockWise();
        BlockPos l = feet.above().relative(side), r = feet.above().relative(side.getOpposite());
        return level.getBlockState(l).isCollisionShapeFullBlock(level, l) && level.getBlockState(r).isCollisionShapeFullBlock(level, r);
    }

    // ---------------------------------------------------------------- 掌心赤焰（龙炎弹）

    /** 正在推掌放龙炎弹。 */
    boolean throwing() {
        return level().getGameTime() - flameAt < FLAME_TICKS;
    }

    /** 掌心赤焰现在放得出：冷却好了、魔力够、没在推掌。 */
    boolean flameReady(long now) {
        return now >= nextFlame && mana >= FLAME_COST && !throwing();
    }

    /** 左手往后一引、掌心朝前推出去（misc 的 {@code flame_throw}），第 {@link #FLAME_RELEASE} 刻龙炎弹出手。 */
    boolean throwFlame(LivingEntity target) {
        long now = level().getGameTime();
        if (!flameReady(now)) return false;
        flameAt = now;
        nextFlame = now + FLAME_COOLDOWN;
        flameTarget = target;
        mood.play("flame_throw", FLAME_TICKS);
        getLookControl().setLookAt(target, 60, 60);
        return true;
    }

    private void tickFlame(ServerLevel level, long now) {
        if (flameTarget == null || now - flameAt != FLAME_RELEASE) return;
        LivingEntity t = flameTarget;
        flameTarget = null;
        if (!t.isAlive() || mana < FLAME_COST) return;
        Spell sp = LacertaFlame.spell(level.getServer(), ownerPlayer() != null && bond(ownerPlayer()).pvp());
        if (sp == null) return;
        lookAt(t, 360, 360);
        yBodyRot = getYRot();
        setYHeadRot(getYRot());
        // 弹道从眼睛出去，对准它身子的中间
        Vec3 eye = getEyePosition(), aim = t.position().add(0, t.getBbHeight() * .5, 0).subtract(eye);
        setXRot((float) -Math.toDegrees(Math.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z))));
        mana -= FLAME_COST;
        Casting.castAs(this, sp, FLAME_COST, 0, t, null, true);
        flames++;
    }

    /** 巨龙形态下被收回、你下线、换维度：记着下次以巨龙登场（接入方案 5.4）。 */
    void rememberForm(@Nullable ServerPlayer p) {
        if (p != null && isDragonForm()) p.setData(Companions.LACERTA_DAYS, LacertaDays.of(p).withDragonForm(true));
    }

    // ---------------------------------------------------------------- 龙眠（代替死去）

    public boolean withdrawing() {
        return withdrawAt >= 0;
    }

    /**
     * 坠入龙眠（第 6.5 节）：致命的一下不死。先挨这一下（受击），再演「最后的骑士」，然后整个人散作樱粉与赤金的火星飘回龙鳞里，
     * 歇 5 分钟。这几秒里打不到、不动。由 {@link Companions} 的死亡事件调用（取消死亡），命令、测试也用。
     */
    void startWithdraw(ServerLevel level) {
        if (withdrawing()) return;
        withdrawAt = level.getGameTime();
        // 巨龙撑不住：先变回人形，再演「最后的骑士」
        if (isDragonForm()) dragon.revert(level, ownerPlayer());
        setHealth(1);
        if (flight.catching()) flight.release(level, ownerPlayer(), withdrawAt, false);
        flight.land();
        blade.drop();
        rest.rise(level, true);
        stopEating();
        setMove("");
        getNavigation().stop();
        setDeltaMovement(Vec3.ZERO);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.ENDER_DRAGON_HURT, SoundSource.NEUTRAL, .5f, 1.6f);
    }

    private void tickWithdraw(ServerLevel level, long now) {
        getNavigation().stop();
        setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
        long t = now - withdrawAt;
        if (t == WITHDRAW_HURT) mood.play("death", DEATH_TICKS + WITHDRAW_FADE + 20);
        long fade = t - WITHDRAW_HURT - DEATH_TICKS;
        if (fade == 0) {
            ServerPlayer p = ownerPlayer();
            if (p != null) CompanionLines.say(this, p, "withdraw", 100);
            Dissolving.entity(this, DissolveShape.parse("up"), WITHDRAW_FADE, CORAL | Dissolving.PETALS);
            FoxfireCue.burst(level, position().add(0, .8, 0), FoxfireFlame.DRAGON, 1.2f);
            level.sendParticles(new DustParticleOptions(GOLD, 1.2f), getX(), getY() + .8, getZ(), 24, .4, .6, .4, 0);
        }
        if (fade >= WITHDRAW_FADE) {
            ServerPlayer p = ownerPlayer();
            if (p != null) {
                Companions.fell(p, this);
                DragonScaleItem.resting(p, def().restTicks());
            }
            withdrawn++;
            discard();
        }
    }

    /** 测试用：让她坠入龙眠。 */
    public void withdrawForTest() {
        if (level() instanceof ServerLevel level) startWithdraw(level);
    }

    @Override
    protected boolean immune() {
        return withdrawing();
    }

    @Override
    public boolean isPushable() {
        return !withdrawing() && super.isPushable();
    }

    @Override
    protected boolean blocked(ServerLevel level, DamageSource source) {
        return blade.tryBlock(level, source);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) {
            fought(level.getGameTime());
            lacertaMood().hit(level.getGameTime());
        }
        return hurt;
    }

    void fought(long now) {
        lastFight = now;
    }

    long lastFight() {
        return lastFight;
    }

    @Override
    protected void onDie() {
        blade.drop();
    }

    // ---------------------------------------------------------------- 吃东西、入场、收回、闪回的样子

    /** 咽下去：鼻尖一小团满足的暖烟（她不打嗝，模型 eat 的 puff 标记）。 */
    @Override
    protected void swallowed(ServerLevel level) {
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, .12f, 1.9f);
    }

    @Override
    protected void appearBurst(ServerLevel level, Vec3 at) {
        level.sendParticles(new DustParticleOptions(CORAL, 1.4f), at.x, at.y + 1.5, at.z, 20, .5, .8, .5, 0);
    }

    /** 收回：巨龙形态下（不管是不是她自己变的）记着下次以巨龙登场；收住龙炎、放你下来（在半空就护着你这一摔）。 */
    @Override
    protected void onLeave() {
        blade.drop();
        rememberForm(ownerPlayer());
        if (level() instanceof ServerLevel level) {
            rest.rise(level, true);
            dragon.stopBreath(level);
        }
        ejectPassengers();
    }

    @Override
    protected void dissolveAway(int ticks) {
        Dissolving.entity(this, DissolveShape.parse("down"), ticks, CORAL | Dissolving.PETALS);
    }

    @Override
    public void puff(ServerLevel level, Vec3 at) {
        FoxfireCue.burst(level, at, FoxfireFlame.DRAGON, .7f);
        level.sendParticles(new DustParticleOptions(CORAL, 1.1f), at.x, at.y, at.z, 10, .25, .35, .25, 0);
    }

    @Override
    public void blinkSound(ServerLevel level, Vec3 at) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, .5f, 1.6f);
    }

    // ---------------------------------------------------------------- 信物

    @Override
    protected boolean blankToken(ItemStack held) {
        return DragonScaleItem.isBlank(held);
    }

    /** 盖了一枚鳞之契：她凑近龙鳞轻吹一口龙息烙下纹路，庄重地单膝立誓，起身以后才发现自己太认真了。 */
    @Override
    protected void sealed(ServerPlayer sp, int seals) {
        mood.act("act_oath");
        CompanionLines.say(this, sp, "seal", 80);
        if (level() instanceof ServerLevel level) {
            FoxfireCue.burst(level, position().add(0, 1, 0), FoxfireFlame.DRAGON, .9f);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1, .8f);
        }
    }

    /** 龙鳞弄丢了：带一份她爱吃的来，她鼓着嘴又从心口拔下一片塞给你。 */
    @Override
    protected boolean remake(ServerPlayer sp, ItemStack held) {
        if (DragonScaleItem.carries(sp) || CompanionFood.taste(ID, held) != CompanionFood.Taste.FAVORITE) return false;
        if (!sp.getAbilities().instabuild) held.shrink(1);
        ItemStack scale = DragonScaleItem.bound(sp);
        if (!sp.getInventory().add(scale)) sp.drop(scale, false);
        mood.pouted(level().getGameTime());
        CompanionLines.say(this, sp, "remake", 80);
        return true;
    }

    // ---------------------------------------------------------------- 说话

    @Override
    public CompanionSheet talkSheet(ServerPlayer p) {
        return LacertaTalk.sheet(this, p);
    }

    @Override
    public List<CompanionTalk.Line> talkGreeting(ServerPlayer p) {
        return LacertaTalk.greeting(this, p);
    }

    @Override
    public CompanionTalk.Reply talkPick(ServerPlayer p, String option) {
        return LacertaTalk.pick(this, p, option);
    }

    @Override
    protected void talkFinished(ServerPlayer p, boolean bye) {
        LacertaTalk.ended(this, p, bye);
    }

    @Override
    public void talkInterrupted(ServerPlayer p) {
        CompanionLines.say(this, p, "talk.danger", 50);
    }

    @Override
    public CompanionTalk.@Nullable Line talkBack(ServerPlayer p) {
        mood.talk("face_poise", level().getGameTime(), 60);
        return LacertaTalk.line("pouch_back");
    }

    /** 角色面板、友人帐里展示的那一位（客户端临时造的）：叉腰站着，表情 {@code face}。 */
    public void showcase(String face) {
        entityData.set(MISC, face);
        entityData.set(MOVE, "");
    }

    /** 展示的那一位手里握着炽焰长剑（已经凝好的，不播凝出来的过程）。 */
    public void showcaseSword() {
        entityData.set(SWORD, true);
        entityData.set(SWORD_AT, -1000L);
    }

    // ---------------------------------------------------------------- 动画

    private static final RawAnimation FOLD = RawAnimation.begin().then("wings_fold", LoopType.HOLD_ON_LAST_FRAME);
    private static final RawAnimation FOLDED = RawAnimation.begin().then("wings_folded", LoopType.LOOP);
    private static final RawAnimation UNFOLD = RawAnimation.begin().then("wings_unfold", LoopType.PLAY_ONCE);
    /** 巨龙的龙炎三段（只动颈首、喉甲，叠在主状态上）。 */
    private static final RawAnimation BREATH_IN = RawAnimation.begin().then("breath_start", LoopType.HOLD_ON_LAST_FRAME);
    private static final RawAnimation BREATH_LOOP = RawAnimation.begin().then("breath_loop", LoopType.LOOP);
    private static final RawAnimation BREATH_OUT = RawAnimation.begin().then("breath_end", LoopType.PLAY_ONCE);

    /** 通用的 NPC 控制器之后再加一个翅膀的：收着时盖过所有动画的翅膀（入场、睡、倒下这些整身动作自己摆翅膀，不管）。 */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
        controllers.add(new AnimationController<LacertaCompanion>("wings", 3, test -> {
            if (isDragonForm()) return PlayState.STOP;
            String misc = npcMiscAnimation();
            if (misc != null && WHOLE_BODY.contains(misc)) return PlayState.STOP;
            long age = level().getGameTime() - entityData.get(WINGS_AT);
            if (entityData.get(WINGS_FOLDED)) return test.setAndContinue(age < WINGS_FOLD ? FOLD : FOLDED);
            return age < WINGS_UNFOLD ? test.setAndContinue(UNFOLD) : PlayState.STOP;
        }));
        // 巨龙的龙炎：蓄压、喷、收（只动颈首，地上空中都能叠）
        controllers.add(new AnimationController<LacertaCompanion>("breath", 2, test -> {
            if (!isDragonForm()) return PlayState.STOP;
            long age = level().getGameTime() - breathChangedAt();
            if (breathingFire()) return test.setAndContinue(age < LacertaDragon.BREATH_START ? BREATH_IN : BREATH_LOOP);
            return age < LacertaDragon.BREATH_END ? test.setAndContinue(BREATH_OUT) : PlayState.STOP;
        }));
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putDouble("Mana", mana);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        mana = input.getDoubleOr("Mana", LacertaStats.MANA);
        // 飞着时存的档会带着「无重力」：读回来照常落地
        setNoGravity(false);
    }

    // ---------------------------------------------------------------- 测试用

    private static int withdrawn, flames;

    public static int withdrawn() {
        return withdrawn;
    }

    public static int flames() {
        return flames;
    }

    public static int draws() {
        return LacertaBlade.draws;
    }

    public static int landed() {
        return LacertaBlade.landed;
    }

    public static int blocks() {
        return LacertaBlade.blocks;
    }

    public static int started(String move) {
        return LacertaBlade.started(move);
    }

    public static int taunts() {
        return LacertaMind.taunts;
    }

    /** 测试用：把和主人的羁绊直接定到 {@code affection}。 */
    public void affectionForTest(ServerPlayer p, float affection) {
        setBond(p, bond(p).withAffection(affection));
    }

    /** 测试用：留在原地。 */
    public void stayForTest(boolean on) {
        setStaying(on);
    }

    /** 测试用：此刻的睡姿 / 坐姿（none / sit / watch / doze / rest / together）。 */
    public String poseForTest() {
        return rest.describePose();
    }

    /** 测试用：接住过你几次、落地保底挡下过几次。 */
    public static int catches() {
        return LacertaFlight.catches;
    }

    public static int saves() {
        return LacertaFlight.saves;
    }

    /** 测试用：变过几次身、挥过几爪（砍中几次）、喷过几次龙炎（烧到几下）、被骑过几次。 */
    public static int transforms() {
        return LacertaDragon.transforms;
    }

    public static int claws() {
        return LacertaDragon.claws;
    }

    public static int clawHits() {
        return LacertaDragon.clawHits;
    }

    public static int breaths() {
        return LacertaDragon.breaths;
    }

    public static int breathHits() {
        return LacertaDragon.breathHits;
    }

    static int rides;

    public static int rides() {
        return rides;
    }

    /** 测试用：变身（{@code auto} 当作她自己打起来变的）、变回。 */
    public boolean transformForTest(ServerPlayer p, boolean auto) {
        return level() instanceof ServerLevel level && dragon.transform(level, p, auto);
    }

    public void revertForTest(ServerPlayer p) {
        if (level() instanceof ServerLevel level) dragon.revert(level, p);
    }

    /** 测试用：龙炎朝 {@code dir} 喷、停。 */
    public boolean breathForTest(Vec3 dir) {
        return dragon.startBreath(() -> dir);
    }

    public void stopBreathForTest() {
        if (level() instanceof ServerLevel level) dragon.stopBreath(level);
    }

    public boolean clawForTest() {
        return dragon.claw(level().getGameTime());
    }

    /** 测试用：骑着时在不在天上（你的客户端那一侧）。 */
    public boolean rideAirborneForTest() {
        return ride.airborne();
    }

    /** 测试用：飞行在做什么（none / escort / pursue / catch / carry）。 */
    public String flightForTest() {
        return flight.mode().name().toLowerCase(java.util.Locale.ROOT);
    }

    public String moveForTest() {
        var m = blade.move();
        return m == null ? "" : m.name();
    }

    /** 测试用：强行定今晚的睡姿（watch / doze / rest / together，null 照羁绊）。 */
    public static void sleepForTest(@Nullable String tier) {
        LacertaRest.forceTier = tier == null ? null : LacertaRest.Tier.valueOf(tier.toUpperCase(java.util.Locale.ROOT));
    }

    /** 测试用：下一招出这一招（招式文件里的名字，null 照处境挑）。 */
    public static void forceMoveForTest(@Nullable String move) {
        LacertaMind.forceMove = move;
    }

    /** 测试用：剑是不是拔着（不看客户端的凝聚、消散）。 */
    public boolean swordDrawnForTest() {
        return blade.drawn();
    }
}
