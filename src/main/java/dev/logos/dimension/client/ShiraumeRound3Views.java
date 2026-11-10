package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.ServerContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.client.shiraume.ShiraumeClient;
import dev.logos.dimension.shiraume.Shiraume;
import dev.logos.dimension.shiraume.ShiraumeBeast;
import dev.logos.dimension.shiraume.ShiraumeCue;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import dev.logos.dimension.shiraume.ShiraumeFight;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 白梅战第三轮的实机场景（本地开发用，docs/SHIRAUME_BOSS.md）：一字斩够不着人时挥出的剑气；一字斩举刀时绕到背后直接转身横扫；
 * 狐形加长的大跳蓄力；狂暴时大跳落地的狐火冲击（起初很慢、越来越快）。每段截图，并检查客户端收到的提示。
 */
@LDLRegisterClient(name = "shiraume_round3", group = "shiraume", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ShiraumeRound3Views implements UIScenario {
    static final int FLOOR = ShiraumeViews.FLOOR;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("shiraume").defaultTimeoutMs(120_000).scenarioTimeoutMs(900_000);
    }

    static ServerLevel level(ServerContext ctx) {
        return ctx.server().overworld();
    }

    /** 固定机位：从 {@code from} 看向 {@code at}（不锁操作，留着界面）。 */
    static void shot(ServerContext ctx, Vec3 from, Vec3 at) {
        CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(from), CameraPoint.world(at)).timing(0, 20 * 60, 0).keepControl().keepHud());
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL).runCommand("difficulty normal").runCommand("weather clear").runCommand("time set 6000")
                .step("camera", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    mc.options.renderDistance().set(8);
                    mc.options.fov().set(80);
                    ShiraumeClient.clear();
                })
                .server("arena", ctx -> {
                    ServerLevel level = level(ctx);
                    ShiraumeViews.arena(level);
                    ShiraumeFight.reset(level);
                    ShiraumeTuningViews.place(ctx, .5, -10.5, .5, 4);
                    ShiraumeViews.sturdy(ctx.player(), 400);
                    Shiraume boss = ShiraumeEntities.SHIRAUME.get().create(level, EntitySpawnReason.COMMAND);
                    boss.snapTo(.5, FLOOR + 1, 4, 180, 0);
                    level.addFreshEntity(boss);
                    ShiraumeFight.adopt(level, boss);
                    boss.setScripted(true);
                    ShiraumeTuningViews.stand(boss, .5, 4, .5, -10.5);
                    // 侧面高一点的机位，看剑气从她身前飞向玩家
                    shot(ctx, new Vec3(12, FLOOR + 6, -4), new Vec3(.5, FLOOR + 2, -3));
                })
                .ticks(30)
                // ---- 剑气：她离玩家十五格，一字斩够不着，挥出剑气
                .server("一字斩", ctx -> ShiraumeTuningViews.human(level(ctx)).play("ichimonji_cut"))
                .waitUntil("剑气到了客户端", ctx -> ShiraumeClient.received(ShiraumeCue.WAVE) > 0)
                .ticks(5)
                .screenshot("round3_wave")
                .ticks(30)
                // ---- 举刀时绕到背后：直接转身横扫
                .server("举刀、玩家在背后", ctx -> {
                    Shiraume b = ShiraumeTuningViews.human(level(ctx));
                    ShiraumeTuningViews.stand(b, .5, 2, .5, 8);
                    ShiraumeTuningViews.place(ctx, .5, -.5, .5, 2);
                    b.setScripted(false);
                    b.play("ichimonji_raise");
                    shot(ctx, new Vec3(7, FLOOR + 3.5, 1), new Vec3(.5, FLOOR + 2, 1));
                })
                .waitUntilServer("转身横扫", ctx -> ShiraumeTuningViews.human(level(ctx)).move().equals("turn_sweep"))
                .ticks(6)
                .screenshot("round3_turn_from_ichimonji")
                .ticks(30)
                // ---- 狐形：加长的蓄力、狂暴大跳落地的狐火冲击
                .server("变身", ctx -> {
                    ctx.player().setHealth(ctx.player().getMaxHealth());
                    ShiraumeFight.skipToPhaseTwo(level(ctx));
                })
                .waitUntilServer("狐形出现", ctx -> ShiraumeTuningViews.beast(level(ctx)) != null)
                .waitUntilServer("第二阶段开始", ctx -> !ShiraumeTuningViews.beast(level(ctx)).scripted())
                .server("狂暴、准备大跳", ctx -> {
                    ServerLevel level = level(ctx);
                    ShiraumeBeast b = ShiraumeTuningViews.beast(level);
                    b.hurtServer(level, level.damageSources().generic(), b.getHealth() - b.getMaxHealth() * .1f);
                    b.setScripted(true);
                    b.play("idle_combat");
                    ShiraumeTuningViews.stand(b, .5, 12, .5, 0);
                    ShiraumeTuningViews.place(ctx, .5, -2, .5, 12);
                    ctx.player().setHealth(ctx.player().getMaxHealth());
                    shot(ctx, new Vec3(16, FLOOR + 13, -12), new Vec3(.5, FLOOR, 2));
                })
                .ticks(20)
                .server("大跳", ctx -> {
                    ShiraumeBeast b = ShiraumeTuningViews.beast(level(ctx));
                    b.setTarget(ctx.player());
                    b.play("pounce_crouch");
                })
                .ticks(10)
                .screenshot("round3_pounce_crouch")
                .waitUntilServer("落地", ctx -> ShiraumeTuningViews.beast(level(ctx)).move().equals("pounce_land"))
                .check("收到狐火冲击", ctx -> ShiraumeClient.received(ShiraumeCue.RING) > 0)
                .ticks(14)
                .screenshot("round3_ring_slow")
                .ticks(20)
                .screenshot("round3_ring_fast")
                .ticks(14)
                .screenshot("round3_ring_far")
                .teardown("restore", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    ShiraumeClient.clear();
                });
    }
}
