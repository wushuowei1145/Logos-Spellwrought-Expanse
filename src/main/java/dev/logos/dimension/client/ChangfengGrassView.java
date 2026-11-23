package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBiome;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.cloudsea.JourneySeasons;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** 固定近景检查长风草的覆盖率和宽叶草簇；不把这块测试台当作自然生成地形。 */
@LDLRegisterClient(name = "changfeng_grass", group = "changfeng", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ChangfengGrassView implements UIScenario {
    private static final int Y = 180;
    private double coverage;
    @Override public void configure(ScenarioOptions options) { options.tags("changfeng").defaultTimeoutMs(120000).scenarioTimeoutMs(240000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("准备近景画面", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true; mc.options.renderDistance().set(6); mc.options.fov().set(65); mc.options.broadcastOptions();
            })
            .server("布置长风草皮", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                for (int x = -24; x <= 24; x++) for (int z = -24; z <= 16; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, Y, z), CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get().defaultBlockState());
                    for (int y = 1; y <= 5; y++) level.setBlockAndUpdate(new BlockPos(x, Y + y, z), Blocks.AIR.defaultBlockState());
                }
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK), 6000);
                level.getWeatherData().setRaining(false); level.getWeatherData().setThundering(false);
                DimensionTravel.teleport(ctx.player(), level, new Vec3(0, Y + 3, 12), 180, 12);
                ctx.player().getAbilities().flying = true; ctx.player().onUpdateAbilities();
            })
            .runCommand("execute in logos_dimension:cloudsea run fillbiome -24 172 -24 24 179 24 logos_dimension:cloudsea_silk_folds")
            .runCommand("execute in logos_dimension:cloudsea run fillbiome -24 180 -24 24 191 24 logos_dimension:cloudsea_silk_folds")
            .server("用原版放置特征生成草地", ctx -> {
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var feature=level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.PLACED_FEATURE,LogosDimension.id("cloudsea_silk_folds_floor")));
                for(int x:new int[]{-16,0}) for(int z:new int[]{-16,0}) feature.value().placeWithBiomeCheck(level,level.getChunkSource().getGenerator(),net.minecraft.util.RandomSource.create(8931+x*31+z),new BlockPos(x,Y+1,z));
                int grass=0;
                for(int x=-16;x<16;x++) for(int z=-16;z<16;z++) {
                    var state=level.getBlockState(new BlockPos(x,Y+1,z));
                    if(state.is(Blocks.TALL_GRASS)||state.is(Blocks.SHORT_GRASS)||state.is(CloudseaBlocks.MEADOW_GRASS_YOUNG.get())) grass++;
                }
                coverage=grass/1024.0;
                System.out.println("CHANGFENG_COVERAGE "+coverage);
            })
            .checkServer("原版生成草的覆盖率约八成",ctx->coverage>=.75&&coverage<=.86)
            .waitUntil("收到长风草丛", ctx -> Minecraft.getInstance().level != null && Minecraft.getInstance().level.dimension().equals(LogosDimension.CLOUDSEA))
            .waitUntil("测试地面已完整到达", ctx -> {
                var level = Minecraft.getInstance().level;
                for (int x : new int[]{-20, 0, 20}) for (int z : new int[]{-20, 0, 12})
                    if (!level.getBlockState(new BlockPos(x, Y, z)).is(CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get())) return false;
                return true;
            }).ticks(300)
            .check("长风草皮上的草都换成宽叶草簇", ctx -> ChangfengNaturalView.widened(new BlockPos(0, Y + 1, 0)) > 600)
            .screenshot("changfeng_grass");
    }
}
