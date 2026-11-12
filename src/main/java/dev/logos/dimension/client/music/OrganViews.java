package dev.logos.dimension.client.music;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicPayloads;
import dev.logos.dimension.music.organ.OrganPipeBlock;
import dev.logos.dimension.music.organ.OrganStop;
import dev.logos.dimension.music.organ.OrganTrumpetBlock;
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
 * 管风琴的客户端场景：石砌大厅（玻璃屋顶，透光也反射声音）里靠北墙一架管风琴——演奏台、一排风箱、
 * 高低错落的锡管墙、两侧木管、正面两支号角管。核对客户端识别到的音管、室内的混响、音栓页、
 * 按住和弦后声音引擎真的在放循环段；最后掀掉屋顶，再量一次应当是露天、没有混响。
 */
public abstract class OrganViews implements UIScenario {
    protected static final int GROUND = 240;
    protected static final BlockPos ORIGIN = new BlockPos(-160, GROUND, 0);
    /** 演奏台主块：键盘朝南，向西展开。 */
    protected static final BlockPos CONSOLE = ORIGIN.offset(1, 1, -6);
    private static final int HALF = 10, HEIGHT = 13;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("instrument", "organ").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);
    }

    private static void column(ServerLevel level, BlockPos bottom, Block pipe, int height) {
        for (int i = 0; i < height; i++) {
            BlockPos p = bottom.above(i);
            BlockState s = pipe.defaultBlockState();
            if (pipe instanceof OrganPipeBlock) s = s.setValue(OrganPipeBlock.FACING, Direction.SOUTH);
            if (pipe instanceof OrganTrumpetBlock) s = s.setValue(OrganTrumpetBlock.FACING, Direction.SOUTH);
            level.setBlock(p, s, Block.UPDATE_CLIENTS);
            if (pipe instanceof OrganPipeBlock b) level.setBlock(p, b.withShape(level.getBlockState(p), level, p), Block.UPDATE_CLIENTS);
        }
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
            .server("build the hall and the organ", ctx -> {
                ServerLevel level = ctx.server().overworld();
                for (int x = -HALF - 2; x <= HALF + 2; x++) {
                    for (int z = -HALF - 2; z <= HALF + 2; z++) {
                        for (int y = 0; y <= HEIGHT + 2; y++) {
                            BlockPos p = ORIGIN.offset(x, y, z);
                            BlockState st;
                            boolean wall = Math.abs(x) == HALF || Math.abs(z) == HALF;
                            boolean inside = Math.abs(x) <= HALF && Math.abs(z) <= HALF;
                            if (y == 0) st = inside ? ((x + z & 1) == 0 ? Blocks.POLISHED_ANDESITE : Blocks.CALCITE).defaultBlockState()
                                : Blocks.STONE.defaultBlockState();
                            else if (!inside || y > HEIGHT) st = Blocks.AIR.defaultBlockState();
                            else if (y == HEIGHT) st = Blocks.GLASS.defaultBlockState();
                            else if (wall) st = Blocks.STONE_BRICKS.defaultBlockState();
                            else st = Blocks.AIR.defaultBlockState();
                            level.setBlock(p, st, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                        }
                    }
                }
                level.getEntitiesOfClass(ItemEntity.class, new AABB(ORIGIN).inflate(20)).forEach(ItemEntity::discard);
                BlockState console = Instruments.ORGAN_CONSOLE.get().placed(Direction.SOUTH);
                level.setBlockAndUpdate(CONSOLE, console);
                console.getBlock().setPlacedBy(level, CONSOLE, console, null, ItemStack.EMPTY);
                // 风箱一排贴着演奏台背后（北面），东西各伸到墙边
                BlockPos row = CONSOLE.north();
                int[] heights = {3, 4, 5, 6, 7, 8, 9, 8, 7, 6, 5, 4, 3};
                for (int i = 0; i < heights.length; i++) {
                    BlockPos chest = row.offset(i - 7, 0, 0);
                    level.setBlockAndUpdate(chest, Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
                    boolean wood = i == 0 || i == heights.length - 1;
                    column(level, chest.above(), wood ? Instruments.ORGAN_WOOD_PIPE.get() : Instruments.ORGAN_TIN_PIPE.get(), heights[i]);
                }
                // 第二排更矮的木管
                for (int i = 0; i < 13; i += 2) {
                    BlockPos chest = row.offset(i - 7, 0, -1);
                    level.setBlockAndUpdate(chest, Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
                    column(level, chest.above(), Instruments.ORGAN_WOOD_PIPE.get(), 2 + i % 3);
                }
                // 正面两支号角管
                for (int dx : new int[]{-5, 3}) {
                    BlockPos chest = CONSOLE.offset(dx, 0, 0);
                    level.setBlockAndUpdate(chest, Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
                    column(level, chest.above(), Instruments.ORGAN_TRUMPET_PIPE.get(), 1);
                }
                DimensionTravel.teleport(ctx.player(), level, new Vec3(ORIGIN.getX() + eyeX, GROUND + eyeY, ORIGIN.getZ() + eyeZ), yaw, pitch);
            })
            .waitUntil("organ arrives", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && mc.level.getBlockEntity(CONSOLE) instanceof InstrumentBlockEntity be
                    && be.organ() != null && be.organ().pipes() > 60;
            });
    }

    private static InstrumentBlockEntity clientConsole() {
        return (InstrumentBlockEntity) Minecraft.getInstance().level.getBlockEntity(CONSOLE);
    }

    @LDLRegisterClient(name = "organ_views", group = "organ", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Looks extends OrganViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 0.5, 2.2, 7.0, 180f, -8f, true)
                .ticks(60)
                .check("client sees all the pipes", ctx -> {
                    var o = clientConsole().organ();
                    return o != null && o.tin() > 50 && o.wood() > 10 && o.reed() == 2 && o.availableStops() == OrganStop.ALL;
                })
                .check("the hall has reverb", ctx -> OrganRoom.estimate(CONSOLE, clientConsole()).seconds() > 1.0)
                .screenshot("organ_views")
                .server("close to the console", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(CONSOLE.getX() - 0.0, GROUND + 2.4, CONSOLE.getZ() + 3.2), 180f, 28f))
                .ticks(40)
                .screenshot("organ_views_console")
                .server("side view", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() + 8.5, GROUND + 3.0, ORIGIN.getZ() - 1.0), 125f, 6f))
                .ticks(40)
                .screenshot("organ_views_side")
                .step("hotbar", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("give the parts", ctx -> {
                    var inv = ctx.player().getInventory();
                    inv.setItem(0, new ItemStack(Instruments.ORGAN_CONSOLE_ITEM.get()));
                    inv.setItem(1, new ItemStack(Instruments.ORGAN_WIND_CHEST_ITEM.get()));
                    inv.setItem(2, new ItemStack(Instruments.ORGAN_TIN_PIPE_ITEM.get()));
                    inv.setItem(3, new ItemStack(Instruments.ORGAN_WOOD_PIPE_ITEM.get()));
                    inv.setItem(4, new ItemStack(Instruments.ORGAN_TRUMPET_PIPE_ITEM.get()));
                    inv.setSelectedSlot(2);
                })
                .ticks(20)
                .screenshot("organ_views_items")
                .server("take the roof off", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++) {
                        level.setBlock(ORIGIN.offset(x, HEIGHT, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++) for (int y = 1; y < HEIGHT; y++) {
                        BlockPos p = ORIGIN.offset(x, y, z);
                        if (level.getBlockState(p).is(Blocks.STONE_BRICKS)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                })
                .ticks(20)
                .check("open air has no reverb", ctx -> !OrganRoom.measure(Minecraft.getInstance().level, clientConsole().voiceCenter()).on())
                .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    /**
     * #243 之后的回归：换资源包、F3+T、换音频设备都会重启声音引擎（OpenAL 上下文换新）。以前管风琴自己留着旧上下文的缓冲，
     * 重启前按过的键之后都不出声。这里按一个键、重启声音引擎、再按同一个键，核对它还在响。
     */
    @LDLRegisterClient(name = "organ_reload_ui", group = "organ", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Reload extends OrganViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 0.0, 1.0, -3.0, 180f, 10f, false)
                .openScreen("open the organ", ctx -> new InstrumentScreen(CONSOLE))
                .waitUntil("the mix is ready", ctx -> OrganMixer.ready(clientConsole().effectiveStops()) != null)
                .ticks(5)
                .keyDown('Z')
                .ticks(20)
                .check("the key sounds before the reload", ctx -> VoiceBank.sounding(MusicClient.liveKey(CONSOLE)) >= 1)
                .keyUp('Z')
                .ticks(60)
                .step("restart the sound engine", ctx -> {
                    Minecraft.getInstance().setScreen(null);
                    Minecraft.getInstance().getSoundManager().reload();
                })
                .ticks(40)
                .openScreen("open the organ again", ctx -> new InstrumentScreen(CONSOLE))
                .ticks(5)
                .keyDown('Z')
                .ticks(20)
                .check("the same key still sounds after the sound engine restarted", ctx -> VoiceBank.sounding(MusicClient.liveKey(CONSOLE)) >= 1)
                .keyUp('Z')
                .teardown("close", ctx -> Minecraft.getInstance().setScreen(null));
        }
    }

    @LDLRegisterClient(name = "organ_play_ui", group = "organ", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Play extends OrganViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 0.0, 1.0, -3.0, 180f, 10f, false)
                .openScreen("open the organ", ctx -> new InstrumentScreen(CONSOLE))
                .ticks(10)
                .step("stops page", ctx -> {
                    if (Minecraft.getInstance().screen instanceof InstrumentScreen screen) screen.showStopsPage();
                    MusicClient.control(CONSOLE, MusicPayloads.Control.STOPS, OrganStop.Preset.PLENUM.mask());
                })
                .waitUntil("full organ arrives on the client", ctx -> clientConsole().stops() == OrganStop.Preset.PLENUM.mask())
                .waitUntil("the full-organ mix is ready", ctx -> OrganMixer.ready(clientConsole().effectiveStops()) != null)
                .ticks(10)
                .screenshot("organ_stops_ui")
                .step("back to the keys", ctx -> {
                    if (Minecraft.getInstance().screen instanceof InstrumentScreen screen) {
                        Minecraft.getInstance().setScreen(null);
                        Minecraft.getInstance().setScreen(new InstrumentScreen(CONSOLE));
                    }
                })
                .ticks(10)
                .keyDown('Z').keyDown('C').keyDown('B').keyDown('Q')
                .ticks(30)
                .check("the held chord is still sounding (looping)", ctx -> VoiceBank.sounding(MusicClient.liveKey(CONSOLE)) >= 4)
                .screenshot("organ_play_ui")
                .keyUp('Z').keyUp('C').keyUp('B').keyUp('Q')
                .ticks(4)
                .check("release leaves reverb tails in the hall", ctx -> VoiceBank.sounding(MusicClient.liveKey(CONSOLE)) >= 1)
                .teardown("close", ctx -> Minecraft.getInstance().setScreen(null));
        }
    }
}
