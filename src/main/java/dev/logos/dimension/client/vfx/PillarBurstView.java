package dev.logos.dimension.client.vfx;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;

import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.vfx.PillarBurst;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 金色光柱的远景：在混合地面（石头、草、泥土、粗泥、圆石）上爆发，按冲起、峰值、保持、收细、成线、消失各截一张。
 * 每个阶段都重放一次新的光柱再采样，截图本身占用的时间不会把短暂的阶段跳过去。
 */
@LDLRegisterClient(name = "pillar_burst", group = "pillar_burst", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class PillarBurstView implements UIScenario {
    static final Vec3 AT = new Vec3(.5, 100, .5);
    private boolean previousHideGui;

    @Override public void configure(ScenarioOptions o) { o.tags("pillar_burst").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> previousHideGui = viewport())
                .server("铺混合地面", ctx -> arena(ctx.server().overworld()))
                .server("远处仰望", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(.5, 100, 34.5), 180, -30))
                .ticks(20);
        stage(s, "pillar_rise", 3);
        stage(s, "pillar_peak", 9);
        stage(s, "pillar_hold", 28);
        stage(s, "pillar_fade", 56);
        stage(s, "pillar_line", 65);
        stage(s, "pillar_gone", 80);
        s.teardown("恢复视口", ctx -> restore(previousHideGui));
    }

    /** 清掉正在播放的，重放一次，等 {@code ticks} 刻后截图。 */
    static void stage(ScenarioBuilder s, String name, int ticks) {
        s.step("重放 " + name, ctx -> { PillarBursts.clear(); PillarBursts.spawn(AT, 1); })
                .ticks(ticks).screenshot(name);
    }

    static boolean viewport() {
        var mc = Minecraft.getInstance();
        boolean hide = mc.options.hideGui;
        mc.options.hideGui = true;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
        mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
        mc.options.fov().set(70); mc.options.renderDistance().set(8); mc.options.broadcastOptions();
        return hide;
    }

    static void restore(boolean hideGui) {
        PillarBursts.clear();
        Minecraft.getInstance().options.hideGui = hideGui;
    }

    /** 以光柱落点为中心铺一片地：中间石头，外面草地里夹着泥土、粗泥和圆石，碎石能看出各自的贴图。 */
    static void arena(ServerLevel level) {
        BlockState[] patches = {Blocks.DIRT.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()};
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 40; z++) {
            int hash = Math.floorMod(x * 73856093 ^ z * 19349663, 11);
            BlockState ground = x * x + z * z <= 6 ? Blocks.STONE.defaultBlockState()
                    : hash < 3 ? patches[hash] : Blocks.GRASS_BLOCK.defaultBlockState();
            level.setBlock(new BlockPos(x, 99, z), ground, 2);
            for (int y = 100; y < 112; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** 近景、夜晚和经服务端发包的一次：看碎石、尘土与冲击波的细节。 */
    @LDLRegisterClient(name = "pillar_burst_close", group = "pillar_burst", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Close implements UIScenario {
        private boolean previousHideGui;

        @Override public void configure(ScenarioOptions o) { o.tags("pillar_burst").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }

        @Override public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                    .step("设置视口", ctx -> previousHideGui = viewport())
                    .server("铺混合地面", ctx -> arena(ctx.server().overworld()))
                    .server("近处平视", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(.5, 100, 13.5), 180, 8))
                    .ticks(20);
            stage(s, "pillar_close_burst", 6);
            stage(s, "pillar_close_debris", 16);
            stage(s, "pillar_close_settle", 40);
            s.step("清空", ctx -> PillarBursts.clear())
                    .server("经服务端发包", ctx -> PillarBurst.play(ctx.server().overworld(), AT, 1))
                    .waitUntil("客户端收到光柱", ctx -> PillarBursts.count() > 0).ticks(12)
                    .screenshot("pillar_close_network")
                    .runCommand("time set night")
                    .server("夜里远看", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(.5, 100, 34.5), 180, -30))
                    .ticks(10);
            stage(s, "pillar_night", 14);
            s.server("站在光柱里", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(1.2, 100, 1.2), 180, -10))
                    .ticks(5);
            stage(s, "pillar_inside", 14);
            s.teardown("恢复视口", ctx -> restore(previousHideGui));
        }
    }
}
