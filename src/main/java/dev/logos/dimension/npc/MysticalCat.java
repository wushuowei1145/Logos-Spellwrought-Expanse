package dev.logos.dimension.npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.feline.CatSoundVariant;
import net.minecraft.world.entity.animal.feline.CatSoundVariants;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 灵猫居民（docs/MYSTICAL_CAT.md）：猫耳猫尾的人形灵猫，住在远行大陆的林隙村镇里。
 *
 * <p>外观来自零件表 {@code mystical_cat}（发型、头饰、猫耳、猫尾、衣服），毛色是另一个维度：
 * 同一套模型换一张贴图（{@link #COATS}），头发、耳朵、尾巴和瞳色一起变。双尾的猫又少见（{@link #TWIN_TAIL_CHANCE}）。
 *
 * <p>平时闲逛、在原地坐下打盹、伸懒腰、舔爪洗脸；夜里坐着睡觉。手里拿着鱼的玩家走过，她会跟上去；
 * 空手右键摸头，喂鱼更开心，每次都记在这位玩家的亲近度上（{@link #fondness}）。亲近到 {@link #FOND} 以后，
 * 她会主动走过来蹭蹭，并且只要在她身边，灵猫的魔力场域就让玩家更容易感知、控制魔力：
 * 获得「灵猫相伴」（{@link MysticalCompany}），法力回复加快、蓄力加快。
 *
 * <p>不会打架：被打就跑，看到怪物就躲；苦力怕也怕她们（{@link ElfFactions}）。
 */
public class MysticalCat extends PathfinderMob implements NpcAnimated, NpcPartsHolder {
    private static final EntityDataAccessor<String> APPEARANCE = SynchedEntityData.defineId(MysticalCat.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> COAT = SynchedEntityData.defineId(MysticalCat.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> EXPRESSION = SynchedEntityData.defineId(MysticalCat.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> ACTION = SynchedEntityData.defineId(MysticalCat.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> SITTING = SynchedEntityData.defineId(MysticalCat.class, EntityDataSerializers.BOOLEAN);
    private static final Codec<Map<String, String>> APPEARANCE_CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING);
    private static final Identifier ASSET = LogosDimension.id("mystical_cat");

    /** 毛色：贴图 {@code textures/entity/mystical_cat/<毛色>.png}，顺序同模型目录 tools/coats.py。 */
    public static final List<String> COATS = List.of("ginger", "snow", "ink", "calico", "tuxedo", "tabby", "siamese", "blue");
    public static final String TWIN_TAIL = "tail_cat_twin";
    /** 随机外观里双尾猫又的几率（其余尾巴平分剩下的）。 */
    public static final float TWIN_TAIL_CHANCE = .08f;
    public static final String HAPPY = "expression_happy";
    /** 亲近度到这么多：主动蹭过来，身边的玩家得到灵猫相伴。 */
    public static final int FOND = 3;
    public static final int MAX_FONDNESS = 20;
    /** 同一位玩家摸头之间至少隔多少刻才再记一次亲近度（摸头本身随时都行）。 */
    public static final int PET_COOLDOWN = 1200;
    /** 灵猫相伴的范围（格）与每次给多久（刻）。 */
    public static final double COMPANY_RANGE = 8;
    public static final int COMPANY_TICKS = 220;
    private static final Map<String, Integer> ACTION_TICKS = Map.of("greet", 32, "refuse", 18, "stretch", 48, "wash", 40, "purr", 32);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Map<UUID, Integer> fondness = new HashMap<>();
    private final Map<UUID, Long> lastPet = new HashMap<>();
    private String decodedFrom;
    private Map<String, String> decoded;
    private int actionTicks, moodTicks, sitTicks;
    private String moodExpression;

    public MysticalCat(EntityType<? extends MysticalCat> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 16).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 16).add(Attributes.TEMPT_RANGE, 10);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(APPEARANCE, NpcParts.encode(NpcParts.MYSTICAL_CAT.defaults()));
        builder.define(COAT, 0);
        builder.define(EXPRESSION, "");
        builder.define(ACTION, "");
        builder.define(SITTING, false);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.35));
        goalSelector.addGoal(2, new AvoidEntityGoal<>(this, Monster.class, 8, 1.0, 1.3));
        goalSelector.addGoal(3, new TemptGoal(this, 0.9, stack -> stack.is(ItemTags.CAT_FOOD), false));
        goalSelector.addGoal(4, new MysticalCatGoals.Sit(this));
        goalSelector.addGoal(5, new MysticalCatGoals.Visit(this));
        goalSelector.addGoal(6, new MysticalCatGoals.Idle(this));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.55));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data) {
        RandomSource random = level.getRandom();
        setAppearance(randomAppearance(random));
        setCoat(random.nextInt(COATS.size()));
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    /** 零件表随机一套，再把尾巴重抽一次：猫又只占 {@link #TWIN_TAIL_CHANCE}。 */
    public static Map<String, String> randomAppearance(RandomSource random) {
        Map<String, String> look = new HashMap<>(NpcParts.MYSTICAL_CAT.random(random));
        List<String> tails = new ArrayList<>();
        for (var slot : NpcParts.MYSTICAL_CAT.slots()) {
            if (slot.name().equals("tails")) slot.parts().forEach(p -> tails.add(p.id()));
        }
        tails.remove(TWIN_TAIL);
        if (!tails.isEmpty()) look.put("tails", random.nextFloat() < TWIN_TAIL_CHANCE ? TWIN_TAIL : tails.get(random.nextInt(tails.size())));
        return look;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // ---------------------------------------------------------------- 外观

    public void setAppearance(Map<String, String> appearance) {
        entityData.set(APPEARANCE, NpcParts.encode(NpcParts.MYSTICAL_CAT.sanitize(appearance)));
    }

    @Override
    public NpcParts.Race npcRace() {
        return NpcParts.MYSTICAL_CAT;
    }

    @Override
    public Map<String, String> npcAppearance() {
        String text = entityData.get(APPEARANCE);
        if (!text.equals(decodedFrom)) {
            decoded = NpcParts.MYSTICAL_CAT.sanitize(NpcParts.decode(text));
            decodedFrom = text;
        }
        return decoded;
    }

    public int coat() {
        return Math.floorMod(entityData.get(COAT), COATS.size());
    }

    public void setCoat(int coat) {
        entityData.set(COAT, Math.floorMod(coat, COATS.size()));
    }

    // ---------------------------------------------------------------- 状态与动作

    public boolean sitting() {
        return entityData.get(SITTING);
    }

    /** 坐下（ticks 刻后自己站起来；被打、被逗、有人拿着鱼走过时提前站起）。 */
    public void sitDown(int ticks) {
        sitTicks = ticks;
        entityData.set(SITTING, true);
        getNavigation().stop();
    }

    public void standUp() {
        sitTicks = 0;
        entityData.set(SITTING, false);
    }

    @Override
    public boolean isNpcSitting() {
        return sitting();
    }

    public void playAction(String name) {
        actionTicks = ACTION_TICKS.getOrDefault(name, 30);
        entityData.set(ACTION, name);
    }

    public boolean acting() {
        return actionTicks > 0;
    }

    public void mood(String expression, int ticks) {
        moodExpression = expression;
        moodTicks = ticks;
    }

    @Override
    public String npcMiscAnimation() {
        String name = entityData.get(ACTION);
        return name.isEmpty() ? null : name;
    }

    @Override
    public String npcExpression() {
        String id = entityData.get(EXPRESSION);
        return id.isEmpty() ? null : id;
    }

    /** 夜里该睡觉了（坐着睡，闭眼）。 */
    public boolean sleepy() {
        return level().isDarkOutside();
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide()) return;
        if (actionTicks > 0 && --actionTicks == 0) entityData.set(ACTION, "");
        if (moodTicks > 0 && --moodTicks == 0) moodExpression = null;
        if (sitTicks > 0 && --sitTicks == 0) standUp();
        if (sitting() && (hurtTime > 0 || isInWater() || isPassenger())) standUp();
        // 坐着打盹时眯着眼；被摸头、吃到鱼时开心
        String expression = moodExpression != null ? moodExpression : sitting() && sleepy() ? HAPPY : null;
        entityData.set(EXPRESSION, expression == null ? "" : expression);
        if (tickCount % 40 == 0 && level() instanceof ServerLevel server) keepCompany(server);
    }

    @Override
    public void travel(net.minecraft.world.phys.Vec3 input) {
        // 坐着不挪窝（推动、掉落照常）
        super.travel(sitting() ? net.minecraft.world.phys.Vec3.ZERO : input);
    }

    // ---------------------------------------------------------------- 亲近度与灵猫相伴

    public int fondness(Player player) {
        return fondness.getOrDefault(player.getUUID(), 0);
    }

    public boolean fondOf(Player player) {
        return fondness(player) >= FOND;
    }

    private void befriend(Player player, int amount) {
        fondness.merge(player.getUUID(), amount, (a, b) -> Math.min(MAX_FONDNESS, a + b));
    }

    /** 身边亲近的玩家：给（续上）灵猫相伴，再从猫身上飘一枚符文到玩家身上。 */
    private void keepCompany(ServerLevel level) {
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, getBoundingBox().inflate(COMPANY_RANGE))) {
            if (!fondOf(player) || player.isSpectator()) continue;
            player.addEffect(new MobEffectInstance(MysticalCompany.EFFECT, COMPANY_TICKS, 0, true, true, true), this);
            if (random.nextInt(3) == 0) {
                // 附魔台的符文粒子：从起点（猫的胸口）飞向给定的位置（玩家胸口）
                double x = player.getX(), y = player.getY() + 1.1, z = player.getZ();
                level.sendParticles(ParticleTypes.ENCHANT, x, y, z, 0, getX() - x, getY() + .9 - y, getZ() - z, 1);
            }
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isAlive()) return super.mobInteract(player, hand);
        ItemStack stack = player.getItemInHand(hand);
        getLookControl().setLookAt(player);
        if (stack.is(ItemTags.CAT_FOOD)) {
            if (!level().isClientSide()) {
                stack.consume(1, player);
                befriend(player, 2);
                playSound(sounds().eatSound().value(), 1, getVoicePitch());
                react(player);
            }
            return InteractionResult.SUCCESS;
        }
        if (!stack.isEmpty()) return super.mobInteract(player, hand);
        if (!level().isClientSide()) {
            long now = level().getGameTime();
            Long last = lastPet.get(player.getUUID());
            if (last == null || now - last >= PET_COOLDOWN) {
                befriend(player, 1);
                lastPet.put(player.getUUID(), now);
            }
            playSound(sounds().purrSound().value(), 1, getVoicePitch());
            react(player);
        }
        return InteractionResult.SUCCESS;
    }

    /** 被摸头、被喂：眯眼开心、蹭两下，冒出爱心。 */
    private void react(Player player) {
        mood(HAPPY, 50);
        playAction("purr");
        if (level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HEART, getX(), getY() + 1.55, getZ(), 2, .25, .1, .25, 0);
        }
    }

    // ---------------------------------------------------------------- 声音：原版小猫

    private static CatSoundVariant.CatSoundSet sounds() {
        return SoundEvents.CAT_SOUNDS.get(CatSoundVariants.SoundSet.CLASSIC).babySounds();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        if (sitting() && sleepy()) return random.nextInt(3) == 0 ? sounds().purrSound().value() : null;
        return random.nextInt(4) == 0 ? sounds().purreowSound().value() : sounds().ambientSound().value();
    }

    @Override
    public int getAmbientSoundInterval() {
        return 240;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return sounds().hurtSound().value();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return sounds().deathSound().value();
    }

    // ---------------------------------------------------------------- 存档

    private record Fond(UUID player, int value) {
        static final Codec<Fond> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("Player").forGetter(Fond::player),
                Codec.INT.fieldOf("Value").forGetter(Fond::value)).apply(i, Fond::new));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store("Appearance", APPEARANCE_CODEC, npcAppearance());
        output.putInt("Coat", coat());
        List<Fond> list = new ArrayList<>();
        fondness.forEach((k, v) -> list.add(new Fond(k, v)));
        output.store("Fondness", Fond.CODEC.listOf(), list);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read("Appearance", APPEARANCE_CODEC).ifPresent(this::setAppearance);
        setCoat(input.getIntOr("Coat", 0));
        fondness.clear();
        input.read("Fondness", Fond.CODEC.listOf()).ifPresent(l -> l.forEach(f -> fondness.put(f.player(), Math.clamp(f.value(), 0, MAX_FONDNESS))));
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

    /** 测试与场景用：直接设置某位玩家的亲近度。 */
    public void setFondness(Player player, int value) {
        fondness.put(player.getUUID(), Math.clamp(value, 0, MAX_FONDNESS));
    }

    public BlockPos homeOrHere() {
        return hasHome() ? getHomePosition() : blockPosition();
    }
}
