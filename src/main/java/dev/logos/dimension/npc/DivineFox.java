package dev.logos.dimension.npc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import com.mojang.serialization.Codec;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.fox.SanctuaryResidents;
import dev.logos.dimension.item.UchigatanaItem;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 神狐：灵狐樱野的居民（docs/NPC_RACE_DIVINE_FOX.md）。
 *
 * <ul>
 * <li>外观：生成时按零件表随机抽一次（同 {@link ForestElf}）；五种表情由行为切换（{@link #npcExpression}），
 *     剧情也可以用 {@link #setExpression} 指定。</li>
 * <li>装备：每只都有一把随机刀装的打刀；约四分之一还背着一张原版弓。身上一套铁甲（不显示、死亡不掉落），
 *     对原版魔法伤害（Logos 的奥秘等元素伤害走的就是它）常驻减免 40%，做法同女巫。</li>
 * <li>战斗：主动攻击附近的敌对怪物，对玩家中立；被打时附近的神狐一起反击。决定攻击后迅速突进拉近距离，
 *     用打刀连招（与玩家共用的招式状态机，{@link FoxKatanaGoal}）；够不着、太远或是苦力怕时换弓射击（{@link FoxBowGoal}）。
 *     遇上生命值超过 50 的敌人时在安全的间隙给自己缠上狐火（{@link FoxFireWrapGoal}），结束后战斗未完就再缠一次。</li>
 * <li>格挡：弹射物 80%、近战 50% 的几率用刀格开（不掉血）；近战被格开后接一记弹刀突刺。</li>
 * <li>重伤（生命 ≤ 30%）时逃开，脱离战斗后慢慢回血（{@link FoxFleeGoal}）。</li>
 * <li>刀平时收起不显示，危险或警觉时才连刀带鞘出现在左腰；穿神乐礼装的神狐总是佩着刀。</li>
 * </ul>
 * 死亡时先播受击，再播侧倒后面朝地面的死亡动画。
 */
public class DivineFox extends PathfinderMob implements NpcAnimated, NpcPartsHolder, RangedAttackMob, MoveRunner.Actor {
    private static final EntityDataAccessor<String> APPEARANCE = SynchedEntityData.defineId(DivineFox.class, EntityDataSerializers.STRING);
    /** 当前显示的表情（零件表 {@code expressions} 里的 id，空为常态），见 {@link #npcExpression}。 */
    private static final EntityDataAccessor<String> EXPRESSION = SynchedEntityData.defineId(DivineFox.class, EntityDataSerializers.STRING);
    /** 刀装（{@link #KATANAS} 的下标），刀拿出来（或佩着）时显示这一套。 */
    private static final EntityDataAccessor<Integer> KATANA = SynchedEntityData.defineId(DivineFox.class, EntityDataSerializers.INT);
    /** 刀是否露出来：战斗与警戒期间，或穿神乐礼装。 */
    private static final EntityDataAccessor<Boolean> SHOWN = SynchedEntityData.defineId(DivineFox.class, EntityDataSerializers.BOOLEAN);
    /** 正在播放的打刀动作：{@code 动画名|开始刻|速度|是否循环}，空为没有。 */
    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(DivineFox.class, EntityDataSerializers.STRING);
    /** 剧情动作（打招呼、拒绝）的动画名，空为没有。 */
    private static final EntityDataAccessor<String> ACTION = SynchedEntityData.defineId(DivineFox.class, EntityDataSerializers.STRING);
    private static final Codec<Map<String, String>> APPEARANCE_CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING);
    private static final Identifier ASSET = LogosDimension.id("divine_fox");
    private static final Identifier MOVESET = LogosDimension.id("uchigatana");

    /** 四套刀装，顺序与模型里的 {@code katana_<配色>_blade / _saya} 组一致（tools/divine_fox_assets.py 的 KATANAS）。 */
    public static final List<String> KATANAS = List.of("ink", "vermilion", "indigo", "wood");
    /** 打刀动作在神狐动画文件里的前缀。 */
    public static final String KATANA_PREFIX = "katana_";
    /** 总是佩刀的外观。 */
    public static final String KAGURA = "outfit_kagura";
    /** 受击（约 0.46 秒）与死亡动画（1.55 秒）播完，在地上停约 1 秒后消失。 */
    public static final int DEATH_TICKS = 60;
    public static final float MAX_MANA = 200, MANA_PER_SECOND = 2;
    /** 主动攻击的搜索半径；一旦决定攻击，追踪距离由 FOLLOW_RANGE 决定。 */
    public static final double AGGRO_RANGE = 20, FOLLOW_RANGE = 40;
    /** 背弓的几率。 */
    public static final float BOW_CHANCE = .25f;
    /** 格挡几率：弹射物、近战。 */
    public static final float BLOCK_PROJECTILE = .8f, BLOCK_MELEE = .5f;
    /** 对原版魔法伤害的减免。 */
    public static final float MAGIC_RESISTANCE = .4f;
    /** 战斗或受伤之后保持警戒（刀不收起）的刻数。 */
    public static final int ALERT_TICKS = 100;
    /** 剧情动作的时长（刻），与动画文件一致（借用的森林精灵动作）。 */
    private static final Map<String, Integer> ACTION_TICKS = Map.of("greet", 32, "refuse", 18);
    /** 行为表情的 id。 */
    public static final String ANGRY = "expression_angry", SURPRISED = "expression_surprised", HAPPY = "expression_happy",
            TEASING = "expression_teasing", KITSUNE = "expression_kitsune";

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    /** 客户端解码缓存，同步数据变了才重新解码。 */
    private String decodedFrom, moveFrom;
    private Map<String, String> decoded;
    private CombatMove move;
    /** 死亡时的朝向（活着时为 NaN），见 {@link NpcDeathPose}。 */
    private float deathYaw = Float.NaN;

    // ---- 服务端状态
    /** 收起来的打刀与弓；拿在手上时这里为空。 */
    private ItemStack katana = ItemStack.EMPTY, bow = ItemStack.EMPTY;
    /** 打刀的招式状态机；刀收起时为空。 */
    private MoveRunner runner;
    private float mana = MAX_MANA;
    private int alertTicks, actionTicks, guardTicks, sinceHurt = 1000, regenTicks;
    /** 剧情指定的表情（优先），以及行为给出的短暂表情。 */
    private String manualExpression, moodExpression;
    private int moodTicks;
    /** 这一仗里放过狐火缠绕：缠绕结束后战斗还没完就再放一次。 */
    private boolean fireFight;
    private boolean fleeing;
    /** 用弓：够不着、太远或是苦力怕（FoxBowGoal 设置）。 */
    private boolean bowMode;
    /** 生成在哪处聚落（结构起点），用于每处最多 20 只的统计；不在聚落里为 null。 */
    private Long sanctuary;

    public DivineFox(EntityType<? extends DivineFox> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, FOLLOW_RANGE).add(Attributes.ATTACK_DAMAGE, 1).add(Attributes.ATTACK_SPEED, 4);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(APPEARANCE, NpcParts.encode(NpcParts.DIVINE_FOX.defaults()));
        builder.define(EXPRESSION, "");
        builder.define(KATANA, 0);
        builder.define(SHOWN, false);
        builder.define(MOVE, "");
        builder.define(ACTION, "");
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new FoxFleeGoal(this));
        goalSelector.addGoal(2, new FoxFireWrapGoal(this));
        goalSelector.addGoal(3, new FoxBowGoal(this));
        goalSelector.addGoal(4, new FoxKatanaGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, DivineFox.class).setAlertOthers());
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
                (e, level) -> isFoe(e) && (e.distanceTo(this) <= AGGRO_RANGE) && (hasBow() || !(e instanceof Creeper))));
    }

    /**
     * 主动攻击的对象：敌对怪物，但不惹中立生物（末影人、僵尸猪灵等）、不挑战 Boss 与监守者，
     * 不打森林精灵、同伴和玩家的 Logos 使魔。苦力怕只有背弓的神狐会去射（见 registerGoals）。
     */
    public static boolean isFoe(LivingEntity e) {
        return e instanceof Enemy && !(e instanceof NeutralMob) && !(e instanceof DivineFox) && !(e instanceof ForestElf)
                && !(e instanceof Warden) && !(e instanceof WitherBoss) && !(e instanceof EnderDragon) && LogosMagicBridge.masterOf(e) == null;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof DivineFox) && super.canAttack(target);
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data) {
        var random = level.getRandom();
        setAppearance(NpcParts.DIVINE_FOX.random(random));
        setKatana(random.nextInt(KATANAS.size()));
        if (random.nextFloat() < BOW_CHANCE) bow = new ItemStack(Items.BOW);
        // 一套铁甲：模型不画盔甲，看不见；死亡不掉落
        setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0);
        if (reason == EntitySpawnReason.STRUCTURE) {
            setPersistenceRequired();
            setHomeTo(blockPosition(), 32);
        }
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    public void setAppearance(Map<String, String> appearance) {
        entityData.set(APPEARANCE, NpcParts.encode(NpcParts.DIVINE_FOX.sanitize(appearance)));
        updateShown();
    }

    @Override
    public NpcParts.Race npcRace() {
        return NpcParts.DIVINE_FOX;
    }

    @Override
    public Map<String, String> npcAppearance() {
        String text = entityData.get(APPEARANCE);
        if (!text.equals(decodedFrom)) {
            decoded = NpcParts.DIVINE_FOX.sanitize(NpcParts.decode(text));
            decodedFrom = text;
        }
        return decoded;
    }

    /** 穿神乐礼装：刀总是佩在腰间。 */
    public boolean alwaysCarries() {
        return KAGURA.equals(npcAppearance().get("outfit"));
    }

    // ---------------------------------------------------------------- 刀与弓

    /** 刀装下标（0–3）。 */
    public int katanaVariant() {
        return Math.floorMod(entityData.get(KATANA), KATANAS.size());
    }

    /** 换一套刀装（同时换掉手上或收着的刀）。 */
    public void setKatana(int variant) {
        int v = Math.floorMod(variant, KATANAS.size());
        entityData.set(KATANA, v);
        ItemStack stack = new ItemStack(katanaItem(v));
        if (getMainHandItem().getItem() instanceof UchigatanaItem) setItemSlot(EquipmentSlot.MAINHAND, stack);
        else katana = stack;
    }

    private static UchigatanaItem katanaItem(int variant) {
        return switch (variant) {
            case 1 -> UchigatanaItem.VERMILION.get();
            case 2 -> UchigatanaItem.INDIGO.get();
            case 3 -> UchigatanaItem.WOOD.get();
            default -> UchigatanaItem.INK.get();
        };
    }

    public boolean hasBow() {
        return !bow.isEmpty() || getMainHandItem().getItem() instanceof BowItem;
    }

    /** 给一张弓（收着，用弓的时候才拿出来）；传空收回弓。 */
    public void giveBow(ItemStack stack) {
        if (getMainHandItem().getItem() instanceof BowItem) {
            unequipBow();
            if (!getMainHandItem().isEmpty()) super.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        bow = stack.copy();
    }

    /**
     * 外部换装（命令、发射器）：手里原来的刀或弓收起来，不会弄丢；换上新刀或新弓时，替掉收着的那一把。
     * 内部拿出、收起走 super.setItemSlot，不经过这里。
     */
    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.MAINHAND && !level().isClientSide()) {
            ItemStack old = getMainHandItem();
            if (old != stack) {
                if (old.getItem() instanceof UchigatanaItem && katana.isEmpty() && !(stack.getItem() instanceof UchigatanaItem)) katana = old;
                if (old.getItem() instanceof BowItem && bow.isEmpty() && !(stack.getItem() instanceof BowItem)) bow = old;
                if (old.getItem() instanceof UchigatanaItem) dropRunner();
            }
            if (stack.getItem() instanceof UchigatanaItem && stack != katana) katana = ItemStack.EMPTY;
            if (stack.getItem() instanceof BowItem && stack != bow) bow = ItemStack.EMPTY;
        }
        super.setItemSlot(slot, stack);
    }

    /** 把手上的东西收回去（刀或弓）。 */
    private void stowHand() {
        ItemStack hand = getMainHandItem();
        if (hand.isEmpty()) return;
        if (isUsingItem()) stopUsingItem();
        if (hand.getItem() instanceof UchigatanaItem && katana.isEmpty()) katana = hand;
        else if (hand.getItem() instanceof BowItem && bow.isEmpty()) bow = hand;
        else return;
        super.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    /** 拔刀：刀拿到手上，招式从拔刀开始（已经拔出就不动）。 */
    public boolean drawKatana() {
        if (runner != null && !runner.cleared() && getMainHandItem().getItem() instanceof UchigatanaItem) return true;
        if (!(level() instanceof ServerLevel level)) return false;
        Moveset set = Movesets.get(level.getServer(), MOVESET);
        if (set == null) return false;
        if (!(getMainHandItem().getItem() instanceof UchigatanaItem)) {
            if (katana.isEmpty()) return false;
            stowHand();
            super.setItemSlot(EquipmentSlot.MAINHAND, katana);
            katana = ItemStack.EMPTY;
        }
        runner = new MoveRunner(set, this, level.getGameTime());
        // 状态机在构造时就报告了第一招（拔刀），那时 runner 还没赋值，这里补报一次
        moveStarted(runner);
        return true;
    }

    /** 收刀：播收刀动作，播完刀回到腰间。 */
    public void sheatheKatana() {
        if (runner == null || runner.cleared()) return;
        String sheathe = runner.moveset().sheathe();
        if (sheathe != null && !sheathe.equals(runner.move().name())) runner.force(sheathe, level().getGameTime());
    }

    /** 立刻把刀放回腰间，不播收刀动作（换弓时）。 */
    private void dropRunner() {
        runner = null;
        entityData.set(MOVE, "");
    }

    /** 拿弓：刀直接回到腰间。 */
    public boolean equipBow() {
        if (getMainHandItem().getItem() instanceof BowItem) return true;
        if (bow.isEmpty()) return false;
        dropRunner();
        stowHand();
        super.setItemSlot(EquipmentSlot.MAINHAND, bow);
        bow = ItemStack.EMPTY;
        bowMode = true;
        return true;
    }

    /** 收弓，回到用刀。 */
    public void unequipBow() {
        bowMode = false;
        if (getMainHandItem().getItem() instanceof BowItem) stowHand();
    }

    public boolean bowMode() {
        return bowMode;
    }

    /** 向招式状态机发一个输入（攻击、格挡）；刀没拔出时不处理。 */
    public boolean katanaInput(Moveset.Input input) {
        return runner != null && !runner.cleared() && runner.input(input, level().getGameTime());
    }

    /** 当前招式（没拔刀时为 null）。 */
    public Moveset.Move katanaMove() {
        return runner == null || runner.cleared() ? null : runner.move();
    }

    @Override
    public double attackSpeed() {
        // 打刀的攻速（UchigatanaItem 为 1.8）：动作按原速播放
        return 1.8;
    }

    @Override
    public void moveStarted(MoveRunner r) {
        if (r != runner) return;
        if (r.cleared()) {
            // 收刀播完：刀回到腰间（或收起不显示）
            runner = null;
            entityData.set(MOVE, "");
            stowHand();
            return;
        }
        Moveset.Move m = r.move();
        entityData.set(MOVE, KATANA_PREFIX + m.clip() + "|" + (long) r.start() + "|" + (float) r.rate() + "|"
                + (m.playback() == Moveset.Playback.LOOP));
    }

    /** 刀的接触时刻：砍到身前扇形里的敌人（同伴与玩家的使魔除外）。 */
    @Override
    public void contact(MoveRunner r) {
        if (!(level() instanceof ServerLevel level)) return;
        LivingEntity target = getTarget();
        double reach = 3.0;
        Vec3 eye = position().add(0, getBbHeight() * .5, 0);
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        boolean hit = false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(reach), e -> e != this && e.isAlive())) {
            if (e instanceof DivineFox || e != target && !(e instanceof Mob m && m.getTarget() instanceof DivineFox) && !isFoe(e)) continue;
            Vec3 to = e.position().add(0, e.getBbHeight() * .5, 0).subtract(eye);
            double flat = Math.sqrt(to.x * to.x + to.z * to.z);
            if (to.length() > reach + e.getBbWidth() * .5) continue;
            // 75° 扇形；贴身（一格内）不论方向
            if (flat > 1 && (to.x * forward.x + to.z * forward.z) / flat < Math.cos(Math.toRadians(37.5))) continue;
            hit |= doHurtTarget(level, e);
        }
        if (hit && target != null && !target.isAlive()) mood(TEASING, 60);
    }

    // ---------------------------------------------------------------- 弓

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        ItemStack weapon = getItemInHand(ProjectileUtil.getWeaponHoldingHand(this, item -> item instanceof BowItem));
        ItemStack projectile = getProjectile(weapon);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, projectile, power, weapon);
        if (weapon.getItem() instanceof BowItem bowItem) arrow = bowItem.customArrow(arrow, projectile, weapon);
        double dx = target.getX() - getX(), dy = target.getY(1 / 3.0) - arrow.getY(), dz = target.getZ() - getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (level() instanceof ServerLevel level) {
            // 神狐射得比骷髅准：散布只有骷髅（困难）的一半
            Projectile.spawnProjectileUsingShoot(arrow, level, projectile, dx, dy + flat * .2, dz, 1.6f, 1);
        }
        playSound(SoundEvents.SKELETON_SHOOT, 1, 1 / (getRandom().nextFloat() * .4f + .8f));
        if (getRandom().nextFloat() < .3f) playSound(FoxSounds.ATTACK.get(), 1, getVoicePitch());
    }

    @Override
    public ItemStack getProjectile(ItemStack weapon) {
        // 无限箭：随身的箭不消耗也不掉落
        return new ItemStack(Items.ARROW);
    }

    // ---------------------------------------------------------------- 魔力（狐火缠绕）

    public float mana() {
        return mana;
    }

    public boolean spendMana(float cost) {
        if (mana < cost) return false;
        mana -= cost;
        return true;
    }

    public boolean fireFight() {
        return fireFight;
    }

    /** 放出狐火缠绕：这一仗里记下，缠绕结束后战斗没完就再放。 */
    public void firedUp() {
        fireFight = true;
        mood(KITSUNE, 40);
        playSound(FoxSounds.CAST.get(), 1, getVoicePitch());
    }

    /** 自己身上是否缠着魔力缠绕（服务端与客户端都能用，客户端据此画狐火）。 */
    public boolean wrapped() {
        return LogosMagicBridge.wrapped(this);
    }

    // ---------------------------------------------------------------- 逃跑与回血

    public boolean fleeing() {
        return fleeing;
    }

    void setFleeing(boolean fleeing) {
        this.fleeing = fleeing;
    }

    /** 重伤：生命不超过三成。 */
    public boolean badlyHurt() {
        return getHealth() <= getMaxHealth() * .3f;
    }

    /** 上次受伤之后过了多少刻。 */
    public int sinceHurt() {
        return sinceHurt;
    }

    // ---------------------------------------------------------------- 每刻

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;
        sinceHurt++;
        if (mana < MAX_MANA) mana = Math.min(MAX_MANA, mana + MANA_PER_SECOND / 20f);
        if (actionTicks > 0 && --actionTicks == 0) entityData.set(ACTION, "");
        if (moodTicks > 0 && --moodTicks == 0) moodExpression = null;
        if (isDeadOrDying()) return;
        if (getTarget() != null || hurtTime > 0) alertTicks = ALERT_TICKS;
        else if (alertTicks > 0) alertTicks--;
        if (!isAlert()) fireFight = false;
        // 平静下来：收刀、收弓
        if (!isAlert()) {
            if (bowMode) unequipBow();
            sheatheKatana();
        }
        if (runner != null) {
            runner.tick(level().getGameTime());
            if (guardTicks > 0 && --guardTicks == 0) katanaInput(Moveset.Input.USE_RELEASE);
        }
        // 脱离战斗五秒后每两秒回一点生命
        if (getTarget() == null && sinceHurt > 100 && getHealth() < getMaxHealth() && ++regenTicks >= 40) {
            regenTicks = 0;
            heal(1);
        }
        updateShown();
        String expression = manualExpression != null ? manualExpression : moodExpression != null ? moodExpression
                : fleeing ? SURPRISED : getTarget() != null ? ANGRY : null;
        entityData.set(EXPRESSION, expression == null ? "" : expression);
    }

    /** 在战斗中，或者战斗、受伤之后的警戒期内。 */
    public boolean isAlert() {
        return getTarget() != null || alertTicks > 0;
    }

    private void updateShown() {
        if (level().isClientSide()) return;
        entityData.set(SHOWN, !isDeadOrDying() && (isAlert() || runner != null) || alwaysCarries());
    }

    /** 一个短暂的表情（行为给出，剧情表情优先）。 */
    public void mood(String expression, int ticks) {
        moodExpression = expression;
        moodTicks = ticks;
    }

    // ---------------------------------------------------------------- 受伤：格挡与魔法减免

    /**
     * 用刀格挡：弹射物 80%、近战 50%，挡下就不受伤；近战被格开时接一记弹刀突刺。
     * 绕过无敌的伤害（/kill 等）、魔法、火焰、爆炸不能格挡。
     */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (amount > 0 && !isInvulnerableTo(level, source) && !isDeadOrDying()) {
            alertTicks = ALERT_TICKS;
            Entity direct = source.getDirectEntity();
            boolean projectile = direct instanceof Projectile;
            boolean melee = direct instanceof LivingEntity && direct == source.getEntity()
                    && (source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO));
            if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && (projectile || melee) && hasKatanaStack()
                    && getRandom().nextFloat() < (projectile ? BLOCK_PROJECTILE : BLOCK_MELEE)) {
                block(level, source, melee);
                return false;
            }
            sinceHurt = 0;
            if (badlyHurt()) mood(SURPRISED, 20);
        }
        return super.hurtServer(level, source, amount);
    }

    private boolean hasKatanaStack() {
        return !katana.isEmpty() || getMainHandItem().getItem() instanceof UchigatanaItem;
    }

    private void block(ServerLevel level, DamageSource source, boolean melee) {
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.SHIELD_BLOCK, getSoundSource(), 1, .9f + getRandom().nextFloat() * .2f);
        if (source.getEntity() instanceof LivingEntity attacker && attacker != getTarget() && canAttack(attacker) && !(attacker instanceof Player p && p.isCreative())) {
            if (getTarget() == null) setTarget(attacker);
        }
        if (source.getEntity() instanceof LivingEntity attacker) getLookControl().setLookAt(attacker);
        if (bowMode) return;
        if (!drawKatana()) return;
        long now = level.getGameTime();
        if (melee) {
            // 弹刀，然后突刺反击
            runner.force("parry", now);
            katanaInput(Moveset.Input.ATTACK);
        } else if (katanaMove() == null || !"guard".equals(katanaMove().name())) {
            runner.force("guard", now);
            guardTicks = 8;
        }
    }

    /** 同女巫：对原版魔法与间接魔法伤害（Logos 的奥秘、寒霜、雷霆、虚空伤害都走这里）减免 40%。 */
    @Override
    protected float getDamageAfterMagicAbsorb(DamageSource source, float damage) {
        damage = super.getDamageAfterMagicAbsorb(source, damage);
        if (source.is(DamageTypeTags.WITCH_RESISTANT_TO) && source.getEntity() != this) {
            damageContainers.peek().setReduction(net.neoforged.neoforge.common.damagesource.DamageContainer.Reduction.INNATE_RESISTANCE, damage * MAGIC_RESISTANCE);
            damage *= 1 - MAGIC_RESISTANCE;
        }
        return damage;
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && target != getTarget() && level() instanceof ServerLevel) playSound(FoxSounds.ALERT.get(), 1, getVoicePitch());
        super.setTarget(target);
    }

    // ---------------------------------------------------------------- 表情

    /**
     * 剧情指定一个表情（调侃、狐狸眯眼、开心、惊讶、愤怒，零件表 {@code expressions} 的 id），优先于行为给出的表情；
     * 传 null 或空串交还给行为。不认识的 id 不生效。表情是临时状态，不存档。
     */
    public void setExpression(String id) {
        boolean known = id != null && NpcParts.DIVINE_FOX.expressions().stream().anyMatch(e -> e.id().equals(id));
        manualExpression = known ? id : null;
        entityData.set(EXPRESSION, known ? id : "");
    }

    /** 当前表情：剧情指定的优先，其次是行为（战斗愤怒、逃跑惊讶、缠上狐火狐狸眯眼、得手调侃、被搭话开心）。 */
    @Override
    public String npcExpression() {
        String id = entityData.get(EXPRESSION);
        return id.isEmpty() ? null : id;
    }

    // ---------------------------------------------------------------- 外观里的刀

    /** 只显示这只神狐的那套刀装；刀不露出时（平时，神乐礼装除外）整把连鞘都不画。 */
    @Override
    public Set<String> npcHiddenExtras() {
        Set<String> hidden = new java.util.HashSet<>();
        int v = katanaVariant();
        for (int i = 0; i < KATANAS.size(); i++) {
            if (i == v) continue;
            hidden.add("katana_" + KATANAS.get(i) + "_blade");
            hidden.add("katana_" + KATANAS.get(i) + "_saya");
        }
        if (!entityData.get(SHOWN)) {
            hidden.add("Weapon");
            hidden.add("Sheath");
        }
        return hidden;
    }

    /** 刀此刻是否露出来（客户端渲染用）。 */
    public boolean katanaShown() {
        return entityData.get(SHOWN);
    }

    @Override
    public CombatMove npcCombatMove() {
        String text = entityData.get(MOVE);
        if (!text.equals(moveFrom)) {
            moveFrom = text;
            move = null;
            String[] p = text.split("\\|");
            if (p.length == 4) {
                try {
                    move = new CombatMove(p[0], Long.parseLong(p[1]), Float.parseFloat(p[2]), Boolean.parseBoolean(p[3]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return move;
    }

    /** 拉弓时双臂随视线指向目标。 */
    @Override
    public boolean npcAiming() {
        return isUsingItem() && getUseItem().getItem() instanceof BowItem;
    }

    @Override
    public String npcMiscAnimation() {
        String name = entityData.get(ACTION);
        return name.isEmpty() ? null : name;
    }

    private void playAction(String name) {
        actionTicks = ACTION_TICKS.getOrDefault(name, 20);
        entityData.set(ACTION, name);
    }

    // ---------------------------------------------------------------- 聚落

    /** 记下生成在哪处聚落（结构起点的 BlockPos.asLong）。 */
    public void setSanctuary(Long start) {
        sanctuary = start;
    }

    public Long sanctuary() {
        return sanctuary;
    }

    /** 死亡或被清除：从所在聚落的居民数里减去。 */
    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && sanctuary != null && level() instanceof ServerLevel level && !isRemoved()) {
            SanctuaryResidents.lost(level, sanctuary);
            sanctuary = null;
        }
        super.remove(reason);
    }

    // ---------------------------------------------------------------- 死亡

    /** 原版死亡 20 刻就消失；这里等受击与死亡动画播完、在地上停一会儿再消失。 */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (deathTime >= DEATH_TICKS && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, EntityEvent.POOF);
            remove(RemovalReason.KILLED);
        }
    }

    /** 倒下后朝向不再转动（NpcDeathPose）；倒下时刀停止招式。 */
    @Override
    public void tick() {
        super.tick();
        deathYaw = isDeadOrDying() ? NpcDeathPose.hold(this, deathYaw) : Float.NaN;
        if (!level().isClientSide() && isDeadOrDying() && runner != null) dropRunner();
    }

    // ---------------------------------------------------------------- 声音与交互

    @Override
    protected SoundEvent getAmbientSound() {
        return getTarget() != null ? null : FoxSounds.AMBIENT.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return FoxSounds.HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return FoxSounds.DEATH.get();
    }

    /** 右键打招呼：看向玩家，开心地应一声；正在战斗时不满地拒绝。 */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isAlive()) return super.mobInteract(player, hand);
        getLookControl().setLookAt(player);
        if (!level().isClientSide()) {
            boolean busy = getTarget() != null;
            playSound(busy ? FoxSounds.NO.get() : FoxSounds.GREET.get(), 1, getVoicePitch());
            if (runner == null) playAction(busy ? "refuse" : "greet");
            if (!busy) mood(HAPPY, 40);
        }
        return InteractionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store("Appearance", APPEARANCE_CODEC, npcAppearance());
        output.putInt("Katana", katanaVariant());
        output.putFloat("Mana", mana);
        if (!katana.isEmpty()) output.store("KatanaItem", ItemStack.CODEC, katana);
        if (!bow.isEmpty()) output.store("Bow", ItemStack.CODEC, bow);
        if (sanctuary != null) output.putLong("Sanctuary", sanctuary);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read("Appearance", APPEARANCE_CODEC).ifPresent(this::setAppearance);
        entityData.set(KATANA, Math.floorMod(input.getIntOr("Katana", 0), KATANAS.size()));
        mana = Math.clamp(input.getFloatOr("Mana", MAX_MANA), 0, MAX_MANA);
        katana = input.read("KatanaItem", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        bow = input.read("Bow", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        // 存档时手上的刀或弓（战斗中退出）也算；旧存档没有刀时补一把
        if (getMainHandItem().getItem() instanceof UchigatanaItem) katana = ItemStack.EMPTY;
        if (getMainHandItem().getItem() instanceof BowItem) bow = ItemStack.EMPTY;
        if (katana.isEmpty() && !(getMainHandItem().getItem() instanceof UchigatanaItem)) katana = new ItemStack(katanaItem(katanaVariant()));
        sanctuary = input.getLong("Sanctuary").orElse(null);
    }

    // ---------------------------------------------------------------- 动画

    @Override
    public Identifier npcAnimationAsset() {
        return ASSET;
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
