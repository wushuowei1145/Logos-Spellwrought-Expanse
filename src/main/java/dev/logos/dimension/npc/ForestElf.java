package dev.logos.dimension.npc;

import java.util.Map;
import java.util.UUID;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import com.mojang.serialization.Codec;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.NaturesForceItem;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 森林精灵：精灵森林的居民（docs/NPC_RACE_FOREST_ELF.md）。
 *
 * <ul>
 * <li>外观：生成时按零件表随机抽一次，存解析后的零件 ID（docs/NPC_APPEARANCE.md 第 6 节）。</li>
 * <li>战斗：拿「自然之力」远射（{@link ElfBowAttackGoal}），主动攻击附近的亡灵与灾厄村民。
 *     弓平时收着，只在战斗和战斗后的警戒期里拿在手上（{@link #weapon}）；
 *     僵尸和灾厄村民也把精灵当村民打（{@link ElfFactions}）。</li>
 * <li>治疗：200 点魔力、每秒恢复 5 点，用 Logos 示例法术治疗受伤的同伴（{@link ElfHealGoal}）。
 *     作为玩家的使魔时把玩家视为友方。</li>
 * <li>移动：普通地面寻路之外，绳梯也算路径（{@link ElfNavigation}），能爬上树屋。</li>
 * </ul>
 * 年龄、伴侣、配色替换以后再加。
 */
public class ForestElf extends PathfinderMob implements NpcAnimated, NpcPartsHolder {
    public static final float MAX_MANA = 200, MANA_PER_SECOND = 5;
    /** 主动攻击的搜索半径；一旦决定攻击，追踪距离由 FOLLOW_RANGE 决定（更远）。 */
    public static final double AGGRO_RANGE = 24, FOLLOW_RANGE = 48;
    private static final EntityDataAccessor<String> APPEARANCE = SynchedEntityData.defineId(ForestElf.class, EntityDataSerializers.STRING);
    /** 正在做的剧情动作（动画名，空为没有），见 {@link #playAction}。 */
    private static final EntityDataAccessor<String> ACTION = SynchedEntityData.defineId(ForestElf.class, EntityDataSerializers.STRING);
    /** 剧情动作的动画名与时长（刻），与动画文件一致（tools/forest_elf_animations.py）。 */
    public static final String HEAL_TOUCH = "heal_touch", HEAL_RING = "heal_ring", GREET = "greet", REFUSE = "refuse";
    private static final Map<String, Integer> ACTION_TICKS = Map.of(HEAL_TOUCH, 16, HEAL_RING, 26, GREET, 32, REFUSE, 18);
    private static final Codec<Map<String, String>> APPEARANCE_CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING);
    private static final Identifier ASSET = LogosDimension.id("forest_elf");
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    /** 客户端解码缓存，同步数据变了才重新解码。 */
    private String decodedFrom;
    private Map<String, String> decoded;
    private float mana = MAX_MANA;
    /** 使魔身份每秒查一次（Logos 的使魔记录在 Logos 那边）。 */
    private long nextFamiliarCheck;
    private UUID master;
    /** 服务端：剧情动作还剩几刻。 */
    private int actionTicks;
    /**
     * 死亡：先播最后一击的受击动画（约 0.46 秒），再播死亡动画（1.55 秒）并在倒地姿势停约 1 秒，共 {@link #DEATH_TICKS} 刻后消失。
     * 死亡动画开始的同时换上茫然挂泪的表情。
     */
    public static final int DEATH_FACE_TICKS = 9, DEATH_TICKS = 60;
    public static final String DEATH_EXPRESSION = "expression_dazed_tears";
    /** 战斗或受伤之后保持警戒（弓不收起）的刻数。 */
    public static final int ALERT_TICKS = 100;
    /** 收起来的武器；拿在手上时这里为空。存档 {@code Weapon}。 */
    private ItemStack weapon = ItemStack.EMPTY;
    /** 服务端：警戒还剩几刻。 */
    private int alertTicks;

    public ForestElf(EntityType<? extends ForestElf> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, FOLLOW_RANGE);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(APPEARANCE, NpcParts.encode(NpcParts.FOREST_ELF.defaults()));
        builder.define(ACTION, "");
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new ElfNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new ElfBowAttackGoal(this));
        goalSelector.addGoal(2, new ElfHealGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, ForestElf.class).setAlertOthers());
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false, (e, level) -> isFoe(e)));
    }

    /** 主动攻击的对象：亡灵和灾厄村民（含劫掠队成员），玩家的 Logos 使魔除外。 */
    public static boolean isFoe(LivingEntity e) {
        var type = e.typeHolder();
        boolean hostile = type.is(EntityTypeTags.UNDEAD) || type.is(EntityTypeTags.ILLAGER) || type.is(EntityTypeTags.RAIDERS);
        return hostile && !(e instanceof ForestElf) && LogosMagicBridge.masterOf(e) == null;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof ForestElf) return false;
        if (target instanceof Player && isFamiliar()) return false;
        return super.canAttack(target);
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data) {
        setAppearance(NpcParts.FOREST_ELF.random(level.getRandom()));
        // 弓收着，战斗时才拿出来（见 updateWeapon）
        weapon = new ItemStack(NaturesForceItem.ITEM.get());
        setDropChance(EquipmentSlot.MAINHAND, 0);
        if (reason == EntitySpawnReason.STRUCTURE) {
            setPersistenceRequired();
            // 住在树屋或世界树里：闲逛不走出住处附近
            setHomeTo(blockPosition(), 24);
        }
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    public void setAppearance(Map<String, String> appearance) {
        entityData.set(APPEARANCE, NpcParts.encode(NpcParts.FOREST_ELF.sanitize(appearance)));
    }

    @Override
    public NpcParts.Race npcRace() {
        return NpcParts.FOREST_ELF;
    }

    @Override
    public Map<String, String> npcAppearance() {
        String text = entityData.get(APPEARANCE);
        if (!text.equals(decodedFrom)) {
            decoded = NpcParts.FOREST_ELF.sanitize(NpcParts.decode(text));
            decodedFrom = text;
        }
        return decoded;
    }

    // ---------------------------------------------------------------- 魔力与使魔身份

    public float mana() {
        return mana;
    }

    /** 付 cost 点魔力；不够就不付。 */
    public boolean spendMana(float cost) {
        if (mana < cost) return false;
        mana -= cost;
        return true;
    }

    /** 是不是某个玩家的 Logos 使魔（服务端每秒查一次）。 */
    public boolean isFamiliar() {
        long now = level().getGameTime();
        if (now >= nextFamiliarCheck) {
            nextFamiliarCheck = now + 20;
            master = LogosMagicBridge.masterOf(this);
        }
        return master != null;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;
        if (mana < MAX_MANA) mana = Math.min(MAX_MANA, mana + MANA_PER_SECOND / 20f);
        if (actionTicks > 0 && --actionTicks == 0) entityData.set(ACTION, "");
        updateWeapon();
    }

    // ---------------------------------------------------------------- 武器：平时收起，战斗与警戒时拿出

    /** 收着的或拿在手上的武器（没有为空）。 */
    public ItemStack weapon() {
        return weapon.isEmpty() && getMainHandItem().getItem() instanceof NaturesForceItem ? getMainHandItem() : weapon;
    }

    /** 换一把武器（收着）；传空即没有武器。 */
    public void setWeapon(ItemStack stack) {
        if (getMainHandItem().getItem() instanceof NaturesForceItem) setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        weapon = stack.copy();
    }

    /** 在战斗中，或者战斗、受伤之后的警戒期内。 */
    public boolean isAlert() {
        return getTarget() != null || alertTicks > 0;
    }

    /** 服务端每刻：警戒时把弓拿出来，警戒结束（且没在拉弓）时收起来。 */
    private void updateWeapon() {
        // 致命一击也算受伤，但倒下的精灵不再拿出武器
        if (isDeadOrDying()) return;
        if (getTarget() != null || hurtTime > 0) alertTicks = ALERT_TICKS;
        else if (alertTicks > 0) alertTicks--;
        ItemStack hand = getMainHandItem();
        if (isAlert()) {
            if (hand.isEmpty() && !weapon.isEmpty()) {
                setItemSlot(EquipmentSlot.MAINHAND, weapon);
                weapon = ItemStack.EMPTY;
            }
        } else if (hand.getItem() instanceof NaturesForceItem && !isUsingItem() && weapon.isEmpty()) {
            // 旧存档里一直拿在手上的弓也在这里收起来
            weapon = hand;
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    /** 原版死亡 20 刻就消失；这里等受击与死亡动画播完、在地上停一会儿再化烟。 */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (deathTime >= DEATH_TICKS && !level().isClientSide() && !isRemoved()) {
            level().broadcastEntityEvent(this, EntityEvent.POOF);
            remove(RemovalReason.KILLED);
        }
    }

    @Override
    public String npcExpression() {
        return isDeadOrDying() && deathTime >= DEATH_FACE_TICKS ? DEATH_EXPRESSION : null;
    }

    // ---------------------------------------------------------------- 剧情动作与瞄准（动画）

    /** 服务端：播放一个剧情动作（治疗、行礼、拒绝），播完自动清掉。 */
    public void playAction(String name) {
        actionTicks = ACTION_TICKS.getOrDefault(name, 20);
        entityData.set(ACTION, name);
    }

    @Override
    public String npcMiscAnimation() {
        String name = entityData.get(ACTION);
        return name.isEmpty() ? null : name;
    }

    /** 拉自然之力、伸手放抚生之触时，双臂随视线指向目标。 */
    @Override
    public boolean npcAiming() {
        return isUsingItem() && getUseItem().getItem() instanceof NaturesForceItem || HEAL_TOUCH.equals(entityData.get(ACTION));
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && target != getTarget() && level() instanceof ServerLevel) playSound(ElfSounds.ALERT.get(), 1, getVoicePitch());
        super.setTarget(target);
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store("Appearance", APPEARANCE_CODEC, npcAppearance());
        output.putFloat("Mana", mana);
        if (!weapon.isEmpty()) output.store("Weapon", ItemStack.CODEC, weapon);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read("Appearance", APPEARANCE_CODEC).ifPresent(this::setAppearance);
        mana = Math.clamp(input.getFloatOr("Mana", MAX_MANA), 0, MAX_MANA);
        weapon = input.read("Weapon", ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }

    // ---------------------------------------------------------------- 声音与交互（对标村民）

    @Override
    protected SoundEvent getAmbientSound() {
        return getTarget() != null ? null : ElfSounds.AMBIENT.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ElfSounds.HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ElfSounds.DEATH.get();
    }

    /** 右键打招呼：看向玩家，正在战斗时回一声不满。 */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isAlive()) return super.mobInteract(player, hand);
        getLookControl().setLookAt(player);
        if (!level().isClientSide()) {
            boolean busy = getTarget() != null;
            playSound(busy ? ElfSounds.NO.get() : ElfSounds.GREET.get(), 1, getVoicePitch());
            playAction(busy ? REFUSE : GREET);
        }
        return InteractionResult.SUCCESS;
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
