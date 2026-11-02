package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import dev.logos.dimension.cloudsea.TallStoneLanternBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** 神狐聚落小品陈列（本地开发用）：在高空石台上排一行狐像、手水钵、暖帘等，拍近景。 */
@LDLRegisterClient(name = "fox_props_views", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class FoxPropsViews implements UIScenario {
    @Override public void configure(ScenarioOptions o) {
        o.tags("fox_dev").defaultTimeoutMs(200000).scenarioTimeoutMs(300000);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("time set noon").runCommand("weather clear")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.fov().set(60);
            })
            .server("build", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                int y = 300;
                for (int x = -2; x <= 22; x++) for (int z = -4; z <= 4; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), (x + z) % 5 == 0 ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState());
                    for (int k = 1; k < 6; k++) level.setBlockAndUpdate(new BlockPos(x, y + k, z), Blocks.AIR.defaultBlockState());
                }
                List<BlockState> row = List.of(
                    FoxWildsBlocks.FOX_STATUE.get().defaultBlockState(),
                    FoxWildsBlocks.STONE_BASIN.get().defaultBlockState(),
                    FoxWildsBlocks.BAMBOO_SPOUT.get().defaultBlockState(),
                    FoxWildsBlocks.STEPPING_STONE.get().defaultBlockState(),
                    FoxWildsBlocks.SHRINE_HOKORA.get().defaultBlockState(),
                    FoxWildsBlocks.HOT_SPRING_STONE.get().defaultBlockState(),
                    FoxWildsBlocks.STONE_LANTERN.get().defaultBlockState(),
                    FoxWildsBlocks.PAPER_LANTERN.get().defaultBlockState()
                );
                for (int i = 0; i < row.size(); i++) {
                    BlockState st = row.get(i);
                    if (st.hasProperty(HorizontalDirectionalBlock.FACING)) st = st.setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH);
                    level.setBlockAndUpdate(new BlockPos(i * 2, y + 1, 1), st);
                }
                BlockState tall = FoxWildsBlocks.STONE_LANTERN_TALL.get().defaultBlockState();
                level.setBlockAndUpdate(new BlockPos(16, y + 1, 1), tall.setValue(TallStoneLanternBlock.HALF, DoubleBlockHalf.LOWER));
                level.setBlockAndUpdate(new BlockPos(16, y + 2, 1), tall.setValue(TallStoneLanternBlock.HALF, DoubleBlockHalf.UPPER));
                // 暖帘与注连绳挂在一道木梁下
                for (int x = 0; x <= 10; x++) level.setBlockAndUpdate(new BlockPos(x, y + 4, -2), Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState());
                List<BlockState> hang = List.of(FoxWildsBlocks.NOREN.get().defaultBlockState(), FoxWildsBlocks.NOREN_RED.get().defaultBlockState(),
                    FoxWildsBlocks.NOREN_WHITE.get().defaultBlockState(), FoxWildsBlocks.NOREN_BROWN.get().defaultBlockState(), FoxWildsBlocks.SHIMENAWA.get().defaultBlockState());
                for (int i = 0; i < hang.size(); i++) {
                    level.setBlockAndUpdate(new BlockPos(i * 2 + 1, y + 3, -2), hang.get(i).setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
                }
                DimensionTravel.teleport(ctx.player(), level, new Vec3(8, y + 4.2, 8.5), 180f, 22f);
                ctx.player().setNoGravity(true);
            })
            .ticks(80)
            .screenshot("fox_props")
            .server("closer", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                DimensionTravel.teleport(ctx.player(), level, new Vec3(4, 302.6, 4.5), 180f, 25f);
            })
            .ticks(40)
            .screenshot("fox_props_close")
            .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }
}
