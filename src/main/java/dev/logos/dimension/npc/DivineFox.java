package dev.logos.dimension.npc;

import java.util.Map;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import com.mojang.serialization.Codec;
import dev.logos.dimension.LogosDimension;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 神狐：灵狐樱野的居民（docs/NPC_RACE_DIVINE_FOX.md）。
 *
 * <p>第一版只有外观与动作：生成时按零件表随机抽一次外观（同 {@link ForestElf}），平时闲逛、看人，受伤时逃开。
 * 眨眼、耳朵抖动、尾巴轻摆是动画文件里的常驻叠加层，在待机、走路等大部分状态下一直循环；
 * 死亡时先播受击，再播侧倒后面朝地面的死亡动画。战斗（神狐打刀）、语音与聚落里的生成以后再加。
 */
public class DivineFox extends PathfinderMob implements NpcAnimated, NpcPartsHolder {
    private static final EntityDataAccessor<String> APPEARANCE = SynchedEntityData.defineId(DivineFox.class, EntityDataSerializers.STRING);
    private static final Codec<Map<String, String>> APPEARANCE_CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING);
    private static final Identifier ASSET = LogosDimension.id("divine_fox");
    /** 受击（约 0.46 秒）与死亡动画（1.55 秒）播完，在地上停约 1 秒后消失。 */
    public static final int DEATH_TICKS = 60;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    /** 客户端解码缓存，同步数据变了才重新解码。 */
    private String decodedFrom;
    private Map<String, String> decoded;
    /** 死亡时的朝向（活着时为 NaN），见 {@link NpcDeathPose}。 */
    private float deathYaw = Float.NaN;

    public DivineFox(EntityType<? extends DivineFox> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.3);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(APPEARANCE, NpcParts.encode(NpcParts.DIVINE_FOX.defaults()));
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.3));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data) {
        setAppearance(NpcParts.DIVINE_FOX.random(level.getRandom()));
        if (reason == EntitySpawnReason.STRUCTURE) {
            setPersistenceRequired();
            setHomeTo(blockPosition(), 24);
        }
        return super.finalizeSpawn(level, difficulty, reason, data);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    public void setAppearance(Map<String, String> appearance) {
        entityData.set(APPEARANCE, NpcParts.encode(NpcParts.DIVINE_FOX.sanitize(appearance)));
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

    /** 倒下后朝向不再转动（NpcDeathPose）。 */
    @Override
    public void tick() {
        super.tick();
        deathYaw = isDeadOrDying() ? NpcDeathPose.hold(this, deathYaw) : Float.NaN;
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store("Appearance", APPEARANCE_CODEC, npcAppearance());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read("Appearance", APPEARANCE_CODEC).ifPresent(this::setAppearance);
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
