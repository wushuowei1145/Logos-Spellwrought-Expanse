package dev.logos.dimension.client.music;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 第二批方块乐器的客户端场景：钟琴、钢片琴、管钟、大键琴一排，正面、侧面与物品栏截图；
 * 再打开大键琴的演奏页按三个键，核对大键琴按得轻重都一样响（不感力度）、声音在响。
 */
public abstract class MalletViews implements UIScenario {
    protected static final int GROUND = 240;
    protected static final BlockPos ORIGIN = new BlockPos(150, GROUND, 40);
    protected static final BlockPos HARPSICHORD = ORIGIN.offset(-4, 1, 0);
    protected static final BlockPos CELESTA = ORIGIN.offset(-1, 1, 0);
    protected static final BlockPos GLOCKENSPIEL = ORIGIN.offset(1, 1, 0);
    protected static final BlockPos BELLS = ORIGIN.offset(3, 1, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("instrument").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);
    }

    private static void place(ServerLevel level, BlockPos pos, Block block) {
        BlockState state = block.defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(pos, state);
        state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
    }

    protected ScenarioBuilder stage(ScenarioBuilder s, double eyeX, double eyeY, double eyeZ, float yaw, float pitch, boolean hideGui) {
        return s.setGameMode(GameType.CREATIVE)
            .runCommand("difficulty peaceful")
            .runCommand("time set noon")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = hideGui;
                mc.options.renderDistance().set(8);
                mc.options.fov().set(70);
                mc.options.broadcastOptions();
            })
            .server("stage the instruments", ctx -> {
                ServerLevel level = ctx.server().overworld();
                for (int x = -9; x <= 9; x++) {
                    for (int z = -6; z <= 9; z++) {
                        BlockPos p = ORIGIN.offset(x, 0, z);
                        for (int y = 1; y <= 5; y++) {
                            level.setBlock(p.above(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                        }
                        level.setBlock(p, ((x + z & 1) == 0 ? Blocks.POLISHED_DIORITE : Blocks.SMOOTH_QUARTZ).defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
                level.getEntitiesOfClass(ItemEntity.class, new AABB(ORIGIN).inflate(16)).forEach(ItemEntity::discard);
                place(level, HARPSICHORD, Instruments.HARPSICHORD.get());
                place(level, CELESTA, Instruments.CELESTA.get());
                place(level, GLOCKENSPIEL, Instruments.GLOCKENSPIEL.get());
                place(level, BELLS, Instruments.TUBULAR_BELLS.get());
                DimensionTravel.teleport(ctx.player(), level, new Vec3(ORIGIN.getX() + eyeX, GROUND + eyeY, ORIGIN.getZ() + eyeZ), yaw, pitch);
            })
            .waitUntil("instruments arrive", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && mc.level.getBlockState(BELLS.above()).is(Instruments.TUBULAR_BELLS.get())
                    && mc.level.getBlockEntity(HARPSICHORD) instanceof InstrumentBlockEntity;
            });
    }

    @LDLRegisterClient(name = "mallet_instrument_views", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Looks extends MalletViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 0.5, 2.6, 5.2, 180f, 18f, true)
                .ticks(60)
                .screenshot("mallet_instrument_views")
                .server("side view", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() + 5.5, GROUND + 3.2, ORIGIN.getZ() + 4.5), 140f, 26f))
                .ticks(40)
                .screenshot("mallet_instrument_views_side")
                .server("close to the glockenspiel and celesta", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() + 0.5, GROUND + 2.6, ORIGIN.getZ() + 2.4), 180f, 45f))
                .ticks(40)
                .screenshot("mallet_instrument_views_close")
                .step("hotbar", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("give the instruments", ctx -> {
                    var inv = ctx.player().getInventory();
                    inv.setItem(0, new ItemStack(Instruments.HARPSICHORD_ITEM.get()));
                    inv.setItem(1, new ItemStack(Instruments.CELESTA_ITEM.get()));
                    inv.setItem(2, new ItemStack(Instruments.GLOCKENSPIEL_ITEM.get()));
                    inv.setItem(3, new ItemStack(Instruments.TUBULAR_BELLS_ITEM.get()));
                    DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                        new Vec3(ORIGIN.getX() + 0.5, GROUND + 2.6, ORIGIN.getZ() + 5.2), 180f, 18f);
                })
                .ticks(30)
                .screenshot("mallet_instrument_views_items")
                .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    @LDLRegisterClient(name = "mallet_instrument_play", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Play extends MalletViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, -3.5, 1.0, 2.5, 180f, 10f, false)
                .openScreen("open the harpsichord", ctx -> new InstrumentScreen(HARPSICHORD))
                .waitUntilServer("player claims the harpsichord", ctx -> ctx.server().overworld().getBlockEntity(HARPSICHORD) instanceof InstrumentBlockEntity be
                    && ctx.player().getUUID().equals(be.performer()))
                .check("the screen knows it is a harpsichord", ctx -> MusicClient.kindAt(HARPSICHORD) == InstrumentKind.HARPSICHORD)
                .keyDown('Z').keyDown('C').keyDown('B')
                .ticks(3)
                .check("three strings sounding", ctx -> VoiceBank.sounding(MusicClient.liveKey(HARPSICHORD)) >= 3)
                .screenshot("mallet_instrument_play")
                .keyUp('Z').keyUp('C').keyUp('B')
                .ticks(10)
                .check("dampers fall on release", ctx -> VoiceBank.sounding(MusicClient.liveKey(HARPSICHORD)) == 0)
                .teardown("close", ctx -> Minecraft.getInstance().setScreen(null));
        }
    }
}
