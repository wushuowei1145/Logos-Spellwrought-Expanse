package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.NativeWorlds;
import dev.logos.dimension.cloudsea.AquaticFlora;
import dev.logos.dimension.cloudsea.JourneyKelpBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 水下景观客户端场景：在水箱里摆海草/高海草/萤光海草/海带/海底石堆，截图；
 * 并校验群系接线、衰退标签与剪刀掉落，以及泻湖与海湾实际生成的海床、水下植物和石堆。
 */
@LDLRegisterClient(name = "underwater_views", group = "underwater", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class UnderwaterViews implements UIScenario {
    private static final int GROUND = 240;
    private static final TagKey<Block> FRAGILE =
        TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("logos", "fragile_plants"));
    private BlockPos origin;
    private Scan lagoon, sea;
    private boolean wired, tagged, shearsOnly;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("underwater").defaultTimeoutMs(180000).scenarioTimeoutMs(600000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
            .runCommand("difficulty peaceful")
            .runCommand("time set day")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.renderDistance().set(8);
                mc.options.fov().set(70);
                mc.options.broadcastOptions();
            })
            .server("stage underwater tank", ctx -> {
                ServerLevel level = ctx.server().overworld();
                origin = new BlockPos(0, GROUND, 0);
                // 13×9 的玻璃水箱，水深 5 格
                for (int x = -6; x <= 6; x++) {
                    for (int z = -4; z <= 6; z++) {
                        level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.SAND.defaultBlockState());
                        boolean wall = x == -6 || x == 6 || z == -4 || z == 6;
                        for (int y = 1; y <= 7; y++) {
                            BlockPos p = origin.offset(x, y, z);
                            if (wall) {
                                level.setBlockAndUpdate(p, Blocks.GLASS.defaultBlockState());
                            } else if (y <= 5) {
                                level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
                            } else {
                                level.removeBlock(p, false);
                            }
                        }
                    }
                }
                // 海底小石堆
                level.setBlockAndUpdate(origin.offset(-4, 1, 2), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                level.setBlockAndUpdate(origin.offset(-3, 1, 2), dev.logos.dimension.cloudsea.CloudseaBlocks.LIMESTONE.get().defaultBlockState());
                level.setBlockAndUpdate(origin.offset(-3, 2, 2), dev.logos.dimension.cloudsea.CloudseaBlocks.LIMESTONE.get().defaultBlockState());
                level.setBlockAndUpdate(origin.offset(-4, 1, 3), dev.logos.dimension.cloudsea.CloudseaBlocks.LIMESTONE.get().defaultBlockState());
                // 海草与高海草
                for (int i = 0; i < 5; i++) {
                    level.setBlock(origin.offset(-2 + i * 2, 1, -2), AquaticFlora.SEAGRASS.get().defaultBlockState(), 2);
                }
                level.setBlock(origin.offset(0, 1, 0), AquaticFlora.TALL_SEAGRASS.get().defaultBlockState(), 2);
                level.setBlock(origin.offset(0, 2, 0), AquaticFlora.TALL_SEAGRASS.get().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.TallSeagrassBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
                // 萤光海草
                for (int i = 0; i < 3; i++) {
                    level.setBlock(origin.offset(3, 1, -1 + i * 2), AquaticFlora.GLOW_SEAGRASS.get().defaultBlockState(), 2);
                }
                // 海带（茎 + 顶叶）
                for (int y = 1; y <= 4; y++) {
                    level.setBlock(origin.offset(4, y, 2), AquaticFlora.KELP_PLANT.get().defaultBlockState(), 2);
                }
                level.setBlock(origin.offset(4, 5, 2), AquaticFlora.KELP.get().defaultBlockState()
                    .setValue(JourneyKelpBlock.AGE, 25), 2);

                // 原版海草作对照
                level.setBlock(origin.offset(2, 1, 0), Blocks.SEAGRASS.defaultBlockState(), 2);
                // 世界生成：分别在泻湖与海湾附近找水，统计海床、水下植物与石堆
                lagoon = scan(level, "cloudsea_glass_lagoons", 3);
                sea = scan(level, "cloudsea_tide_ribbons", 8);
                wired = biomeWired(level, "cloudsea_glass_lagoons", "logos_dimension:journey_seagrass_river")
                    && biomeWired(level, "cloudsea_tide_ribbons", "logos_dimension:journey_kelp_warm")
                    && biomeWired(level, "cloudsea_root_caves", "logos_dimension:journey_seagrass_deep")
                    && biomeWired(level, "cloudsea_root_caves", "logos_dimension:underwater_rock_pile")
                    && biomeWired(level, "cloudsea_pearl_basins", "minecraft:disk_clay");
                tagged = java.util.stream.Stream.of(AquaticFlora.SEAGRASS, AquaticFlora.TALL_SEAGRASS,
                        AquaticFlora.GLOW_SEAGRASS, AquaticFlora.KELP, AquaticFlora.KELP_PLANT)
                    .allMatch(b -> b.get().defaultBlockState().is(FRAGILE));
                // 掉落照原版：海草空手不掉、剪刀才掉；海带空手也掉
                BlockState seagrass = AquaticFlora.SEAGRASS.get().defaultBlockState();
                shearsOnly = Block.getDrops(seagrass, level, origin, null, null, ItemStack.EMPTY).isEmpty()
                    && !Block.getDrops(seagrass, level, origin, null, null, new ItemStack(Items.SHEARS)).isEmpty()
                    && !Block.getDrops(AquaticFlora.KELP_PLANT.get().defaultBlockState(), level, origin, null, null,
                        ItemStack.EMPTY).isEmpty();

                DimensionTravel.teleport(ctx.player(), level,
                    new Vec3(origin.getX() + 0.5, GROUND + 3.0, origin.getZ() + 4.0), 180f, 0f);
            })
            .waitUntil("tank arrives", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && mc.level.hasChunk(origin.getX() >> 4, origin.getZ() >> 4)
                    && !mc.level.getBlockState(origin.offset(0, 1, 0)).isAir();
            })
            .ticks(60)
            .checkServer("marine biome wires underwater features", ctx -> wired)
            .checkServer("worldgen places underwater plants", ctx -> lagoon.plants > 0 && sea.plants > 0)
            .checkServer("sea floor is not bare stone", ctx -> sea.bareStoneShare() < 0.02 && lagoon.bareStoneShare() < 0.02)
            .checkServer("rock piles only underwater", ctx -> sea.rocksUnderwater + lagoon.rocksUnderwater > 0
                && sea.rocksOnLand == 0 && lagoon.rocksOnLand == 0)
            .checkServer("underwater plants decay", ctx -> tagged)
            .checkServer("seagrass drops only with shears", ctx -> shearsOnly)
            .checkServer("plants and rocks placed in tank", ctx -> {
                ServerLevel level = ctx.server().overworld();
                return level.getBlockState(origin.offset(0, 1, 0)).is(AquaticFlora.TALL_SEAGRASS.get())
                    && level.getBlockState(origin.offset(4, 5, 2)).is(AquaticFlora.KELP.get())
                    && level.getBlockState(origin.offset(-3, 2, 2)).is(dev.logos.dimension.cloudsea.CloudseaBlocks.LIMESTONE.get());
            })
            .check("tank visible on client", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level.getBlockState(origin.offset(4, 5, 2)).is(AquaticFlora.KELP.get());
            })
            .screenshot("underwater_plants")
            // 实际生成的海湾海床：站在统计过的那片水里往下看
            .server("dive into generated sea", ctx -> {
                ServerLevel sea = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                DimensionTravel.teleport(ctx.player(), sea,
                    new Vec3(this.sea.water.getX() + 0.5, this.sea.water.getY() + 5.0, this.sea.water.getZ() + 0.5), 135f, 40f);
            })
            .waitUntil("sea chunks arrive", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && mc.level.dimension() == LogosDimension.CLOUDSEA
                    && mc.level.hasChunk(sea.water.getX() >> 4, sea.water.getZ() >> 4);
            })
            .ticks(80)
            .screenshot("generated_seabed")
            .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    /** 一片水域的统计：水下植物数、海床表层方块、水下与岸上的石堆块数。 */
    private static final class Scan {
        int plants, rocksUnderwater, rocksOnLand, floors, stoneCarved, stoneOpen;
        BlockPos water = BlockPos.ZERO;
        final java.util.Map<String, Integer> floor = new java.util.TreeMap<>();
        final java.util.Map<String, Integer> supports = new java.util.TreeMap<>();

        /** 没被洞穴/峡谷挖到的海床里，裸石头所占比例。 */
        double bareStoneShare() {
            return floors == 0 ? 1 : stoneOpen / (double) floors;
        }
    }

    private static final int R = 48;

    /**
     * 在远行大陆指定群系附近找一处水深至少 minDepth 的水，生成周围 7×7 区块后逐列统计：
     * 被水淹没的列记海床表层方块，并数水下植物与石堆；干地上的石堆单独计数（不应有）。
     * 洞穴与峡谷雕刻在地表规则之后运行，挖开处露石头与原版一致：实际地面比生成器预估的海床
     * 低 2 格以上的列算挖开，挖开处 4 格以内的裸石头算作峡谷边缘，不计入海床裸石头。
     */
    private static Scan scan(ServerLevel overworld, String near, int minDepth) {
        Scan scan = new Scan();
        ServerLevel level = overworld.getServer().getLevel(LogosDimension.CLOUDSEA);
        if (level == null) return scan;
        var gen = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        BlockPos anchor = NativeWorlds.locateBiome(level, near);
        BlockPos water = null;
        outer:
        for (int dx = -512; dx <= 512; dx += 32) {
            for (int dz = -512; dz <= 512; dz += 32) {
                int x = anchor.getX() + dx, z = anchor.getZ() + dz;
                int surf = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                int floor = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                if (surf - floor >= minDepth) {
                    water = new BlockPos(x, floor, z);
                    break outer;
                }
            }
        }
        if (water == null) return scan;
        scan.water = water;
        int cx = water.getX() >> 4, cz = water.getZ() >> 4;
        for (int cxd = -3; cxd <= 3; cxd++) {
            for (int czd = -3; czd <= 3; czd++) {
                level.getChunk(cx + cxd, cz + czd);
            }
        }
        Block limestone = dev.logos.dimension.cloudsea.CloudseaBlocks.LIMESTONE.get();
        int n = 2 * R + 1;
        String[][] ground = new String[n][n];
        boolean[][] carved = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                int x = water.getX() - R + i, z = water.getZ() - R + k;
                int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                if (level.getFluidState(new BlockPos(x, top, z)).is(net.minecraft.tags.FluidTags.WATER)) {
                    BlockPos g = new BlockPos(x, top - 1, z);
                    while (g.getY() > level.getMinY() && isRock(level.getBlockState(g), limestone)) g = g.below();
                    ground[i][k] = name(level.getBlockState(g));
                    int planned = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                    carved[i][k] = g.getY() + 1 < planned - 2;
                }
                for (int y = top - 3; y <= top + 24; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(p);
                    if (isPlant(state)) {
                        scan.plants++;
                        scan.supports.merge(name(level.getBlockState(p.below())), 1, Integer::sum);
                    } else if (isRock(state, limestone) && y >= top - 2) {
                        // 石堆是贴着地表的小堆：上面是水（或水草）算水下，上面是空气算岸上
                        BlockState above = level.getBlockState(p.above());
                        if (above.is(Blocks.WATER) || isPlant(above)) scan.rocksUnderwater++;
                        else if (above.isAir()) scan.rocksOnLand++;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                if (ground[i][k] == null) continue;
                scan.floors++;
                scan.floor.merge(ground[i][k], 1, Integer::sum);
                if (!ground[i][k].equals("stone")) continue;
                boolean nearCarving = false;
                for (int a = Math.max(0, i - 4); a <= Math.min(n - 1, i + 4) && !nearCarving; a++) {
                    for (int b = Math.max(0, k - 4); b <= Math.min(n - 1, k + 4); b++) {
                        if (carved[a][b]) { nearCarving = true; break; }
                    }
                }
                if (nearCarving) scan.stoneCarved++;
                else scan.stoneOpen++;
            }
        }
        System.out.println("UNDERWATER_SCAN near=" + near + " water=" + water + " plants=" + scan.plants
            + " rocksUnderwater=" + scan.rocksUnderwater + " rocksOnLand=" + scan.rocksOnLand
            + " floors=" + scan.floors + " floor=" + scan.floor + " stoneByCarving=" + scan.stoneCarved
            + " stoneOpen=" + scan.stoneOpen + " supports=" + scan.supports);
        return scan;
    }

    private static String name(BlockState state) {
        return state.getBlock().builtInRegistryHolder().key().identifier().getPath();
    }

    private static boolean isPlant(BlockState state) {
        return state.is(AquaticFlora.SEAGRASS.get()) || state.is(AquaticFlora.TALL_SEAGRASS.get())
            || state.is(AquaticFlora.GLOW_SEAGRASS.get()) || state.is(AquaticFlora.KELP.get())
            || state.is(AquaticFlora.KELP_PLANT.get());
    }

    private static boolean isRock(BlockState state, Block limestone) {
        return state.is(limestone) || state.is(Blocks.MOSSY_COBBLESTONE);
    }

    private static boolean biomeWired(ServerLevel level, String biome, String placed) {
        var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        var placedFeatures = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        var biomeHolder = biomes.get(ResourceKey.create(Registries.BIOME, LogosDimension.id(biome)));
        var placedKey = ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(placed));
        if (biomeHolder.isEmpty() || placedFeatures.get(placedKey).isEmpty()) return false;
        return biomeHolder.get().value().getGenerationSettings().features().stream()
            .anyMatch(list -> list.stream().anyMatch(h -> h.is(placedKey)));
    }
}
