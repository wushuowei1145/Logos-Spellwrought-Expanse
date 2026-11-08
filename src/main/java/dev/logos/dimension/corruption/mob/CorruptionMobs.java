package dev.logos.dimension.corruption.mob;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.ServerConfig;
import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.corruption.CorruptionContact;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 污秽衍生物：腐败区域里刷出来的怪，是原版僵尸、骷髅、蠹虫、史莱姆的变体。
 * <ul>
 * <li>脚下是腐败方块（污秽或脉络）的地方，原版刷怪时“怪物”这一类的候选全换成它们，别的怪不刷；不看亮度，白天也刷。
 *     其余照原版：占怪物上限、离玩家 24 格以外、和平模式不刷也会消失。
 * <li>它们打中东西时（近战、箭、史莱姆的碰撞）附带一次腐败伤害并流失魔力；它们自己碰腐败不受伤。
 * <li>僵尸和骷髅照样怕太阳，但站在腐败上不燃烧。
 * <li>核心满了以后存的储备也会拿来就地生它们（{@link dev.logos.dimension.corruption.CorruptionNetworks}）。
 * </ul>
 * 外观是原版的模型和贴图加上一层腐败（客户端 {@code client/corruption/CorruptedLook}）。说明见 docs/CORRUPTION.md。
 */
public final class CorruptionMobs {
    private CorruptionMobs() {}

    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);
    /** 被腐败的生物：衍生物都在里面。碰腐败不受伤，森林精灵和神狐会打它们。 */
    public static final TagKey<EntityType<?>> CORRUPTED = TagKey.create(Registries.ENTITY_TYPE, LogosDimension.id("corrupted"));

    public static final Supplier<EntityType<FilthZombie>> FILTH_ZOMBIE = TYPES.register("filth_zombie", () ->
            EntityType.Builder.<FilthZombie>of(FilthZombie::new, MobCategory.MONSTER).sized(.6f, 1.95f).eyeHeight(1.74f).passengerAttachments(2.0125f)
                    .ridingOffset(-.7f).clientTrackingRange(8).build(key("filth_zombie")));
    public static final Supplier<EntityType<FilthSkeleton>> FILTH_SKELETON = TYPES.register("filth_skeleton", () ->
            EntityType.Builder.<FilthSkeleton>of(FilthSkeleton::new, MobCategory.MONSTER).sized(.6f, 1.99f).eyeHeight(1.74f).ridingOffset(-.7f)
                    .clientTrackingRange(8).build(key("filth_skeleton")));
    public static final Supplier<EntityType<FilthSilverfish>> FILTH_SILVERFISH = TYPES.register("filth_silverfish", () ->
            EntityType.Builder.<FilthSilverfish>of(FilthSilverfish::new, MobCategory.MONSTER).sized(.4f, .3f).eyeHeight(.13f).passengerAttachments(.2375f)
                    .clientTrackingRange(8).build(key("filth_silverfish")));
    public static final Supplier<EntityType<FilthSlime>> FILTH_SLIME = TYPES.register("filth_slime", () ->
            EntityType.Builder.<FilthSlime>of(FilthSlime::new, MobCategory.MONSTER).sized(.52f, .52f).eyeHeight(.325f).spawnDimensionsScale(4)
                    .clientTrackingRange(10).build(key("filth_slime")));

    public static final DeferredItem<SpawnEggItem> FILTH_ZOMBIE_EGG = egg("filth_zombie", FILTH_ZOMBIE);
    public static final DeferredItem<SpawnEggItem> FILTH_SKELETON_EGG = egg("filth_skeleton", FILTH_SKELETON);
    public static final DeferredItem<SpawnEggItem> FILTH_SILVERFISH_EGG = egg("filth_silverfish", FILTH_SILVERFISH);
    public static final DeferredItem<SpawnEggItem> FILTH_SLIME_EGG = egg("filth_slime", FILTH_SLIME);

    private static ResourceKey<EntityType<?>> key(String name) {
        return ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id(name));
    }

    private static DeferredItem<SpawnEggItem> egg(String name, Supplier<? extends EntityType<? extends Mob>> type) {
        return DimensionBlocks.ITEMS.registerItem(name + "_spawn_egg", SpawnEggItem::new, p -> p.spawnEgg(type.get()));
    }

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        bus.addListener(CorruptionMobs::attributes);
        bus.addListener(CorruptionMobs::placements);
        bus.addListener(CorruptionMobs::creativeTab);
        NeoForge.EVENT_BUS.addListener(CorruptionMobs::potentialSpawns);
        NeoForge.EVENT_BUS.addListener(CorruptionMobs::onDamaged);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(FILTH_ZOMBIE.get(), Zombie.createAttributes().build());
        event.put(FILTH_SKELETON.get(), AbstractSkeleton.createAttributes().build());
        event.put(FILTH_SILVERFISH.get(), Silverfish.createAttributes().build());
        event.put(FILTH_SLIME.get(), Monster.createMonsterAttributes().build());
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            for (var egg : List.of(FILTH_ZOMBIE_EGG, FILTH_SKELETON_EGG, FILTH_SILVERFISH_EGG, FILTH_SLIME_EGG)) event.accept(egg.get());
        }
    }

    /** 实体是不是被腐败的（衍生物，以及以后复活成腐化版的生物）。 */
    public static boolean isCorrupted(Entity entity) {
        return entity.typeHolder().is(CORRUPTED);
    }

    /** {@code pos} 这个落脚点是不是在腐败上：脚下是污秽或脉络，或者就站在脉络里。 */
    public static boolean onCorruption(LevelReader level, BlockPos pos) {
        if (level.getBlockState(pos).is(Corruption.VEIN.get())) return true;
        BlockState below = level.getBlockState(pos.below());
        return below.is(Corruption.BLOCK.get()) || below.is(Corruption.VEIN.get());
    }

    // ---- 刷怪 ---------------------------------------------------------------------------------

    /** 和原版怪物一样站在地上刷；自然生成只在腐败上，而且不看亮度。刷怪蛋、命令不受这条限制。 */
    private static void placements(RegisterSpawnPlacementsEvent event) {
        event.register(FILTH_ZOMBIE.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CorruptionMobs::canSpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(FILTH_SKELETON.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CorruptionMobs::canSpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(FILTH_SILVERFISH.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CorruptionMobs::canSpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(FILTH_SLIME.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CorruptionMobs::canSpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }

    public static <T extends Mob> boolean canSpawn(EntityType<T> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource random) {
        if (level.getDifficulty() == Difficulty.PEACEFUL) return false;
        if (reason == EntitySpawnReason.NATURAL && !onCorruption(level, pos)) return false;
        return Mob.checkMobSpawnRules(type, level, reason, pos, random);
    }

    /** 怪物这一类刷在腐败上时，候选只有衍生物：僵尸最多，史莱姆最少。 */
    public static List<Weighted<MobSpawnSettings.SpawnerData>> candidates() {
        return List.of(
                new Weighted<>(new MobSpawnSettings.SpawnerData(FILTH_ZOMBIE.get(), 1, 3), 40),
                new Weighted<>(new MobSpawnSettings.SpawnerData(FILTH_SKELETON.get(), 1, 2), 30),
                new Weighted<>(new MobSpawnSettings.SpawnerData(FILTH_SILVERFISH.get(), 2, 4), 20),
                new Weighted<>(new MobSpawnSettings.SpawnerData(FILTH_SLIME.get(), 1, 1), 10));
    }

    private static void potentialSpawns(LevelEvent.PotentialSpawns event) {
        if (event.getMobCategory() != MobCategory.MONSTER || !onCorruption(event.getLevel(), event.getPos())) return;
        for (var entry : List.copyOf(event.getSpawnerDataList())) event.removeSpawnerData(entry);
        candidates().forEach(event::addSpawnerData);
    }

    /** 随机挑一种衍生物，权重和自然生成一样。 */
    public static EntityType<? extends Mob> pick(RandomSource random) {
        int roll = random.nextInt(100);
        return roll < 40 ? FILTH_ZOMBIE.get() : roll < 70 ? FILTH_SKELETON.get() : roll < 90 ? FILTH_SILVERFISH.get() : FILTH_SLIME.get();
    }

    // ---- 攻击附带腐败 ---------------------------------------------------------------------------

    /** 被腐败的生物打中别的东西之后（近战、箭、史莱姆的碰撞都是它造成的）：再补一次腐败伤害并流失魔力。 */
    private static void onDamaged(LivingDamageEvent.Post event) {
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();
        Entity attacker = source.getEntity();
        if (attacker == null || source.is(Corruption.DAMAGE) || !isCorrupted(attacker) || !(target.level() instanceof ServerLevel level)) return;
        if (!target.isAlive() || CorruptionContact.immune(target)) return;
        float extra = ServerConfig.CORRUPTION_DERIVATIVE_DAMAGE.get().floatValue();
        CorruptionContact.drainMana(target, ServerConfig.CORRUPTION_MANA_DRAIN.get());
        if (extra <= 0) return;
        // 刚挨了一下还在无敌时间里，不清掉的话这一下打不进去
        target.invulnerableTime = 0;
        target.hurtServer(level, new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(Corruption.DAMAGE), attacker), extra);
    }
}
