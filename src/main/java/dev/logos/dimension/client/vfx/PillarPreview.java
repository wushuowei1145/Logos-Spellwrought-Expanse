package dev.logos.dimension.client.vfx;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;

import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.vfx.PillarBurst;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 金色光柱的动态展示，给有窗口的预览脚本（预览金色光柱.cmd）用：不截图、不检查，换几个机位各经服务端播放一次，
 * 留足时间看完整个过程。白天和夜晚分成两个短场景，避免单个场景太长。
 */
@LDLRegisterClient(name = "pillar_preview_day", group = "pillar_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class PillarPreview implements UIScenario {
    /** 一次光柱放完再留一点空白。 */
    static final int WATCH = PillarBurstVisual.LIFE + 16;
    private boolean previousHideGui;

    @Override public void configure(ScenarioOptions o) { o.tags("pillar_preview").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> previousHideGui = PillarBurstView.viewport())
                .server("铺混合地面", ctx -> PillarBurstView.arena(ctx.server().overworld()));
        show(s, "远处仰望", new Vec3(.5, 100, 34.5), 180, -30, 1);
        show(s, "近处平视", new Vec3(.5, 100, 13.5), 180, 8, 1);
        show(s, "侧面看两倍大小", new Vec3(-19.5, 100, .5), -90, -42, 2);
        s.teardown("恢复视口", ctx -> PillarBurstView.restore(previousHideGui));
    }

    /** 移到机位，等画面稳定，经服务端放一次光柱，看完。 */
    static void show(ScenarioBuilder s, String name, Vec3 at, float yaw, float pitch, float scale) {
        s.server(name, ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), at, yaw, pitch))
                .ticks(15)
                .step("清空", ctx -> PillarBursts.clear())
                .server("爆发（缩放 " + scale + "）", ctx -> PillarBurst.play(ctx.server().overworld(), PillarBurstView.AT, scale))
                .ticks(WATCH);
    }

    /** 夜里远看、站在旁边第一人称、第三人称背后。 */
    @LDLRegisterClient(name = "pillar_preview_night", group = "pillar_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Night implements UIScenario {
        private boolean previousHideGui;

        @Override public void configure(ScenarioOptions o) { o.tags("pillar_preview").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }

        @Override public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set night").runCommand("difficulty peaceful")
                    .step("设置视口", ctx -> previousHideGui = PillarBurstView.viewport())
                    .server("铺混合地面", ctx -> PillarBurstView.arena(ctx.server().overworld()));
            show(s, "夜里远看", new Vec3(.5, 100, 34.5), 180, -30, 1);
            show(s, "站在旁边", new Vec3(.5, 100, 6.5), 180, -20, 1);
            s.step("第三人称背后", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK));
            show(s, "背后看", new Vec3(.5, 100, 9.5), 180, -12, 1);
            s.teardown("恢复视口", ctx -> {
                Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                PillarBurstView.restore(previousHideGui);
            });
        }
    }
}
