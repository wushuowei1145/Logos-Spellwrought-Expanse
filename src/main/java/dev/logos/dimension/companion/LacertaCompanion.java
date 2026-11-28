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
            "sleep_together_up");

    private static final EntityDataAccessor<Boolean> SWORD = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> SWORD_AT = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Boolean> WINGS_FOLDED = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> WINGS_AT = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Boolean> SITTING = SynchedEntityData.defineId(LacertaCompanion.class, EntityDataSerializers.BOOLEAN);

    final LacertaBlade blade = new LacertaBlade(this);
    final LacertaRest rest = new LacertaRest(this);
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
        return ASSET;
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
        if (!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR)) {
            d *= (float) (1 - LacertaStats.overflow(parts.armor().total(), parts.toughness().total()));
        }
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

    @Override
    void startSummon() {
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
        mana = Math.min(maxMana, mana + regen / 20);
        blade.tick(level);
        tickFlame(level, now);
        if (p != null) rest.tick(level, p, now);
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
        if (onGround() || isInWater() || isPassenger() || fallDistance < GLIDE_AFTER) return;
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
        boolean fold = !blade.drawn() && (isPassenger() || isInWater() || cramped());
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
        setHealth(1);
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

    @Override
    protected void onLeave() {
        blade.drop();
        if (level() instanceof ServerLevel level) rest.rise(level, true);
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

    // ---------------------------------------------------------------- 动画

    private static final RawAnimation FOLD = RawAnimation.begin().then("wings_fold", LoopType.HOLD_ON_LAST_FRAME);
    private static final RawAnimation FOLDED = RawAnimation.begin().then("wings_folded", LoopType.LOOP);
    private static final RawAnimation UNFOLD = RawAnimation.begin().then("wings_unfold", LoopType.PLAY_ONCE);

    /** 通用的 NPC 控制器之后再加一个翅膀的：收着时盖过所有动画的翅膀（入场、睡、倒下这些整身动作自己摆翅膀，不管）。 */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        super.registerControllers(controllers);
        controllers.add(new AnimationController<LacertaCompanion>("wings", 3, test -> {
            String misc = npcMiscAnimation();
            if (misc != null && WHOLE_BODY.contains(misc)) return PlayState.STOP;
            long age = level().getGameTime() - entityData.get(WINGS_AT);
            if (entityData.get(WINGS_FOLDED)) return test.setAndContinue(age < WINGS_FOLD ? FOLD : FOLDED);
            return age < WINGS_UNFOLD ? test.setAndContinue(UNFOLD) : PlayState.STOP;
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
