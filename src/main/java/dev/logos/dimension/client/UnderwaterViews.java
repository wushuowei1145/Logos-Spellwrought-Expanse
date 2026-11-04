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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 水下景观客户端场景：在水箱里摆海草/高海草/萤光海草/海带/海底石堆，截图；
 * 并校验特征注册、群系接线和实际世界生成命中。
 */
@LDLRegisterClient(name = "underwater_views", group = "underwater", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class UnderwaterViews implements UIScenario {
    private static final int GROUND = 240;
    private static final java.util.Set<String> WIRED = java.util.Set.of(
        "cloudsea_glass_lagoons", "cloudsea_pearl_basins", "cloudsea_tide_ribbons",
        "cloudsea_opal_dunes", "cloudsea_dream_basins");
    private BlockPos origin;
    private int worldGenHits;
    private boolean wired;

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
                // 世界生成：在远行大陆找一片水，数一数 3×3 区块里生成了多少水下植物
                worldGenHits = countWorldGen(level);
                wired = biomeWired(level, "cloudsea_glass_lagoons", "journey_seagrass");

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
            .checkServer("worldgen places underwater plants", ctx -> worldGenHits > 0)
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
            .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    /** 在远行大陆找一片有水的地方，统计 3×3 区块内本附属水下植物的数量。 */
    private static int countWorldGen(ServerLevel overworld) {
        ServerLevel level = overworld.getServer().getLevel(LogosDimension.CLOUDSEA);
        if (level == null) return 0;
        var gen = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        BlockPos anchor = NativeWorlds.locateBiome(level, "cloudsea_glass_lagoons");
        BlockPos water = null;
        outer:
        for (int dx = -512; dx <= 512; dx += 32) {
            for (int dz = -512; dz <= 512; dz += 32) {
                int x = anchor.getX() + dx, z = anchor.getZ() + dz;
                int surf = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                int floor = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                String biome = gen.getBiomeSource().getNoiseBiome(x >> 2, surf >> 2, z >> 2, random.sampler())
                    .unwrapKey().map(k -> k.identifier().getPath()).orElse("");
                if (surf - floor >= 3 && WIRED.contains(biome)) {
                    water = new BlockPos(x, floor, z);
                    System.out.println("WATER_COLUMN " + x + "," + z + " floor=" + floor
                        + " surf=" + surf + " biome=" + biome);
                    break outer;
                }
            }
        }
        if (water == null) return 0;
        int hits = 0;
        java.util.Map<String, Integer> supports = new java.util.TreeMap<>();
        int cx = water.getX() >> 4, cz = water.getZ() >> 4;
        for (int cxd = -1; cxd <= 1; cxd++) {
            for (int czd = -1; czd <= 1; czd++) {
                level.getChunk(cx + cxd, cz + czd);
            }
        }
        for (int x = water.getX() - 24; x <= water.getX() + 24; x++) {
            for (int z = water.getZ() - 24; z <= water.getZ() + 24; z++) {
                for (int y = water.getY() - 1; y <= water.getY() + 24; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    var state = level.getBlockState(p);
                    if (state.is(AquaticFlora.SEAGRASS.get()) || state.is(AquaticFlora.TALL_SEAGRASS.get())
                        || state.is(AquaticFlora.GLOW_SEAGRASS.get()) || state.is(AquaticFlora.KELP.get())
                        || state.is(AquaticFlora.KELP_PLANT.get())) {
                        hits++;
                        String below = level.getBlockState(p.below()).getBlock().toString();
                        supports.merge(below, 1, Integer::sum);
                    }
                }
            }
        }
        System.out.println("UNDERWATER_SCAN anchor=" + anchor + " water=" + water + " hits=" + hits
            + " supports=" + supports);
        return hits;
    }

    private static boolean biomeWired(ServerLevel level, String biome, String placed) {
        var biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        var placedFeatures = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        var biomeHolder = biomes.get(ResourceKey.create(Registries.BIOME, LogosDimension.id(biome)));
        var placedKey = ResourceKey.create(Registries.PLACED_FEATURE, LogosDimension.id(placed));
        if (biomeHolder.isEmpty() || placedFeatures.get(placedKey).isEmpty()) return false;
        return biomeHolder.get().value().getGenerationSettings().features().stream()
            .anyMatch(list -> list.stream().anyMatch(h -> h.is(placedKey)));
    }
}
