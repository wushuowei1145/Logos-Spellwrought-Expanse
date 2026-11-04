package dev.logos.dimension.fox;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;
import java.util.Random;

/**
 * 放置聚落模板时的小替换，每处聚落、每块模板各不相同（由模板的种子决定，同一块模板跨区块结果一致）：
 * 暖帘与幟的颜色按每块模板（每家店）随机；盆栽与小花逐格换品种；田里的作物按 8×8 一片换种类、成熟度按比例保留；
 * 石砖、石阶、半砖、矮墙与圆石按本聚落的风化程度变成青苔或裂纹；朝上的酒桶、干草捆与堆肥桶互换。
 */
final class SanctuaryProcessor extends StructureProcessor {
    static final MapCodec<SanctuaryProcessor> CODEC = Codec.LONG.fieldOf("seed").xmap(SanctuaryProcessor::new, p -> p.seed);

    private static final List<Block> FLOWERS = List.of(Blocks.POPPY, Blocks.DANDELION, Blocks.OXEYE_DAISY, Blocks.ALLIUM, Blocks.AZURE_BLUET,
        Blocks.CORNFLOWER, Blocks.PINK_TULIP, Blocks.RED_TULIP, Blocks.WHITE_TULIP, Blocks.LILY_OF_THE_VALLEY);
    private static final List<Block> POTS = List.of(Blocks.POTTED_AZALEA, Blocks.POTTED_FLOWERING_AZALEA, Blocks.POTTED_FERN, Blocks.POTTED_BAMBOO,
        Blocks.POTTED_CHERRY_SAPLING, Blocks.POTTED_SPRUCE_SAPLING, Blocks.POTTED_RED_TULIP, Blocks.POTTED_LILY_OF_THE_VALLEY,
        Blocks.POTTED_PINK_TULIP, Blocks.POTTED_CORNFLOWER, Blocks.POTTED_AZURE_BLUET);
    private static final List<Block> CROPS = List.of(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS);
    private static final List<Block> BANNERS = List.of(Blocks.RED_BANNER, Blocks.WHITE_BANNER, Blocks.BLUE_BANNER, Blocks.ORANGE_BANNER,
        Blocks.YELLOW_BANNER, Blocks.CYAN_BANNER, Blocks.PURPLE_BANNER, Blocks.BLACK_BANNER);
    private static final List<Block> PROPS = List.of(Blocks.BARREL, Blocks.HAY_BLOCK, Blocks.COMPOSTER);

    private final long seed;
    /** 本聚落的风化程度，0.5–1.5 倍。 */
    private final double weather;
    private final Block[] noren = new Block[4];
    private final Block[] banners = new Block[BANNERS.size()];

    SanctuaryProcessor(long seed) {
        this.seed = seed;
        Random random = new Random(seed);
        List<Block> colors = norenColors();
        for (int i = 0; i < noren.length; i++) noren[i] = colors.get(random.nextInt(colors.size()));
        for (int i = 0; i < banners.length; i++) banners[i] = BANNERS.get(random.nextInt(BANNERS.size()));
        // 风化程度跟整处聚落走：种子的高位来自聚落，低位才混进模板位置
        this.weather = 0.5 + ((seed >>> 40) & 1023) / 1023.0;
    }

    private static List<Block> norenColors() {
        return List.of(FoxWildsBlocks.NOREN.get(), FoxWildsBlocks.NOREN_RED.get(), FoxWildsBlocks.NOREN_WHITE.get(), FoxWildsBlocks.NOREN_BROWN.get());
    }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos, StructureTemplate.StructureBlockInfo blockInfo,
                                                             StructureTemplate.StructureBlockInfo relative, StructurePlaceSettings settings) {
        BlockState state = relative.state();
        BlockPos p = relative.pos();
        BlockState out = swap(state, p);
        return out == state ? relative : new StructureTemplate.StructureBlockInfo(p, out, relative.nbt());
    }

    private BlockState swap(BlockState state, BlockPos p) {
        Block block = state.getBlock();
        long h = hash(p.getX(), p.getY(), p.getZ());
        int r = (int) (h % 1000);
        int ni = norenColors().indexOf(block);
        if (ni >= 0) return copy(state, noren[ni]);
        int bi = BANNERS.indexOf(block);
        if (bi >= 0) return copy(state, banners[bi]);
        if (POTS.contains(block)) return POTS.get((int) ((h >> 12) % POTS.size())).defaultBlockState();
        if (FLOWERS.contains(block)) return FLOWERS.get((int) ((h >> 12) % FLOWERS.size())).defaultBlockState();
        if (block instanceof CropBlock crop && CROPS.contains(block)) {
            Block to = CROPS.get((int) (hash(p.getX() >> 3, 0, p.getZ() >> 3) % CROPS.size()));
            if (to == block) return state;
            CropBlock target = (CropBlock) to;
            int age = Math.round(crop.getAge(state) * (float) target.getMaxAge() / crop.getMaxAge());
            return target.getStateForAge(age);
        }
        if (block == Blocks.BARREL && state.getValue(BlockStateProperties.FACING) != Direction.UP) return state;
        if (PROPS.contains(block) && (block != Blocks.HAY_BLOCK || state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y)) {
            Block to = PROPS.get((int) ((h >> 12) % PROPS.size()));
            if (to == Blocks.BARREL) return Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
            return to.defaultBlockState();
        }
        double mossy = 90 * weather, cracked = mossy + 45 * weather;
        if (block == Blocks.STONE_BRICKS) {
            return r < mossy ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : r < cracked ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState() : state;
        }
        if (r >= mossy) return state;
        if (block == Blocks.STONE_BRICK_STAIRS) return copy(state, Blocks.MOSSY_STONE_BRICK_STAIRS);
        if (block == Blocks.STONE_BRICK_SLAB) return copy(state, Blocks.MOSSY_STONE_BRICK_SLAB);
        if (block == Blocks.STONE_BRICK_WALL) return copy(state, Blocks.MOSSY_STONE_BRICK_WALL);
        if (block == Blocks.COBBLESTONE) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        return state;
    }

    /** 换成另一种方块，能对上的属性（朝向、半砖类型、含水……）照抄。 */
    private static BlockState copy(BlockState from, Block to) {
        BlockState out = to.defaultBlockState();
        for (Property<?> property : from.getProperties()) {
            if (out.hasProperty(property)) out = copyValue(from, out, property);
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState copyValue(BlockState from, BlockState to, Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }

    private long hash(int x, int y, int z) {
        long h = seed ^ (x * 341873128712L) ^ (y * 2654435761L) ^ (z * 132897987541L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h & Long.MAX_VALUE;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return FoxSanctuary.PROCESSOR.get();
    }
}
