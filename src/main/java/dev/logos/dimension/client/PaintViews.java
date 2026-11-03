package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import dev.logos.dimension.elven.ElvenBlocks;
import dev.logos.dimension.paint.PaintSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 油漆系统客户端场景：在测试平台上摆本附属的原木/木板/半砖，表面刷不同颜色的漆面，
 * 漆面/未刷交替对照，人工截图核对染色后的木纹与半砖覆盖。
 */
public abstract class PaintViews implements UIScenario {
    protected static final int GROUND = 240;
    protected static final int[] COLORS = {
        0xFFFF5050, 0xFF50E070, 0xFF4F7FFF, 0xFFFFD84A,
        0xFFFF5FE0, 0xFF43DDE6, 0xFFFF9A3C,
    };
    protected BlockPos origin;
    protected Vec3 eye;
    protected final BlockPos[] painted = new BlockPos[COLORS.length];

    protected double eyeX() { return 0.5; }
    protected double eyeZ() { return 8.0; }
    protected float pitch() { return 12f; }
    protected String shot() { return "paint_surface_on_wood"; }

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("paint").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);
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
            .server("stage wood blocks with painted faces", ctx -> {
                ServerLevel level = ctx.server().overworld();
                origin = new BlockPos(0, GROUND, 0);
                for (int x = -11; x <= 9; x++) {
                    for (int z = -6; z <= 9; z++) {
                        level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                        for (int y = 1; y <= 6; y++) level.removeBlock(origin.offset(x, y, z), false);
                    }
                }
                Block[] woods = {
                    CloudseaBlocks.STEM.get(), ElvenBlocks.LOG.get(), ElvenBlocks.PLANKS.get(),
                    FoxWildsBlocks.LACQUER_LOG.get(), FoxWildsBlocks.LACQUER_PLANKS.get(),
                    FoxWildsBlocks.LACQUER_SLAB.get(), CloudseaBlocks.DARK_TIMBER.get(),
                };
                for (int i = 0; i < woods.length; i++) {
                    // 交替列：偶数 x 刷漆，奇数 x 用同样的木头但不刷漆，便于对照
                    BlockPos woodPos = origin.offset(-7 + i * 2, 1, 0);
                    BlockState woodState = woods[i].defaultBlockState();
                    level.setBlockAndUpdate(woodPos, woodState);
                    BlockPos surface = woodPos.south();
                    level.setBlockAndUpdate(surface, PaintSystem.SURFACE.get().defaultBlockState()
                        .setValue(DirectionalBlock.FACING, Direction.NORTH)
                        .setValue(dev.logos.dimension.paint.PaintSurfaceBlock.COVER,
                            dev.logos.dimension.paint.PaintSurfaceBlock.coverFor(woodState)));
                    if (level.getBlockEntity(surface) instanceof dev.logos.dimension.paint.PaintSurfaceBlockEntity be) {
                        be.setPaint(COLORS[i]);
                    }
                    painted[i] = surface;
                    level.setBlockAndUpdate(origin.offset(-6 + i * 2, 1, 0), woodState);
                }
                eye = new Vec3(origin.getX() + eyeX(), GROUND + 2.7, origin.getZ() + eyeZ());
                DimensionTravel.teleport(ctx.player(), level, eye, 180f, pitch());
            })
            .waitUntil("stage arrives", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && mc.level.hasChunk(origin.getX() >> 4, origin.getZ() >> 4)
                    && !mc.level.getBlockState(painted[0]).isAir();
            })
            .ticks(60)
            .checkServer("paint colors stored on all surfaces", ctx -> {
                ServerLevel level = ctx.server().overworld();
                for (int i = 0; i < COLORS.length; i++) {
                    if (!(level.getBlockEntity(painted[i]) instanceof dev.logos.dimension.paint.PaintSurfaceBlockEntity be)
                        || be.paint() != COLORS[i]) return false;
                }
                return true;
            })
            .checkServer("slab cover fitted to half", ctx -> {
                ServerLevel level = ctx.server().overworld();
                var cover = dev.logos.dimension.paint.PaintSurfaceBlock.COVER;
                return level.getBlockState(painted[5]).getValue(cover)
                    == dev.logos.dimension.paint.PaintSurfaceBlock.Cover.HALF_BOTTOM;
            })
            .check("painted surfaces present on client", ctx -> {
                var mc = Minecraft.getInstance();
                for (BlockPos p : painted) {
                    if (!mc.level.getBlockState(p).is(PaintSystem.SURFACE.get())) return false;
                }
                return true;
            })
            .screenshot(shot())
            .checkServer("surface drops when its support is removed", ctx -> {
                ServerLevel level = ctx.server().overworld();
                BlockPos surface = painted[0];
                BlockPos support = surface.relative(level.getBlockState(surface).getValue(DirectionalBlock.FACING));
                level.setBlockAndUpdate(support, Blocks.AIR.defaultBlockState());
                return level.getBlockState(surface).isAir();
            })
            .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    /** 调配页截图：打开全屏 256 色调色盘。 */
    @LDLRegisterClient(name = "paint_palette_ui", group = "paint", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Palette extends PaintViews {
        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE)
                .runCommand("difficulty peaceful")
                .step("show HUD", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    mc.options.fov().set(70);
                    mc.options.broadcastOptions();
                })
                .server("stand above ground", ctx -> {
                    var player = ctx.player();
                    player.teleportTo(0, GROUND + 3, 0);
                })
                .step("open palette", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setScreen(new PaintPaletteScreen(mc.player, InteractionHand.MAIN_HAND, ItemStack.EMPTY));
                })
                .ticks(10)
                .check("palette screen open", ctx -> Minecraft.getInstance().screen instanceof PaintPaletteScreen)
                .screenshot("paint_palette_ui")
                .teardown("close palette", ctx -> Minecraft.getInstance().setScreen(null));
        }
    }

    @LDLRegisterClient(name = "paint_views", group = "paint", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Surfaces extends PaintViews {
    }

    /** 俯视场景：原木顶面与下砖顶面刷漆，核对贴顶覆盖与半砖补半格。 */
    @LDLRegisterClient(name = "paint_tops", group = "paint", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Tops extends PaintViews {
        private BlockPos logTop, slabTop;

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
                .server("stage painted tops", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    origin = new BlockPos(0, GROUND, 0);
                    for (int x = -6; x <= 6; x++) {
                        for (int z = -6; z <= 10; z++) {
                            level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                            for (int y = 1; y <= 6; y++) level.removeBlock(origin.offset(x, y, z), false);
                        }
                    }
                    BlockPos log = origin.offset(-2, 1, 0);
                    level.setBlockAndUpdate(log, ElvenBlocks.LOG.get().defaultBlockState());
                    logTop = log.above();
                    level.setBlockAndUpdate(logTop, PaintSystem.SURFACE.get().defaultBlockState()
                        .setValue(DirectionalBlock.FACING, Direction.DOWN)
                        .setValue(dev.logos.dimension.paint.PaintSurfaceBlock.COVER,
                            dev.logos.dimension.paint.PaintSurfaceBlock.Cover.FULL));
                    if (level.getBlockEntity(logTop) instanceof dev.logos.dimension.paint.PaintSurfaceBlockEntity be) {
                        be.setPaint(0xFF58D060);
                    }
                    BlockPos slab = origin.offset(2, 1, 0);
                    level.setBlockAndUpdate(slab, Blocks.OAK_SLAB.defaultBlockState());
                    slabTop = slab.above();
                    level.setBlockAndUpdate(slabTop, PaintSystem.SURFACE.get().defaultBlockState()
                        .setValue(DirectionalBlock.FACING, Direction.DOWN)
                        .setValue(dev.logos.dimension.paint.PaintSurfaceBlock.COVER,
                            dev.logos.dimension.paint.PaintSurfaceBlock.Cover.HALF_BOTTOM));
                    if (level.getBlockEntity(slabTop) instanceof dev.logos.dimension.paint.PaintSurfaceBlockEntity be) {
                        be.setPaint(0xFF4F80FF);
                    }
                    // 对照：同样的原木、下砖不刷
                    level.setBlockAndUpdate(origin.offset(-4, 1, 0), ElvenBlocks.LOG.get().defaultBlockState());
                    level.setBlockAndUpdate(origin.offset(4, 1, 0), Blocks.OAK_SLAB.defaultBlockState());
                    DimensionTravel.teleport(ctx.player(), level,
                        new Vec3(origin.getX() + 0.5, GROUND + 4.2, origin.getZ() + 9.0), 180f, 26f);
                })
                .waitUntil("stage arrives", ctx -> {
                    var mc = Minecraft.getInstance();
                    return mc.level != null && mc.level.hasChunk(origin.getX() >> 4, origin.getZ() >> 4)
                        && !mc.level.getBlockState(logTop).isAir();
                })
                .ticks(60)
                .checkServer("top covers correct", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    var cover = dev.logos.dimension.paint.PaintSurfaceBlock.COVER;
                    return level.getBlockState(logTop).getValue(cover)
                            == dev.logos.dimension.paint.PaintSurfaceBlock.Cover.FULL
                        && level.getBlockState(slabTop).getValue(cover)
                            == dev.logos.dimension.paint.PaintSurfaceBlock.Cover.HALF_BOTTOM;
                })
                .check("top surfaces present on client", ctx -> {
                    var mc = Minecraft.getInstance();
                    return mc.level.getBlockState(logTop).is(PaintSystem.SURFACE.get())
                        && mc.level.getBlockState(slabTop).is(PaintSystem.SURFACE.get());
                })
                .screenshot("paint_tops")
                .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }
}
