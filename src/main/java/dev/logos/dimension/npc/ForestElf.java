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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 森林精灵（普通居民）的最小原型：随机外观、车万女仆同款动画驱动、简单闲逛。
 *
 * <p>外观在生成时抽一次，存解析后的零件 ID（docs/NPC_APPEARANCE.md 第 6 节）；年龄、伴侣、配色替换以后再加。
 */
public class ForestElf extends PathfinderMob implements NpcAnimated, NpcPartsHolder {
    private static final EntityDataAccessor<String> APPEARANCE = SynchedEntityData.defineId(ForestElf.class, EntityDataSerializers.STRING);
    private static final Codec<Map<String, String>> APPEARANCE_CODEC = Codec.unboundedMap(Codec.STRING, Codec.STRING);
    private static final Identifier ASSET = LogosDimension.id("forest_elf");
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    /** 客户端解码缓存，同步数据变了才重新解码。 */
    private String decodedFrom;
    private Map<String, String> decoded;

    public ForestElf(EntityType<? extends ForestElf> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(APPEARANCE, NpcParts.encode(NpcParts.FOREST_ELF.defaults()));
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data) {
        setAppearance(NpcParts.FOREST_ELF.random(level.getRandom()));
        return super.finalizeSpawn(level, difficulty, reason, data);
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
