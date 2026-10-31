package dev.logos.dimension.client.vfx;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;

import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.vfx.ManaTornado;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 魔力龙卷的动态展示，给有窗口的预览脚本（预览魔力龙卷.cmd）用：不截图、不检查。卷在村民身上远看、近看，
 * 再卷在玩家自己身上看第三人称和第一人称；每段都经服务端卷起，颜色 12 秒转一圈，最后收掉看它散开。
 * 白天和夜晚分成两个短场景。
 */
@LDLRegisterClient(name = "tornado_preview_day", group = "tornado_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class TornadoPreview implements UIScenario {
    /** 每段卷多久，再留多久看它散开。 */
    static final int WATCH = 200, AFTER = (int) ManaTornadoVisual.FADE + 16;
    private boolean previousHideGui;

    @Override public void configure(ScenarioOptions o) { o.tags("tornado_preview").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> previousHideGui = PillarBurstView.viewport())
                .server("铺混合地面", ctx -> PillarBurstView.arena(ctx.server().overworld()));
        villager(s, "远处平视", new Vec3(.5, 100, 16.5), 180, -14);
        villager(s, "近处", new Vec3(.5, 100, 7.5), 180, -25);
        self(s, "第三人称背后", CameraType.THIRD_PERSON_BACK, 12);
        s.teardown("恢复视口", ctx -> ManaTornadoView.restore(previousHideGui));
    }

    /** 移到机位，在村民身上卷一段，收掉，看完散开。 */
    static void villager(ScenarioBuilder s, String name, Vec3 at, float yaw, float pitch) {
        s.server("放一个施法者", ctx -> ManaTornadoView.caster = ManaTornadoView.summonCaster(ctx.server().overworld()))
                .server(name, ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), at, yaw, pitch))
                .ticks(15)
                .step("清空", ctx -> ManaTornados.clear())
                .server("卷起", ctx -> ManaTornado.play(ctx.server().overworld(), ManaTornadoView.casterEntity(ctx.server().overworld()), -1, 1, ManaTornado.CYCLE))
                .ticks(WATCH)
                .server("收掉", ctx -> ManaTornado.stop(ctx.server().overworld(), ManaTornadoView.casterEntity(ctx.server().overworld())))
                .ticks(AFTER)
                .server("收走施法者", ctx -> ManaTornadoView.dismiss(ctx.server().overworld()));
    }

    /** 站到中间，换到 {@code camera} 视角，在玩家自己身上卷一段，收掉，看完散开。 */
    static void self(ScenarioBuilder s, String name, CameraType camera, float pitch) {
        s.server("站到中间", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), ManaTornadoView.AT, 180, pitch))
                .step(name, ctx -> Minecraft.getInstance().options.setCameraType(camera))
                .ticks(15)
                .step("清空", ctx -> ManaTornados.clear())
                .server("卷起", ctx -> ManaTornado.play(ctx.server().overworld(), ctx.player(), -1, 1, ManaTornado.CYCLE))
                .ticks(WATCH)
                .server("收掉", ctx -> ManaTornado.stop(ctx.server().overworld(), ctx.player()))
                .ticks(AFTER)
                .step("第一人称", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON));
    }

    /** 夜里远看、第三人称背后、第一人称往上看。 */
    @LDLRegisterClient(name = "tornado_preview_night", group = "tornado_preview", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Night implements UIScenario {
        private boolean previousHideGui;

        @Override public void configure(ScenarioOptions o) { o.tags("tornado_preview").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

        @Override public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set night").runCommand("difficulty peaceful")
                    .step("设置视口", ctx -> previousHideGui = PillarBurstView.viewport())
                    .server("铺混合地面", ctx -> PillarBurstView.arena(ctx.server().overworld()));
            villager(s, "夜里远看", new Vec3(.5, 100, 16.5), 180, -14);
            self(s, "第三人称背后", CameraType.THIRD_PERSON_BACK, 12);
            self(s, "第一人称往上看", CameraType.FIRST_PERSON, -50);
            s.teardown("恢复视口", ctx -> ManaTornadoView.restore(previousHideGui));
        }
    }
}
