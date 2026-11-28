package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.ultimate.UltimateDef;
import dev.logos.runtime.ManaVessel;
import dev.logos.runtime.SpellModifiers;
import dev.logos.item.WandItem;
import java.util.List;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀，玩家的同伴（docs/KOHAKU_COMPANION.md）。魔女之森的灵猫，星辰花之都魔法院出身的炼金和法术构筑天才；懒，护短。
 * 每位同伴都有的那些在 {@link CompanionEntity}；这里是她自己的：
 * <ul>
 *   <li>长杖（{@link KohakuStaff}）：她自己的随爪之契换，包袱里你给的优先。</li>
 *   <li>魔力（她是 Logos 的 {@link ManaVessel}）：上限 = 她自己的 + 羁绊 + 继承主人的长期上限 + 手里那根杖的上限；回复按她的
 *       {@code logos:mana_regeneration}（她自己的，加上主手杖的），由这里每刻结算（Logos 只给玩家结算）。被摸头以后一阵子回得快一倍。</li>
 *   <li>魔法（{@link KohakuMagic}）：琥珀护膜、她自己的术式。伤害、治疗、护盾都按她自己的法术强度（{@link #spellPower}）。</li>
 *   <li>不会死：撑不住时她还是会倒下（{@code death}，为同伴打到了最后），倒下以后地上转开一个法阵，把她卷回爪印金镑里歇着（{@link #withdrawing}）。</li>
 * </ul>
 * 召唤和收回用爪印金镑（{@link PawSovereignItem}）。登场时地上先转开一个琥珀色的法阵，她从阵心蹦出来（模型的 {@code summon}）。
 * 决策在 {@link KohakuMind}，表情和小动作在 {@link KohakuMood}，对话在 {@link KohakuTalk}。
 */
public class KohakuCompanion extends CompanionEntity implements ManaVessel {
    public static final String ID = "kohaku";
    public static final Identifier ASSET = LogosDimension.id("kohaku_companion");
    /**
     * 她的数（第 11 节）：法师，身子单薄——生命 24、每枚爪之契 +2、羁绊每段 +2，生命只继承两成起（每枚 +4%，最多 56%）；
     * 护甲、韧性一直是 0，也不继承。法术强度 0.9 起、每枚 +0.08、羁绊每段 +0.06，再继承主人的（三成起、每枚 +8%、第九枚全部）：
     * 主人法强 1、没盖契、生疏时正好 1.2。
     */
    public static final CompanionDef.Growth GROWTH = new CompanionDef.Growth(24, 0, 0, 0, .9, 2, 0, 0, .08, 2, 0, .06, .3, .08, 1, 9,
            .2, .04, .56, false);
    /** 魔力上限：她自己的、每枚爪之契、羁绊每段；回复（每秒）：同理。都比玩家长得快。 */
    public static final double MANA = 400, SEAL_MANA = 100, BOND_MANA = 50, REGEN = 4, SEAL_REGEN = .75, BOND_REGEN = .25;
    /** 撤退以后在金镑里歇多久（5 分钟）；契印一共几枚（爪之契）。 */
    public static final int REST_TICKS = 6000, MAX_SEALS = 9;
    /** 奥义「星辰花炼成 · 凝时琥珀」（docs/ULTIMATES.md、docs/KOHAKU_COMPANION.md 第 20 节，{@link KohakuUltimate}）：冷却 35 秒、基准耗魔 280；完整版 106 刻，缩短版 42 刻。 */
    public static final UltimateDef ULTIMATE = new UltimateDef(700, 280, KohakuUltimate::full, KohakuUltimate::condensed);
    /** 被摸头以后多少刻内回魔快一倍。 */
    static final int PETTED_REGEN = 600;
    /** 登场：法阵先转开多少刻她才蹦出来；入场动画多长、落地在第几刻（与模型的 summon 一致）。 */
    static final int CIRCLE_TICKS = 24, SUMMON_TICKS = 64, SUMMON_LAND = 6;
    /** 撤退：挨了致命的一下以后停多久（受击）、倒下动画多长（模型的 death）、倒下以后法阵转多久把她卷走。 */
    static final int WITHDRAW_HURT = 10, DEATH_TICKS = 116, WITHDRAW_CIRCLE = 36;
    /** 她的颜色：琥珀（粒子、法阵、消散）。 */
    static final int AMBER = 0xE8A043;
    /** 法阵的半径（格）：登场、撤退。 */
    public static final float CIRCLE_RADIUS = 1.3f, WITHDRAW_RADIUS = 1.6f;
    /** 登场的法阵一共转多久：转开、她蹦出来落地，再收起来。 */
    static final int SUMMON_CIRCLE = CIRCLE_TICKS + SUMMON_LAND + 12;
    /** 她的炼成阵什么时候转开的（游戏刻，没有为 -1）、一共转多久：客户端照着画（{@code KohakuCircle}），不走 Logos 的领域特效（那是奥术的粉紫色）。 */
    private static final EntityDataAccessor<Long> CIRCLE_AT = SynchedEntityData.defineId(KohakuCompanion.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> CIRCLE_LENGTH = SynchedEntityData.defineId(KohakuCompanion.class, EntityDataSerializers.INT);
    /** 撑着伞没有：0 没有、1 她自己的、2 大的（把你也罩进去）。客户端照着在杖头上画伞面（{@code KohakuUmbrella}）。 */
    private static final EntityDataAccessor<Integer> UMBRELLA = SynchedEntityData.defineId(KohakuCompanion.class, EntityDataSerializers.INT);
    /** 穿哪一套（{@link KohakuOutfit}）：客户端按它隐藏别的几套。 */
    private static final EntityDataAccessor<String> OUTFIT = SynchedEntityData.defineId(KohakuCompanion.class, EntityDataSerializers.STRING);

    final KohakuStaff staff = new KohakuStaff(this);
    final KohakuMagic magic = new KohakuMagic(this);
    final KohakuMoves moves = new KohakuMoves(this);
    final KohakuLife life = new KohakuLife(this);
    private float spellPower = 1;
    private CompanionStats.Part power = new CompanionStats.Part(1, 0, 0);
    private double mana, maxMana = MANA;
    private CompanionStats.Part manaParts = new CompanionStats.Part(MANA, 0, 0);
    private long lastFight = -10_000, summonAt = -1, withdrawAt = -1, pettedAt = -10_000;

    public KohakuCompanion(EntityType<? extends KohakuCompanion> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(CIRCLE_AT, -1L);
        builder.define(CIRCLE_LENGTH, 0);
        builder.define(UMBRELLA, 0);
        builder.define(OUTFIT, KohakuOutfit.CARDIGAN.id);
    }

    public KohakuOutfit outfit() {
        return KohakuOutfit.byId(entityData.get(OUTFIT));
    }

    @Override
    public String outfitId() {
        return outfit().id;
    }

    /** 换上 {@code o}：身边一小团琥珀色的光点和一声轻响，像炼成一样一下子换好（{@code quiet}：叫出来时直接穿上，不放）。 */
    void dress(KohakuOutfit o, boolean quiet) {
        if (outfit() == o) return;
        entityData.set(OUTFIT, o.id);
        if (quiet || !(level() instanceof ServerLevel level)) return;
        level.sendParticles(new DustParticleOptions(AMBER, 1.1f), getX(), getY() + .8, getZ(), 22, .3, .5, .3, 0);
        level.sendParticles(ParticleTypes.POOF, getX(), getY() + .7, getZ(), 2, .15, .25, .15, .005);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, .6f, 1.25f);
    }

    void setUmbrella(int size) {
        if (entityData.get(UMBRELLA) != size) entityData.set(UMBRELLA, size);
    }

    /** 撑着伞没有：0 没有、1 她自己的、2 大的（把你也罩进去）。 */
    public int umbrella() {
        return entityData.get(UMBRELLA);
    }

    /** 地上转开她的炼成阵，转 {@code length} 刻。 */
    void openCircle(long now, int length) {
        entityData.set(CIRCLE_AT, now);
        entityData.set(CIRCLE_LENGTH, length);
    }

    /** 炼成阵转开了多少刻（客户端画用，含这一帧的 {@code partialTick}）；没有在转时为 -1。 */
    public float circleAge(float partialTick) {
        long at = entityData.get(CIRCLE_AT);
        if (at < 0) return -1;
        float age = level().getGameTime() - at + partialTick;
        return age < 0 || age > entityData.get(CIRCLE_LENGTH) ? -1 : age;
    }

    public int circleLength() {
        return entityData.get(CIRCLE_LENGTH);
    }

    /** 这一阵是来卷走她的（撤退）：大一点，中间升起光柱。 */
    public boolean circleWithdraws() {
        return "death".equals(misc());
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, GROWTH.health()).add(Attributes.MOVEMENT_SPEED, .3)
                .add(Attributes.FOLLOW_RANGE, 48).add(Attributes.ARMOR, 0).add(Attributes.STEP_HEIGHT, 1).add(Attributes.ATTACK_DAMAGE, 1);
    }

    @Override
    public CompanionDef def() {
        return Companions.KOHAKU_DEF;
    }

    @Override
    protected CompanionMood createMood() {
        return new KohakuMood(this);
    }

    @Override
    protected CompanionMind createMind() {
        return new KohakuMind(this);
    }

    @Override
    public @Nullable KohakuMind mind() {
        return (KohakuMind) super.mind();
    }

    @Override
    protected int summonTicks() {
        return SUMMON_TICKS;
    }

    @Override
    public Identifier npcAnimationAsset() {
        return ASSET;
    }

    // ---------------------------------------------------------------- 法术强度和魔力

    /** 她的法术强度（倍数）：她的术式由 Logos 按她身上的 {@code logos:spell_power} 自己乘；护膜、凝辉这些代码里的数乘它。 */
    public float spellPower() {
        return spellPower;
    }

    public CompanionStats.Part powerParts() {
        return power;
    }

    /** 主人的长期法术强度（不算药水）：通用的法强乘奥术法强（她的术式多是奥术）。 */
    static double ownerPower(Player owner) {
        return CompanionStats.spellPower(owner, "spell_power", "arcane_spell_power");
    }

    /** 主人的长期魔力上限（不算药水）。 */
    static double ownerMana(Player owner) {
        return CompanionStats.lasting(owner, SpellModifiers.MAX_MANA);
    }

    /** 魔力上限的三部分（不算手里的杖）：她自己的（基础 + 爪之契）、羁绊、继承。 */
    static CompanionStats.Part manaParts(double ownerMana, int seals, int stage) {
        return new CompanionStats.Part(MANA + SEAL_MANA * GROWTH.grown(seals), BOND_MANA * Math.max(0, stage), GROWTH.ratio(seals) * ownerMana);
    }

    /** 她自己的魔力回复（每秒，不算手里的杖和药水）。 */
    static double regen(int seals, int stage) {
        return REGEN + SEAL_REGEN * GROWTH.grown(seals) + BOND_REGEN * Math.max(0, stage);
    }

    public CompanionStats.Part manaParts() {
        return manaParts;
    }

    @Override
    protected void statsApplied(Player owner, int seals, int stage) {
        power = CompanionStats.power(GROWTH, ownerPower(owner), seals, stage);
        spellPower = (float) power.total();
        // 属性的基础值是 1：继承那一份修饰补上差。治疗、护盾跟着法强走
        CompanionStats.set(this, SpellModifiers.SPELL_POWER, spellPower - 1);
        CompanionStats.set(this, SpellModifiers.HEAL_POWER, spellPower - 1);
        manaParts = manaParts(ownerMana(owner), seals, stage);
        CompanionStats.set(this, SpellModifiers.MAX_MANA, manaParts.total() - SpellModifiers.MAX_MANA.value().getDefaultValue());
        CompanionStats.set(this, SpellModifiers.MANA_REGENERATION, regen(seals, stage) - SpellModifiers.MANA_REGENERATION.value().getDefaultValue());
        refreshCap();
    }

    /** 魔力上限：她身上的 {@code logos:max_mana}（她自己的 + 羁绊 + 继承）加上手里那根杖的。 */
    private void refreshCap() {
        maxMana = getAttributeValue(SpellModifiers.MAX_MANA) + WandItem.carriedMaxMana(staff.held());
        if (mana > maxMana) mana = maxMana;
    }

    /** 每秒回多少魔力：她的回复（含主手杖的、药水的）加上限的百分比回复；被摸头以后一阵子快一倍。 */
    public double regenPerSecond() {
        double r = getAttributeValue(SpellModifiers.MANA_REGENERATION) + getAttributeValue(SpellModifiers.MANA_REGENERATION_PERCENT) / 100 * maxMana;
        return level().getGameTime() - pettedAt < PETTED_REGEN ? r * 2 : r;
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

    /** 她拿得出的魔力：身上的加上杖里存的（不打的时候她把身上的往杖里充，只留三成）。 */
    public double available() {
        return mana + staff.store();
    }

    /** 花掉最多 {@code amount}；返回花了多少。 */
    double spendMana(double amount) {
        double took = Math.max(0, Math.min(amount, mana));
        mana -= took;
        return took;
    }

    void setMana(double v) {
        mana = Math.max(0, Math.min(v, maxMana));
    }

    // ---------------------------------------------------------------- 长杖

    /** 把 {@link KohakuStaff#held} 放到手里（吃东西时先不换，吃完由 {@link #restingMainHand} 换回来）。 */
    void holdStaff() {
        if (!eating()) setItemSlot(EquipmentSlot.MAINHAND, restingMainHand());
        refreshCap();
    }

    @Override
    protected ItemStack restingMainHand() {
        // 要递给你的吃的、叼来的战利品拿在右手里
        ItemStack gift = life.inHand();
        if (!gift.isEmpty()) return gift.copy();
        return staffStowed() ? ItemStack.EMPTY : staff.held().copy();
    }

    /**
     * 长杖收起来了（维护者定的）：小动作（摸头、哈气、伸懒腰……）、入场、睡着、倒下时右手要空出来，长杖化成光收起来，播完再拿出来。
     * 施法时不收。
     */
    boolean staffStowed() {
        // 凝辉：右手凝剑（手里是杖剑时直接用它砍，不收）；猫步：一跃一落，杖收着
        if (moves.busy()) return !(moves.sweeping() && staff.blade());
        if (magic.casting()) return false;
        // 坐着、趴着：长杖收起来（撑伞时杖就是伞柄，不收）
        if (life.seated()) return true;
        return withdrawing() || mood.playing() != null || mood.curled();
    }

    /** 每刻看一眼：该收起来就收，该拿出来就拿（手边闪一点琥珀色的光）。吃东西时手里是吃的，不管。 */
    private void tickStaffInHand(ServerLevel level) {
        if (eating()) return;
        ItemStack want = restingMainHand(), have = getMainHandItem();
        if (ItemStack.isSameItemSameComponents(want, have)) return;
        setItemSlot(EquipmentSlot.MAINHAND, want);
        // 拿出要递的东西、递出去：不闪光
        if (hiddenForSummon() || !life.inHand().isEmpty() || !have.isEmpty() && !ItemStack.isSameItem(have, staff.held())) return;
        Vec3 hand = getEyePosition().add(Vec3.directionFromRotation(0, yBodyRot + 40).scale(.4)).add(0, -.5, 0);
        level.sendParticles(new DustParticleOptions(AMBER, .8f), hand.x, hand.y, hand.z, 8, .1, .3, .1, 0);
        level.sendParticles(ParticleTypes.END_ROD, hand.x, hand.y, hand.z, 2, .05, .2, .05, .01);
    }

    /** 记一次「在打」。 */
    void fought(long now) {
        lastFight = now;
    }

    long lastFight() {
        return lastFight;
    }

    @Override
    protected boolean busyHands() {
        return magic.casting() || moves.busy() || !life.inHand().isEmpty();
    }

    /** 凝辉的战斗武器编号（客户端按它取网格画剑）。 */
    public static int vesperglassWeapon() {
        return KohakuMoves.VESPERGLASS.id();
    }

    /** 手里是杖剑（凝辉直接用它的剑刃砍，客户端不另外画剑）。 */
    public static boolean bladeStaff(ItemStack stack) {
        return KohakuMoves.bladeIn(stack);
    }

    /** 点杖施法时两臂跟着视线抬（杖尖对准她在打的那个）。 */
    @Override
    public boolean npcAiming() {
        return move().startsWith("cast_staff");
    }

    // ---------------------------------------------------------------- 每刻

    /** 法阵还在转开时 misc 上的名字：模型里没有这一段，什么都不播；客户端看到它就不画她（{@link #hiddenForSummon}）。 */
    static final String CIRCLE = "circle";

    @Override
    void startSummon() {
        summonAt = level().getGameTime();
        // 法阵先转开，她还没出来：不画，入场动画等法阵转开了再播
        mood.play(CIRCLE, CIRCLE_TICKS + 1);
        openCircle(summonAt, SUMMON_CIRCLE);
    }

    /** 新叫出来的她魔力是满的（读档的照存档）：先拿上杖，上限算上杖的，再加满。 */
    @Override
    void bind(ServerPlayer player) {
        super.bind(player);
        // 你上次给她换的那一套
        dress(KohakuOutfit.byId(player.getData(Companions.KOHAKU_DAYS).outfit()), true);
        staff.equipNow(player);
        mana = maxMana;
    }

    /** 法阵还在转开、她还没蹦出来。 */
    public boolean inCircle() {
        return summonAt >= 0 && level().getGameTime() - summonAt < CIRCLE_TICKS;
    }

    @Override
    public boolean hiddenForSummon() {
        return CIRCLE.equals(misc()) || super.hiddenForSummon();
    }

    @Override
    protected boolean tickSpecial(ServerLevel level, long now) {
        if (withdrawAt < 0) return false;
        tickWithdraw(level, now);
        return true;
    }

    @Override
    protected void tickWithOwner(ServerLevel level, ServerPlayer p) {
        staff.tick(level, level.getGameTime(), p);
    }

    @Override
    protected void tickActions(ServerLevel level, long now, @Nullable ServerPlayer p) {
        if (summonAt >= 0) summonEffects(level, now - summonAt);
        if (tickCount % 20 == 0) refreshCap();
        mana = Math.min(maxMana, mana + regenPerSecond() / 20);
        magic.tick(level, now);
        moves.tick(level, now);
        life.tick(level, now, p);
        tickStaffInHand(level);
    }

    /** 登场：地上转开一个琥珀色的炼成阵，转开以后她从阵心蹦出来，落地时阵收起来。 */
    private void summonEffects(ServerLevel level, long t) {
        Vec3 at = position();
        if (t == 0) {
            level.playSound(null, at.x, at.y, at.z, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.NEUTRAL, .5f, 1.6f);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1, .9f);
        }
        if (t == CIRCLE_TICKS) {
            mood.play("summon", SUMMON_TICKS);
            level.sendParticles(new DustParticleOptions(AMBER, 1.3f), at.x, at.y + .6, at.z, 26, .4, .5, .4, 0);
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + .4, at.z, 10, .3, .3, .3, .04);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1, 1.4f);
        }
        if (t == CIRCLE_TICKS + SUMMON_LAND) {
            level.playSound(null, at.x, at.y, at.z, cat().ambientSound().value(), SoundSource.NEUTRAL, .6f, 1.2f);
        }
        if (t > CIRCLE_TICKS + SUMMON_TICKS) summonAt = -1;
    }

    @Override
    protected void clientTick() {
        // 长杖杖尖偶尔落下一点琥珀色的光
        if (tickCount % 40 == 0 && !hiddenForSummon()) {
            Vec3 at = position().add(Vec3.directionFromRotation(0, yBodyRot + 50).scale(.45)).add(0, 1.25, 0);
            level().addParticle(new DustParticleOptions(AMBER, .35f), at.x, at.y, at.z, 0, -.01, 0);
        }
    }

    // ---------------------------------------------------------------- 撤退（代替死去）

    /** 撤退中：倒下、法阵把她卷走。打不到、不动。 */
    public boolean withdrawing() {
        return withdrawAt >= 0;
    }

    /**
     * 开始撤退（第 7.6 节）：致命的一下不死。先挨这一下（受击），再倒下（{@code death}：为同伴打到了最后），倒下以后地上转开一个法阵，
     * 把她卷回爪印金镑里歇着。由 {@link Companions} 的死亡事件调用（取消死亡），命令、测试也用。
     */
    void startWithdraw(ServerLevel level) {
        if (withdrawing()) return;
        withdrawAt = level.getGameTime();
        setHealth(1);
        magic.cancel();
        moves.cancel();
        life.rise(level, withdrawAt, true);
        life.dropHand(level);
        stopEating();
        setMove("");
        getNavigation().stop();
        setDeltaMovement(Vec3.ZERO);
        level.playSound(null, getX(), getY(), getZ(), cat().hurtSound().value(), SoundSource.NEUTRAL, .8f, 1.3f);
    }

    private void tickWithdraw(ServerLevel level, long now) {
        getNavigation().stop();
        setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
        long t = now - withdrawAt;
        Vec3 at = position();
        if (t == WITHDRAW_HURT) {
            // 长杖从手里掉下去（化成光）
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            mood.play("death", DEATH_TICKS + WITHDRAW_CIRCLE + 10);
        }
        long circle = t - WITHDRAW_HURT - DEATH_TICKS;
        if (circle == 0) {
            openCircle(now, WITHDRAW_CIRCLE);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.NEUTRAL, .5f, 1.5f);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1, .8f);
        }
        if (circle == WITHDRAW_CIRCLE / 2) {
            // 琥珀色的烟和光把她卷起来
            Dissolving.entity(this, DissolveShape.parse("up"), WITHDRAW_CIRCLE / 2, AMBER);
            level.sendParticles(new DustParticleOptions(AMBER, 1.4f), at.x, at.y + .5, at.z, 30, .5, .5, .5, 0);
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + .5, at.z, 12, .4, .6, .4, .05);
        }
        if (circle >= WITHDRAW_CIRCLE) {
            ServerPlayer p = ownerPlayer();
            if (p != null) {
                Companions.fell(p, this);
                PawSovereignItem.resting(p, def().restTicks());
            }
            withdrawn++;
            discard();
        }
    }

    /** 测试用：撤退回去过几次。 */
    private static int withdrawn;

    /** 测试用：让她撤退（倒下、被法阵卷走，之后歇着）。 */
    public void withdrawForTest() {
        if (level() instanceof ServerLevel level) startWithdraw(level);
    }

    public static int withdrawn() {
        return withdrawn;
    }

    /** 测试用：凝过几次护膜、护膜碎过几次、放过几页术式、赶走过几次幻翼。 */
    public static int wards() {
        return KohakuMagic.wards;
    }

    public static int wardBreaks() {
        return KohakuMagic.wardBreaks;
    }

    public static int casts() {
        return KohakuMagic.casts;
    }

    /** 测试用：她自己的那一页（{@code one_drop}、{@code starbloom}……）放过几次；你给的页放过几次。 */
    public static int casts(String page) {
        return KohakuMagic.CAST_COUNT.getOrDefault(page, 0);
    }

    public static int yoursCasts() {
        return KohakuMagic.yoursCasts;
    }

    /** 测试用：凝辉横扫过几次、打中过几个；猫步闪过几次；两个人一起闪过几次。 */
    public static int sweeps() {
        return KohakuMoves.sweeps;
    }

    public static int sweepHits() {
        return KohakuMoves.sweepHits;
    }

    public static int blinks() {
        return KohakuMoves.blinks;
    }

    public static int carries() {
        return KohakuMoves.carries;
    }

    /** 测试用：塞给你吃的几次、叼来战利品几次、认过几次地毯、在地毯上睡着加过几次羁绊、晒饱过几次。 */
    public static int foodGifts() {
        return KohakuLife.foodGifts;
    }

    public static int fetches() {
        return KohakuLife.fetches;
    }

    public static int rugClaims() {
        return KohakuLife.rugClaims;
    }

    public static int rugNaps() {
        return KohakuLife.rugNaps;
    }

    public static int sunFulls() {
        return KohakuLife.sunFulls;
    }

    /** 测试用：一起睡过几整夜、给过几次醒来的礼物、你挂机时在床上打过几次盹。 */
    public static int fullNights() {
        return KohakuSleep.fullNights;
    }

    public static int morningGifts() {
        return KohakuSleep.morningGifts;
    }

    public static int bedNaps() {
        return KohakuSleep.bedNaps;
    }

    /** 测试用：强行定睡姿（curl / loaf / belly，null 照常判）、睡哪（floor / spare / shiraume / yours，null 照常判）。 */
    public static void sleepForTest(@Nullable String pose, @Nullable String where) {
        KohakuSleep.forcePose = pose == null ? null : KohakuLife.Pose.valueOf(pose.toUpperCase(java.util.Locale.ROOT));
        KohakuSleep.forceWhere = where == null ? null : KohakuSleep.Where.valueOf(where.toUpperCase(java.util.Locale.ROOT));
    }

    /** 测试用：这一晚睡在哪（没在跟你睡为空）。 */
    public String sleepWhereForTest() {
        return life.sleep.where();
    }

    /** 测试用：给你吃的、叼战利品都必成（不看概率）。 */
    public static void luckyForTest(boolean on) {
        KohakuLife.lucky = on;
    }

    /** 测试用：此刻摆着什么姿势（none / sit / loaf / umbrella）、陪你去的是哪（beside / rug / sun / umbrella，没有为空）。 */
    public String poseForTest() {
        return life.pose().name().toLowerCase(java.util.Locale.ROOT);
    }

    public String spotForTest() {
        var plan = life.plan();
        return plan == null ? "" : plan.spot().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 测试用：晒饱了没有；直接晒到差 {@code left} 刻就饱。 */
    public boolean sunnedForTest() {
        return life.sunned();
    }

    public void sunAlmostForTest(int left) {
        life.sunAlmost(left);
    }

    /** 测试用：把和主人的羁绊直接定到 {@code affection}。 */
    public void affectionForTest(ServerPlayer p, float affection) {
        setBond(p, bond(p).withAffection(affection));
    }

    /** 测试用：认下的地毯（没有为 null）。 */
    public static net.minecraft.core.@Nullable BlockPos rugForTest(ServerPlayer p) {
        return KohakuLife.days(p).rug().map(net.minecraft.core.GlobalPos::pos).orElse(null);
    }

    /** 测试用：魔力加满、杖里充满（大术式要两边加起来才放得出）。 */
    public void topUp() {
        refreshCap();
        mana = maxMana;
        staff.setStore(staff.capacity());
    }

    /** 测试用：让她留在原地（不跟着主人走），好从远处拍。 */
    public void stayForTest(boolean on) {
        setStaying(on);
    }

    /** 测试用：你给的杖上她能放几页。 */
    public int yourPageCount() {
        return staff.yoursPages().size();
    }

    public static int phantomsScared() {
        return KohakuMind.phantomsScared;
    }

    public static int hisses() {
        return KohakuMind.hisses;
    }

    @Override
    protected boolean immune() {
        return withdrawing() || inCircle();
    }

    @Override
    public boolean isPushable() {
        return !withdrawing() && super.isPushable();
    }

    @Override
    protected void onDie() {
        magic.cancel();
        moves.cancel();
        if (level() instanceof ServerLevel level) life.dropHand(level);
    }

    /** 整个人由 misc 动画摆（睡着、倒下）：客户端这时不叠加视线。 */
    @Override
    public boolean curled() {
        String m = misc();
        return "sleep".equals(m) || "death".equals(m);
    }

    // ---------------------------------------------------------------- 摸头、入场、收回、闪回的样子

    /** 被摸头：一阵子回魔快一倍（同摸头的规矩加好感，见 {@link CompanionEntity#mobInteract}）。 */
    void petted(long now) {
        pettedAt = now;
    }

    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        boolean pat = hand == net.minecraft.world.InteractionHand.MAIN_HAND && !player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()
                && player.getUUID().equals(ownerId()) && !level().isClientSide();
        var result = super.mobInteract(player, hand);
        if (pat && result.consumesAction()) petted(level().getGameTime());
        return result;
    }

    @Override
    protected void appearBurst(ServerLevel level, Vec3 at) {
        // 现身由法阵和入场动画表现（summonEffects）
    }

    @Override
    protected void onLeave() {
        magic.cancel();
        moves.cancel();
        if (level() instanceof ServerLevel level) {
            life.rise(level, level.getGameTime(), true);
            life.dropHand(level);
        }
    }

    @Override
    protected void dissolveAway(int ticks) {
        Dissolving.entity(this, DissolveShape.parse("down"), ticks, AMBER);
    }

    @Override
    public void puff(ServerLevel level, Vec3 at) {
        level.sendParticles(new DustParticleOptions(AMBER, 1.2f), at.x, at.y, at.z, 14, .25, .4, .25, 0);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 6, .2, .3, .2, .03);
    }

    @Override
    public void blinkSound(ServerLevel level, Vec3 at) {
        level.playSound(null, at.x, at.y, at.z, cat().ambientSound().value(), SoundSource.NEUTRAL, .5f, 1.4f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, .6f, 1.6f);
    }

    // ---------------------------------------------------------------- 信物

    @Override
    protected boolean blankToken(ItemStack held) {
        return PawSovereignItem.isBlank(held);
    }

    /** 盖了一枚爪之契：她拿起金镑咬一口，确认是真金，弹一下收好，一脸得意。 */
    @Override
    protected void sealed(ServerPlayer sp, int seals) {
        mood.act("act_coin_flip");
        ((KohakuMood) mood).smug(level().getGameTime());
        CompanionLines.say(this, sp, "seal", 60);
        if (level() instanceof ServerLevel level) {
            level.sendParticles(new DustParticleOptions(AMBER, 1.2f), getX(), getY() + 1, getZ(), 22, .5, .6, .5, 0);
            level.sendParticles(ParticleTypes.END_ROD, getX(), getY() + 1.2, getZ(), 10, .4, .4, .4, .03);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1, 1.4f);
        }
    }

    /** 爪印金镑丢了：拿一枚真的灵猫金镑来，她再压一枚（补办要收钱的）。 */
    @Override
    protected boolean remake(ServerPlayer sp, ItemStack held) {
        if (!held.is(catSovereign()) || PawSovereignItem.carries(sp)) return false;
        if (!sp.getAbilities().instabuild) held.shrink(1);
        ItemStack coin = PawSovereignItem.bound(sp);
        if (!sp.getInventory().add(coin)) sp.drop(coin, false);
        mood.act("act_coin_flip");
        CompanionLines.say(this, sp, "remake", 60);
        return true;
    }

    /** 她的猫叫（原版家猫的那一套）。 */
    static net.minecraft.world.entity.animal.feline.CatSoundVariant.CatSoundSet cat() {
        return SoundEvents.CAT_SOUNDS.get(net.minecraft.world.entity.animal.feline.CatSoundVariants.SoundSet.CLASSIC).adultSounds();
    }

    /** 灵猫金镑（真的那枚）。 */
    static Item catSovereign() {
        return BuiltInRegistries.ITEM.getValue(LogosDimension.id("cat_sovereign"));
    }

    // ---------------------------------------------------------------- 说话

    @Override
    public CompanionSheet talkSheet(ServerPlayer p) {
        return KohakuTalk.sheet(this, p);
    }

    @Override
    public List<CompanionTalk.Line> talkGreeting(ServerPlayer p) {
        return KohakuTalk.greeting(this, p);
    }

    @Override
    public CompanionTalk.Reply talkPick(ServerPlayer p, String option) {
        return KohakuTalk.pick(this, p, option);
    }

    @Override
    protected void talkFinished(ServerPlayer p, boolean bye) {
        KohakuTalk.ended(this, p, bye);
    }

    @Override
    public void talkInterrupted(ServerPlayer p) {
        KohakuTalk.interrupted(this, p);
    }

    @Override
    public CompanionTalk.@Nullable Line talkBack(ServerPlayer p) {
        return KohakuTalk.back(this, p);
    }

    /** 角色面板、友人帐里展示的那一位（客户端临时造的）：穿 {@code outfit}，拄着长杖，表情 {@code face}。 */
    public void showcase(String face, int seals, KohakuOutfit outfit) {
        entityData.set(OUTFIT, outfit.id);
        entityData.set(MISC, face);
        entityData.set(MOVE, "");
        setItemSlot(EquipmentSlot.MAINHAND, KohakuStaff.own(seals));
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) fought(level.getGameTime());
        return hurt;
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putDouble("Mana", mana);
        output.putDouble("StaffStore", staff.store());
        output.putString("Outfit", outfit().id);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        mana = input.getDoubleOr("Mana", MANA);
        staff.setStore(input.getDoubleOr("StaffStore", 0));
        entityData.set(OUTFIT, KohakuOutfit.byId(input.getStringOr("Outfit", KohakuOutfit.CARDIGAN.id)).id);
    }
}
