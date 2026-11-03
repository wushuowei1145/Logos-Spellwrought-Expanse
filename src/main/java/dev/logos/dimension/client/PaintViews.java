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
 * 油漆系统客户端场景：在测试平台上摆本附属的原木/木板/半砖/楼梯，
 * 表面贴不同颜色的漆面，人工截图核对半透明叠色是否透出各自木纹。
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
    protected BlockPos stairFront, stairSide, stairUpside;

    protected double eyeX() { return 2.5; }
    protected double eyeZ() { return 8.0; }
    protected float pitch() { return 12f; }
    protected String shot() { return "paint_surface_on_wood"; }

    /** 摆一座精灵木楼梯（浅色木头，染色看得清）并刷一面漆，返回漆面位置。 */
    private static BlockPos paintStair(ServerLevel level, BlockPos pos, Direction facing, boolean upsideDown,
                                       Direction paintedFace, int color) {
        BlockState stair = net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(net.minecraft.world.level.block.StairBlock.FACING, facing)
            .setValue(net.minecraft.world.level.block.StairBlock.HALF,
                upsideDown ? net.minecraft.world.level.block.state.properties.Half.TOP
                           : net.minecraft.world.level.block.state.properties.Half.BOTTOM);
        level.setBlockAndUpdate(pos, stair);
        BlockPos surface = pos.relative(paintedFace);
        level.setBlockAndUpdate(surface, PaintSystem.SURFACE.get().defaultBlockState()
            .setValue(DirectionalBlock.FACING, paintedFace.getOpposite())
            .setValue(dev.logos.dimension.paint.PaintSurfaceBlock.COVER,
                dev.logos.dimension.paint.PaintSurfaceBlock.coverFor(stair, paintedFace)));
        if (level.getBlockEntity(surface) instanceof dev.logos.dimension.paint.PaintSurfaceBlockEntity be) {
            be.setPaint(color);
        }
        return surface;
    }

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
                for (int x = -11; x <= 15; x++) {
                    for (int z = -6; z <= 9; z++) {
                        level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                        for (int y = 1; y <= 6; y++) level.removeBlock(origin.offset(x, y, z), false);
                    }
                }
                Block[] woods = {
                    CloudseaBlocks.STEM.get(), ElvenBlocks.LOG.get(), ElvenBlocks.PLANKS.get(),
                    FoxWildsBlocks.LACQUER_LOG.get(), FoxWildsBlocks.LACQUER_PLANKS.get(),
                    FoxWildsBlocks.LACQUER_SLAB.get(), FoxWildsBlocks.LACQUER_STAIRS.get(),
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
                            dev.logos.dimension.paint.PaintSurfaceBlock.coverFor(woodState, Direction.SOUTH)));
                    if (level.getBlockEntity(surface) instanceof dev.logos.dimension.paint.PaintSurfaceBlockEntity be) {
                        be.setPaint(COLORS[i]);
                    }
                    painted[i] = surface;
                    level.setBlockAndUpdate(origin.offset(-6 + i * 2, 1, 0), woodState);
                }
                // 楼梯：正面（低侧 L 形）、侧面（镜像 L）、倒置楼梯正面，验证贴合；
                // 都刷朝镜头的南面，截图里能直接看到覆盖轮廓
                // 楼梯之间留一格空位，避免相邻楼梯互相改成形（shape=inner_*）导致轮廓变化
                stairFront = paintStair(level, origin.offset(6, 1, 0), Direction.SOUTH, false, Direction.SOUTH, 0xFF44E044);
                stairSide = paintStair(level, origin.offset(8, 1, 0), Direction.EAST, false, Direction.SOUTH, 0xFF4466FF);
                stairUpside = paintStair(level, origin.offset(10, 1, 0), Direction.SOUTH, true, Direction.SOUTH, 0xFFFF44CC);
                // 楼梯对照：同款未刷漆，同样留空
                level.setBlockAndUpdate(origin.offset(4, 1, 0), net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.SOUTH));
                level.setBlockAndUpdate(origin.offset(12, 1, 0), net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.SOUTH));
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
            .checkServer("stair and slab covers fitted to shape", ctx -> {
                ServerLevel level = ctx.server().overworld();
                var cover = dev.logos.dimension.paint.PaintSurfaceBlock.COVER;
                var stairShape = net.minecraft.world.level.block.StairBlock.SHAPE;
                return level.getBlockState(stairFront).getValue(cover) == dev.logos.dimension.paint.PaintSurfaceBlock.Cover.FRONT_STEP
                    && level.getBlockState(stairSide).getValue(cover) == dev.logos.dimension.paint.PaintSurfaceBlock.Cover.SIDE_LEFT
                    && level.getBlockState(stairUpside).getValue(cover) == dev.logos.dimension.paint.PaintSurfaceBlock.Cover.FRONT_STEP_UD
                    && level.getBlockState(painted[5]).getValue(cover) == dev.logos.dimension.paint.PaintSurfaceBlock.Cover.HALF_BOTTOM
                    && level.getBlockState(stairSide.relative(Direction.NORTH)).getValue(stairShape)
                        == net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT;
            })
            .check("painted surfaces present on client", ctx -> {
                var mc = Minecraft.getInstance();
                for (BlockPos p : painted) {
                    if (!mc.level.getBlockState(p).is(PaintSystem.SURFACE.get())) return false;
                }
                return true;
            })
            .screenshot(shot())
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

    /** 正对三段楼梯的近景，用于核对 L 形覆盖是否贴合。 */
    @LDLRegisterClient(name = "paint_stairs", group = "paint", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Stairs extends PaintViews {
        @Override protected double eyeX() { return 8.5; }
        @Override protected double eyeZ() { return 5.0; }
        @Override protected float pitch() { return 6f; }
        @Override protected String shot() { return "paint_stairs_closeup"; }
    }
}
