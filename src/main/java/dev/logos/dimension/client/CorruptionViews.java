package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.corruption.CorruptionNetworks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.phys.Vec3;

/**
 * 腐败的实际客户端检查，分组 {@code corruption}：草地上一片以核心为中心的腐败污秽（记着原来是草方块）、外圈一圈脉络，
 * 玩家拿铁镐真的挖掉核心，看镜头升起来看着核心、网络从核心开始一圈圈消散（污秽剥落露出草方块，脉络消失）、镜头回来。
 */
public abstract class CorruptionViews implements UIScenario {
    private static final int GROUND = 100;
    private static final Vec3 FEET = new Vec3(.5, GROUND + 1, .5);
    /** 站在 {@link #FEET} 朝南俯视 20 度时准星落在核心上（和运镜测试方块同一个位置）。 */
    private static final BlockPos CORE = new BlockPos(0, GROUND + 1, 3);
    private static final int FILTH = 8, VEINS = 10;

    @Override public void configure(ScenarioOptions o) {
        o.tags("corruption").defaultTimeoutMs(60000).scenarioTimeoutMs(180000);
    }

    /** 草地；核心底下往外 {@link #FILTH} 格是腐败污秽（玩家脚下留空），再往外一圈脉络。按离核心的远近放，每块都挨着已经在网络里的。 */
    private static void stage(ServerLevel level) {
        for (int x = -16; x <= 16; x++) for (int z = -14; z <= 18; z++) {
            // 玩家脚下那条用石砖：不在可转化的方块里，蔓延吃不到，消散后玩家不会掉下去
            boolean path = Math.abs(x) <= 1 && z <= 1;
            level.setBlockAndUpdate(new BlockPos(x, GROUND, z), (path ? Blocks.STONE_BRICKS : Blocks.GRASS_BLOCK).defaultBlockState());
            for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(new BlockPos(x, GROUND - y, z), Blocks.DIRT.defaultBlockState());
            for (int y = 1; y <= 18; y++) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, z), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(CORE, Corruption.CORE.get().defaultBlockState());
        // 污秽记着原来是草方块，就像是蔓延变来的
        var data = CorruptionNetworks.get(level);
        for (BlockPos p : floor()) {
            level.setBlockAndUpdate(p, Corruption.BLOCK.get().defaultBlockState());
            data.remember(p, Blocks.GRASS_BLOCK.defaultBlockState());
        }
        // 脉络：最内一圈趴在最外圈的污秽上（挨着它才归这个网络），再往外两圈趴在草上，由里往外放
        var vein = Corruption.VEIN.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        List<BlockPos> veins = new ArrayList<>();
        for (int x = -VEINS; x <= VEINS; x++) for (int z = -VEINS; z <= VEINS; z++) {
            BlockPos p = CORE.offset(x, 0, z);
            int d = Math.abs(x) + Math.abs(z);
            if (d >= FILTH && d <= VEINS && !(Math.abs(p.getX()) <= 1 && p.getZ() <= 1)) veins.add(p);
        }
        veins.sort(Comparator.comparingInt(p -> Math.abs(p.getX() - CORE.getX()) + Math.abs(p.getZ() - CORE.getZ())));
        for (BlockPos p : veins) level.setBlockAndUpdate(p, vein);
    }

    /** 核心底下往外 {@link #FILTH} 格（玩家脚下那条除外），由近到远。 */
    private static List<BlockPos> floor() {
        BlockPos below = CORE.below();
        List<BlockPos> floor = new ArrayList<>();
        for (int x = -FILTH; x <= FILTH; x++) for (int z = -FILTH; z <= FILTH; z++) {
            BlockPos p = below.offset(x, 0, z);
            if (Math.abs(x) + Math.abs(z) > FILTH || Math.abs(p.getX()) <= 1 && p.getZ() <= 1) continue;
            floor.add(p);
        }
        floor.sort(Comparator.comparingInt(p -> p.distManhattan(below)));
        return floor;
    }

    private static boolean grass(int distance) {
        return Minecraft.getInstance().level.getBlockState(CORE.below().offset(0, 0, distance)).is(Blocks.GRASS_BLOCK);
    }

    private static boolean filth(int distance) {
        var level = Minecraft.getInstance().level;
        return level.getBlockState(CORE.below().offset(0, 0, distance)).is(Corruption.BLOCK.get());
    }

    @LDLRegisterClient(name = "corruption_core_break", group = "corruption", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class CoreBreak extends CorruptionViews {
        private Vec3 eye = Vec3.ZERO;
        private int size;

        @Override public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.SURVIVAL).runCommand("time set 13000").runCommand("weather clear").runCommand("difficulty peaceful")
                    .step("设置视口", ctx -> {
                        var mc = Minecraft.getInstance();
                        mc.options.hideGui = false;
                        mc.player.getInventory().setSelectedSlot(0);
                        mc.options.setCameraType(CameraType.FIRST_PERSON);
                        mc.options.fov().set(70);
                        var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                        mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    })
                    .server("搭场地", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        stage(level);
                        size = CorruptionNetworks.get(level).ofCore(CORE).size();
                        DimensionTravel.teleport(ctx.player(), level, FEET, 0, 20);
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
                    })
                    .checkServer("网络记下了全部方块", ctx -> size > 100)
                    .waitUntil("到位", ctx -> {
                        var mc = Minecraft.getInstance();
                        return mc.player.position().distanceTo(FEET) < .01 && mc.player.onGround() && mc.level.getBlockState(CORE).is(Corruption.CORE.get());
                    })
                    .ticks(10)
                    .step("记下起点", ctx -> eye = Minecraft.getInstance().player.getEyePosition())
                    .screenshot("corruption_before")
                    .step("开始挖", ctx -> Minecraft.getInstance().gameMode.startDestroyBlock(CORE, Direction.NORTH))
                    .waitUntil("挖掉后开始运镜", ctx -> {
                        var mc = Minecraft.getInstance();
                        if (!CameraDirector.active()) mc.gameMode.continueDestroyBlock(CORE, Direction.NORTH);
                        return CameraDirector.active();
                    })
                    .checkServer("核心没掉东西", ctx -> ctx.server().overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(CORE).inflate(4)).isEmpty())
                    .waitUntil("运镜第 12 刻", ctx -> CameraDirector.age() >= 12).screenshot("corruption_rise")
                    .waitUntil("运镜第 28 刻", ctx -> CameraDirector.age() >= 28).screenshot("corruption_wave_28")
                    .step("近的先没、远的还在", ctx -> {
                        ctx.check("机位在玩家头顶十格", Minecraft.getInstance().gameRenderer.getMainCamera().position().distanceTo(eye.add(0, 10, 0)) < .05);
                        ctx.check("核心旁边的污秽已经变回草方块", grass(2));
                        ctx.check("最外圈的污秽还在", filth(FILTH));
                    })
                    .waitUntil("运镜第 40 刻", ctx -> CameraDirector.age() >= 40).screenshot("corruption_wave_40")
                    .waitUntil("运镜第 52 刻", ctx -> CameraDirector.age() >= 52).screenshot("corruption_wave_52")
                    .waitUntil("运镜第 70 刻", ctx -> CameraDirector.age() >= 70).screenshot("corruption_wave_70")
                    .step("全部消散", ctx -> {
                        var level = Minecraft.getInstance().level;
                        int left = 0;
                        for (BlockPos p : BlockPos.betweenClosed(CORE.offset(-VEINS, -1, -VEINS), CORE.offset(VEINS, 0, VEINS))) {
                            if (Corruption.isNetworkBlock(level.getBlockState(p))) left++;
                        }
                        ctx.check("污秽和脉络都没了，还剩 " + left, left == 0);
                        long back = floor().stream().filter(p -> level.getBlockState(p).is(Blocks.GRASS_BLOCK)).count();
                        ctx.check("污秽全部变回草方块：" + back + "/" + floor().size(), back == floor().size());
                    })
                    .waitUntil("运镜结束", ctx -> !CameraDirector.active()).ticks(5)
                    .screenshot("corruption_after")
                    .step("镜头回到玩家", ctx -> {
                        var mc = Minecraft.getInstance();
                        ctx.check("玩家还活着、站在原地", mc.player.isAlive() && mc.player.position().distanceTo(FEET) < .01);
                        ctx.check("回到眼睛", mc.gameRenderer.getMainCamera().position().distanceTo(mc.player.getEyePosition()) < .01);
                    })
                    .teardown("恢复", ctx -> {
                        var mc = Minecraft.getInstance();
                        mc.options.hideGui = false;
                        mc.options.setCameraType(CameraType.FIRST_PERSON);
                    });
        }
    }

    /**
     * 大网络拆核心时客户端的开销：29×29 的污秽地面（记着原来是草方块），上面全铺着脉络，一共约 1680 块。
     * 玩家在边上看着，量消散期间每帧的间隔、同时有多少块影子、一帧交出去多少格。
     */
    @LDLRegisterClient(name = "corruption_large_fade", group = "corruption", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class LargeFade extends CorruptionViews {
        private static final int HALF = 14;
        private static final BlockPos HEART = new BlockPos(0, GROUND + 1, 0);
        private final List<Long> frames = new ArrayList<>();
        private int size, mostGhosts, mostCells;
        private final Object listener = new Object() {
            @net.neoforged.bus.api.SubscribeEvent
            public void frame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
                frames.add(System.nanoTime());
                mostGhosts = Math.max(mostGhosts, dev.logos.dimension.client.dissolve.Dissolves.ghosts());
                mostCells = Math.max(mostCells, dev.logos.dimension.client.dissolve.Dissolves.cellsLastFrame());
            }
        };

        private static void stage(ServerLevel level) {
            for (int x = -HALF - 4; x <= HALF + 4; x++) for (int z = -HALF - 24; z <= HALF + 4; z++) {
                level.setBlockAndUpdate(new BlockPos(x, GROUND, z), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(new BlockPos(x, GROUND - y, z), Blocks.DIRT.defaultBlockState());
                for (int y = 1; y <= 18; y++) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, z), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(HEART, Corruption.CORE.get().defaultBlockState());
            var data = CorruptionNetworks.get(level);
            BlockPos below = HEART.below();
            List<BlockPos> floor = new ArrayList<>();
            for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++) floor.add(below.offset(x, 0, z));
            floor.sort(Comparator.comparingInt(p -> p.distManhattan(below)));
            for (BlockPos p : floor) {
                level.setBlockAndUpdate(p, Corruption.BLOCK.get().defaultBlockState());
                data.remember(p, Blocks.GRASS_BLOCK.defaultBlockState());
            }
            var vein = Corruption.VEIN.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
            for (BlockPos p : floor) if (!p.equals(below)) level.setBlockAndUpdate(p.above(), vein);
        }

        @Override public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set 6000").runCommand("weather clear").runCommand("difficulty peaceful")
                    .step("设置视口", ctx -> {
                        var mc = Minecraft.getInstance();
                        mc.options.hideGui = false;
                        mc.options.setCameraType(CameraType.FIRST_PERSON);
                        mc.options.fov().set(70);
                        var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                        mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    })
                    .server("搭场地", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        stage(level);
                        size = CorruptionNetworks.get(level).ofCore(HEART).size();
                        DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, GROUND + 4, -HALF - 10.5), 0, 25);
                    })
                    .checkServer("网络有一千多块", ctx -> size > 1500)
                    .waitUntil("到位", ctx -> Minecraft.getInstance().level.getBlockState(HEART).is(Corruption.CORE.get()))
                    .ticks(20)
                    .screenshot("corruption_large_before")
                    .step("开始量", ctx -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(listener))
                    .server("拆核心", ctx -> ctx.server().overworld().destroyBlock(HEART, false))
                    .ticks(40).screenshot("corruption_large_wave_40")
                    .ticks(30).screenshot("corruption_large_wave_70")
                    .waitUntil("消散完", ctx -> dev.logos.dimension.client.dissolve.Dissolves.ghosts() == 0)
                    .ticks(5)
                    .step("看开销", ctx -> {
                        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
                        long worst = 0, total = 0;
                        int over33 = 0, over50 = 0;
                        for (int i = 1; i < frames.size(); i++) {
                            long gap = frames.get(i) - frames.get(i - 1);
                            worst = Math.max(worst, gap);
                            total += gap;
                            if (gap > 33_000_000L) over33++;
                            if (gap > 50_000_000L) over50++;
                        }
                        double avg = frames.size() > 1 ? total / 1e6 / (frames.size() - 1) : 0;
                        com.mojang.logging.LogUtils.getLogger().info("[corruption] large fade: {} blocks, {} frames ({} over 33 ms, {} over 50 ms), avg {} ms, worst {} ms, most ghosts {}, most cells/frame {}",
                                size, frames.size(), over33, over50, String.format("%.1f", avg), String.format("%.1f", worst / 1e6), mostGhosts, mostCells);
                        ctx.check("最慢的一帧在 100 毫秒以内：" + worst / 1_000_000 + " 毫秒", worst < 100_000_000L);
                        var level = Minecraft.getInstance().level;
                        // 只看核心周围 10 格：网络有上限时，满了的网络会在角上分出子核心，子核心不受拆母核心影响，还会接着长
                        int back = 0, inner = HALF - 4;
                        for (int x = -inner; x <= inner; x++) for (int z = -inner; z <= inner; z++) {
                            if (level.getBlockState(HEART.below().offset(x, 0, z)).is(Blocks.GRASS_BLOCK)) back++;
                        }
                        ctx.check("污秽全部变回草方块：" + back, back == (2 * inner + 1) * (2 * inner + 1));
                    })
                    .screenshot("corruption_large_after")
                    .teardown("恢复", ctx -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener));
        }
    }
}
