package dev.logos.dimension.world;

import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

/**
 * 让 Logos 的罐栽植物也能种在云海草皮上。
 *
 * <p>Logos 从 #328 起给每种罐栽植物一个独立基底标签 {@code logos:plantable_on/<作物>}，但明确说明
 * 标签只改 {@code mayPlaceOn}，右键种植、收获和地面掉落要由附属自己补（作物本身没有物品和掉落）。
 * 这里补三件事：
 *
 * <ul>
 *   <li>拿着对应的种子右键草皮顶面 → 种下作物（消耗一颗种子）；</li>
 *   <li>地面作物的随机刻按概率跳过，明显慢于培养罐（罐子一次随机刻可能连涨多级）；</li>
 *   <li>破坏时按配方产物掉落，成熟时额外给一颗种子，能继续种。</li>
 * </ul>
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class WildCrops {
    /** 一颗种子的最大成熟阶段，与 {@code JarCropBlock.AGE_3} 一致。 */
    private static final int MAX_AGE = 3;
    /** 地面每刻真正推进生长的概率：罐子约 0.5，地面再减半。 */
    private static final float FIELD_GROWTH = 0.5f;

    private record Harvest(Identifier item, int count) {}

    /** 种子 → 作物，以及作物成熟后的产物（与 Logos 的 cultivation 配方一致）。 */
    private static final Map<Identifier, Identifier> SEED_TO_CROP = Map.ofEntries(
        Map.entry(id("logos:mandrake_seed"), id("logos:mandrake_crop")),
        Map.entry(id("logos:starlight_moss_spore"), id("logos:starlight_moss_crop")),
        Map.entry(id("logos:mercury_spore"), id("logos:mercury_mushroom_crop")),
        Map.entry(id("logos:niter_sprout"), id("logos:niter_sprout_crop")),
        Map.entry(id("logos:copper_seed"), id("logos:copper_pod_crop")),
        Map.entry(id("logos:iron_seed"), id("logos:iron_pod_crop")),
        Map.entry(id("logos:gold_seed"), id("logos:gold_pod_crop")),
        Map.entry(id("logos:netherite_seed"), id("logos:netherite_pod_crop")),
        Map.entry(id("logos:candleflame"), id("logos:candleflame_crop")),
        Map.entry(id("logos:frostbell"), id("logos:frostbell_crop")),
        Map.entry(id("logos:thunder_thistle"), id("logos:thunder_thistle_crop")),
        Map.entry(id("logos:riftshade"), id("logos:riftshade_crop")),
        Map.entry(id("logos:redvein_root"), id("logos:redvein_root_crop")));

    private static final Set<Identifier> CROPS = Set.copyOf(SEED_TO_CROP.values());

    private static final Map<Identifier, List<Harvest>> HARVEST = Map.ofEntries(
        Map.entry(id("logos:mandrake_crop"), List.of(new Harvest(id("logos:mandrake_root"), 2))),
        Map.entry(id("logos:starlight_moss_crop"), List.of(new Harvest(id("logos:starlight_moss"), 2))),
        Map.entry(id("logos:mercury_mushroom_crop"), List.of(new Harvest(id("logos:mercury_mushroom"), 2))),
        Map.entry(id("logos:niter_sprout_crop"),
            List.of(new Harvest(id("logos:saltpeter"), 2), new Harvest(id("logos:niter_sprout"), 2))),
        Map.entry(id("logos:copper_pod_crop"), List.of(new Harvest(id("logos:copper_pod"), 1))),
        Map.entry(id("logos:iron_pod_crop"), List.of(new Harvest(id("logos:iron_pod"), 1))),
        Map.entry(id("logos:gold_pod_crop"), List.of(new Harvest(id("logos:gold_pod"), 1))),
        Map.entry(id("logos:netherite_pod_crop"), List.of(new Harvest(id("logos:netherite_pod"), 1))),
        Map.entry(id("logos:candleflame_crop"), List.of(new Harvest(id("logos:candleflame_stamen"), 2))),
        Map.entry(id("logos:frostbell_crop"), List.of(new Harvest(id("logos:frostbell_petal"), 2))),
        Map.entry(id("logos:thunder_thistle_crop"), List.of(new Harvest(id("logos:thunder_thistle_down"), 2))),
        Map.entry(id("logos:riftshade_crop"), List.of(new Harvest(id("logos:riftshade_pollen"), 2))),
        Map.entry(id("logos:redvein_root_crop"), List.of(new Harvest(id("logos:redvein_root"), 2))));

    private WildCrops() {}

    private static Identifier id(String value) {
        return Identifier.parse(value);
    }

    private static boolean inOurWorld(Level level) {
        return level.dimension().equals(LogosDimension.CLOUDSEA) || level.dimension().equals(LogosDimension.CRUCIBLE);
    }

    /** 作物方块对应的种子物品（收获时还田用）。 */
    private static Identifier seedFor(Identifier crop) {
        for (var entry : SEED_TO_CROP.entrySet()) {
            if (entry.getValue().equals(crop)) return entry.getKey();
        }
        return null;
    }

    /** 右键草皮顶面：把种子种成对应的罐栽植物。 */
    @SubscribeEvent
    static void plant(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (event.getFace() != Direction.UP) return;
        if (!inOurWorld(level)) return;
        BlockPos soil = event.getPos();
        if (!level.getBlockState(soil).is(net.minecraft.tags.BlockTags.DIRT)) return;

        Identifier cropId = SEED_TO_CROP.get(BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem()));
        if (cropId == null) return;
        Block cropBlock = BuiltInRegistries.BLOCK.getValue(cropId);
        if (cropBlock == null) return;

        BlockPos target = soil.above();
        BlockState crop = cropBlock.defaultBlockState();
        if (!level.getBlockState(target).canBeReplaced() || !crop.canSurvive(level, target)) return;

        level.setBlock(target, crop, Block.UPDATE_ALL);
        if (!event.getEntity().getAbilities().instabuild) event.getItemStack().shrink(1);
        level.playSound(null, target, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1f, 1f);
        if (level instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX() + .5, target.getY() + .3, target.getZ() + .5,
                4, .2, .1, .2, 0);
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    /** 地面生长比罐子再慢一档：一半的随机刻直接跳过。 */
    @SubscribeEvent
    static void slow(CropGrowEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!inOurWorld(level)) return;
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock());
        if (!CROPS.contains(blockId)) return;
        if (level.getRandom().nextFloat() >= FIELD_GROWTH) {
            event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
        }
    }

    /** 破坏成熟作物：按配方产物掉落；另外给一颗种子，能继续种。 */
    @SubscribeEvent
    static void drops(BlockDropsEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!inOurWorld(level)) return;
        BlockState state = event.getState();
        Identifier cropId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        List<Harvest> harvest = HARVEST.get(cropId);
        if (harvest == null || !(state.getBlock() instanceof CropBlock cropBlock)) return;
        if (!cropBlock.isMaxAge(state)) return;

        BlockPos pos = event.getPos();
        for (Harvest drop : harvest) {
            Item item = BuiltInRegistries.ITEM.getValue(drop.item());
            if (item != null) {
                event.getDrops().add(new ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5,
                    new ItemStack(item, drop.count())));
            }
        }
        Identifier seed = seedFor(cropId);
        if (seed != null) {
            Item seedItem = BuiltInRegistries.ITEM.getValue(seed);
            if (seedItem != null) {
                event.getDrops().add(new ItemEntity(level, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5,
                    new ItemStack(seedItem, 1)));
            }
        }
    }

    /** 供测试查询：某个作物是否被地面种植接管。 */
    public static boolean handles(Identifier blockId) {
        return CROPS.contains(blockId);
    }
}
