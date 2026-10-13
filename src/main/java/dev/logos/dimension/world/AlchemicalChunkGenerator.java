package dev.logos.dimension.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.DimensionFluids;
import dev.logos.dimension.block.ReactiveMatterBlock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Chunk-local, bounded generation: giant landforms are sampled lazily, never prebuilt across 20,000 blocks. */
public final class AlchemicalChunkGenerator extends ChunkGenerator {
    public static final MapCodec<AlchemicalChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(AlchemicalChunkGenerator::getBiomeSource)
    ).apply(i, AlchemicalChunkGenerator::new));
    private final AlchemicalBiomeSource source;
    private volatile AlchemicalTerrain terrain;

    public AlchemicalChunkGenerator(BiomeSource source) {
        super(source);
        if (!(source instanceof AlchemicalBiomeSource alchemical)) throw new IllegalArgumentException("Crucible requires its alchemical biome source");
        this.source = alchemical;
        terrain = new AlchemicalTerrain(alchemical.layout());
    }
    public AlchemicalTerrain terrain() { return terrain; }
    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }
    @Override public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState random, long seed) {
        source.bindSeed(seed); terrain = new AlchemicalTerrain(source.layout());
        return super.createState(structures, random, seed);
    }
    @Override public void createStructures(net.minecraft.core.RegistryAccess access, ChunkGeneratorStructureState state,
            StructureManager manager, ChunkAccess chunk, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {}
    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk) {}
    @Override public void spawnOriginalMobs(WorldGenRegion region) {}
    @Override public int getMinY() { return AlchemicalTerrain.MIN_Y; }
    @Override public int getGenDepth() { return AlchemicalTerrain.HEIGHT; }
    @Override public int getSeaLevel() { return AlchemicalTerrain.SEA_LEVEL; }

    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
        var pos = new BlockPos.MutableBlockPos();
        var floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        var surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = chunk.getPos().getMinBlockX() + x, wz = chunk.getPos().getMinBlockZ() + z;
            var column = terrain.column(wx, wz);
            for (int y = getMinY(); y < getMinY() + getGenDepth(); y++) {
                BlockState state = stateAt(wx, y, wz, column);
                if (state.isAir()) continue;
                chunk.setBlockState(pos.set(wx, y, wz), state);
                floor.update(x, y, z, state); surface.update(x, y, z, state);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    public BlockState stateAt(int x, int y, int z, AlchemicalTerrain.Column c) {
        if (c.hasIsland() && y >= c.islandBottom() && y <= c.islandTop()) return DimensionBlocks.ISLAND_STONE.get().defaultBlockState();
        if (c.hasMetalIsland() && y >= c.metalBottom() && y <= c.metalTop()) return DimensionBlocks.MERCURY_METAL.get().defaultBlockState();
        BlockState apparatus = apparatus(x, y, z, c.geography().center());
        if (apparatus != null) return apparatus;
        if (y <= getMinY() + 3) return Blocks.BEDROCK.defaultBlockState();
        if (y > c.ground()) {
            if (y > c.liquidTop()) return Blocks.AIR.defaultBlockState();
            return switch (c.liquid()) {
                case WATER -> Blocks.WATER.defaultBlockState();
                case MERCURY -> DimensionFluids.MERCURY_BLOCK.get().defaultBlockState();
                case MOLTEN_GLASS -> DimensionFluids.GLASS_BLOCK.get().defaultBlockState();
                default -> Blocks.AIR.defaultBlockState();
            };
        }
        return switch (c.geography().region()) {
            case SULFUR -> {
                if (y < c.ground() - 3) yield DimensionBlocks.CHARRED_STONE.get().defaultBlockState();
                double vein = terrain.layout().noise(x / 24.0, z / 24.0, terrain.layout().seed() + 281);
                yield vein > .25 ? natural(DimensionBlocks.CINNABAR.get()) : vein < -.35 ? natural(DimensionBlocks.SULFUR_DEPOSIT.get())
                        : DimensionBlocks.CHARRED_STONE.get().defaultBlockState();
            }
            case MERCURY -> y > c.ground() - 4 ? natural(DimensionBlocks.MERCURY_CRUST.get()) : DimensionBlocks.MERCURY_METAL.get().defaultBlockState();
            case SALT -> y > c.ground() - 5 ? natural(DimensionBlocks.SALT_CRUST.get()) : DimensionBlocks.STRATA_STONE.get().defaultBlockState();
            default -> y == c.ground() && c.liquid() == AlchemicalTerrain.Liquid.NONE ? DimensionBlocks.AETHER_TURF.get().defaultBlockState()
                    : y > c.ground() - 4 ? DimensionBlocks.AETHER_SOIL.get().defaultBlockState() : DimensionBlocks.STRATA_STONE.get().defaultBlockState();
        };
    }

    /** Environmental retorts and conduits stay far outside every reserved central island. */
    private BlockState apparatus(int x, int y, int z, AlchemicalLayout.Center center) {
        double scale = terrain.layout().spacing() / 20_000.0;
        double fx = center.x() + 6000 * scale, fz = center.z() + 1200 * scale;
        double dx = x - fx, dz = z - fz;
        double d2 = dx * dx + dz * dz;
        double r = Math.max(24, 64 * scale);
        double dy = y - 303;
        if (d2 < r * r && y >= 303 - r && y <= 303 + r) {
            double sphere = Math.sqrt(d2 + dy * dy);
            if (sphere <= r && sphere > r - 1.6) return DimensionBlocks.DISTILLATION_GLASS.get().defaultBlockState();
            if (sphere < r - 2 && d2 < r * r * .48) {
                if (y >= 277 && y <= 285) return y == 285 ? DimensionBlocks.AETHER_TURF.get().defaultBlockState() : DimensionBlocks.STRATA_STONE.get().defaultBlockState();
                int lx = Math.floorMod(x, 17) - 8, lz = Math.floorMod(z, 17) - 8;
                if (y > 285 && y < 297 && lx == 0 && lz == 0) return DimensionBlocks.MERCURY_METAL.get().defaultBlockState();
                if (y >= 295 && y <= 298 && lx * lx + lz * lz < 17) return DimensionBlocks.GLASS_LEAVES.get().defaultBlockState();
            }
        }
        if (d2 <= 64 && y >= 199 && y <= 303 - r + 2) {
            if (d2 >= 36) return DimensionBlocks.DISTILLATION_GLASS.get().defaultBlockState();
            if (y == 199) return DimensionFluids.MERCURY_BLOCK.get().defaultBlockState();
        }
        // A slow curved pipe with liquid visible through its hollow shell.
        if (y > 150 && y < 390) {
            double px = center.x() - 5000 * scale + Math.sin((y - 150) / 120.0) * 68;
            double pz = center.z() + 4200 * scale;
            double pipe = Math.hypot(x - px, z - pz);
            if (pipe < 11 && pipe >= 9) return DimensionBlocks.DISTILLATION_GLASS.get().defaultBlockState();
            if (pipe < 3) return DimensionFluids.MERCURY_BLOCK.get().defaultBlockState();
        }
        return null;
    }

    private static BlockState natural(ReactiveMatterBlock block) { return block.defaultBlockState().setValue(ReactiveMatterBlock.NATURAL, true); }

    @Override public void buildSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk) {
        long salt = AlchemicalLayout.hash(chunk.getPos().x(), chunk.getPos().z(), terrain.layout().seed() + 337);
        int x = chunk.getPos().getMinBlockX() + 8, z = chunk.getPos().getMinBlockZ() + 8;
        var c = terrain.column(x, z);
        // Start only exposed fluid edges. Interior lake sources do not need thousands of idle ticks.
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int px = chunk.getPos().getMinBlockX() + dx, pz = chunk.getPos().getMinBlockZ() + dz;
            var col = terrain.column(px, pz);
            if (col.liquid() != AlchemicalTerrain.Liquid.NONE) {
                var p = new BlockPos(px, col.liquidTop(), pz);
                for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    var beside = p.relative(direction);
                    if (stateAt(beside.getX(), beside.getY(), beside.getZ(), terrain.column(beside.getX(), beside.getZ())).isAir()) {
                        var fluid = chunk.getFluidState(p).getType();
                        level.scheduleTick(p, fluid, fluid.getTickDelay(level)); break;
                    }
                }
            }
            var neck = new BlockPos(px, 199, pz);
            if (chunk.getFluidState(neck).getFluidType() == DimensionFluids.MERCURY_TYPE.get()
                    && chunk.getBlockState(neck.below()).isAir())
                level.scheduleTick(neck, DimensionFluids.MERCURY.get(), 15);
        }
        // Island tops are deliberately never decorated, including their peripheral slopes.
        if (c.geography().region() == AlchemicalLayout.Region.AETHER && c.liquid() == AlchemicalTerrain.Liquid.NONE) {
            if (AlchemicalLayout.unit(salt) > .15 && terrain.layout().noise(x / 320.0, z / 320.0, 733) > -.15)
                fanTree(chunk, x, c.ground() + 1, z, salt);
            for (int i = 0; i < 4; i++) {
                long h = AlchemicalLayout.mix(salt + i * 71);
                int px = chunk.getPos().getMinBlockX() + (int) (h & 15), pz = chunk.getPos().getMinBlockZ() + (int) ((h >>> 4) & 15);
                var ground = terrain.column(px, pz);
                if (ground.geography().region() == AlchemicalLayout.Region.AETHER && ground.liquid() == AlchemicalTerrain.Liquid.NONE)
                    decorate(chunk, px, ground.ground() + 1, pz, (h & 7) == 0 ? DimensionBlocks.STAR_BLOSSOM.get().defaultBlockState() : DimensionBlocks.AETHER_FERN.get().defaultBlockState());
            }
        }
        if (c.geography().region() == AlchemicalLayout.Region.SALT && AlchemicalLayout.unit(salt) > .76) {
            int height = 4 + (int) (salt & 7);
            for (int y = c.ground() + 1; y <= c.ground() + height; y++)
                decorate(chunk, x, y, z, natural(DimensionBlocks.SALT_CRYSTAL.get()));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (dx != 0 || dz != 0) for (int y = c.ground() + 1; y < c.ground() + height - 2; y++)
                    decorate(chunk, x + dx, y, z + dz, natural(DimensionBlocks.SALT_CRYSTAL.get()));
        }
    }

    private void fanTree(ChunkAccess chunk, int x, int y, int z, long salt) {
        int height = 10 + (int) ((salt >>> 9) & 5);
        for (int i = 0; i < height; i++) decorate(chunk, x, y + i, z, DimensionBlocks.FAN_TRUNK.get().defaultBlockState());
        for (int layer = 0; layer < 3; layer++) {
            int crownY = y + height - layer * 3;
            int radius = layer == 0 ? 4 : 3;
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + 1) continue;
                int fold = Math.max(Math.abs(dx), Math.abs(dz)) >= radius - 1 ? 1 : 0;
                decorate(chunk, x + dx, crownY + fold, z + dz, DimensionBlocks.FAN_LEAVES.get().defaultBlockState());
                if (layer == 0 && Math.abs(dx) + Math.abs(dz) == radius && ((dx + dz) & 1) == 0)
                    decorate(chunk, x + dx, crownY - 1, z + dz, DimensionBlocks.AMBER_SEEDS.get().defaultBlockState());
            }
        }
    }
    private void decorate(ChunkAccess chunk, int x, int y, int z, BlockState state) {
        var c = terrain.column(x, z);
        if (c.hasIsland() && y >= c.islandBottom()) return;
        BlockPos pos = new BlockPos(x, y, z);
        if (!chunk.getBlockState(pos).isAir()) return;
        chunk.setBlockState(pos, state);
        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).update(x & 15, y, z & 15, state);
        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG).update(x & 15, y, z & 15, state);
    }

    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heights, RandomState random) {
        var c = terrain.column(x, z);
        for (int y = Math.min(heights.getMaxY(), getMinY() + getGenDepth() - 1); y >= heights.getMinY(); y--)
            if (type.isOpaque().test(stateAt(x, y, z, c))) return y + 1;
        return heights.getMinY();
    }
    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heights, RandomState random) {
        var states = new BlockState[heights.getHeight()];
        var c = terrain.column(x, z);
        for (int i = 0; i < states.length; i++) states[i] = stateAt(x, i + heights.getMinY(), z, c);
        return new NoiseColumn(heights.getMinY(), states);
    }
    @Override public void addDebugScreenInfo(List<String> lines, RandomState random, BlockPos feet) {
        var s = terrain.layout().sample(feet.getX(), feet.getZ());
        lines.add("Crucible: " + s.region() + " / center " + s.center().x() + ", " + s.center().z());
    }
}
