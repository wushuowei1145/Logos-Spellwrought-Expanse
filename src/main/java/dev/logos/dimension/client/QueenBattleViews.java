package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.camera.*;
import dev.logos.dimension.elven.blight.*;
import dev.logos.dimension.gametest.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;

/** 实际树心内检查三阶段、反抗动作、下降核心与升空净化；自动驾驶只在开发场景里存在。 */
@LDLRegisterClient(name = "queen_core_battle", group = "elven_blight", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public final class QueenBattleViews implements UIScenario {
    private ElvenForestGameTests.Tree tree;
    private UUID queen;
    private long passes, sigils, hud;
    @Override public void configure(ScenarioOptions o) { o.tags("elven_blight").defaultTimeoutMs(360000).scenarioTimeoutMs(600000); }
    private CorruptedElfQueen queen(net.minecraft.server.level.ServerPlayer player) { return (CorruptedElfQueen) player.level().getEntity(queen); }
    @Override public void define(ScenarioBuilder s) {
        s.runCommand("difficulty normal").setGameMode(GameType.CREATIVE)
            .step("准备战斗镜头", ctx -> { var mc = Minecraft.getInstance(); mc.options.hideGui = false; mc.options.renderDistance().set(16); mc.options.fov().set(76); mc.options.broadcastOptions(); hud = QueenBossHud.frames(); passes = QueenPurificationScreen.passes(); sigils = dev.logos.dimension.client.vfx.QueenCrimsonFx.sigils(); })
            .server("原版拼接真实树心并开战", ctx -> {
                var world = ctx.server().getLevel(LogosDimension.CLOUDSEA); tree = ElvenBlightGameTests.placeStudy(world);
                var feet = tree.pos(0, 3, -4).getBottomCenter();
                var facing = tree.pos(0, 5, 0).getCenter().subtract(feet.add(0, 1.6, 0));
                float yaw = (float) Math.toDegrees(Math.atan2(-facing.x, facing.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(facing.y, Math.hypot(facing.x, facing.z)));
                DimensionTravel.teleport(ctx.player(), world, feet, yaw, pitch);
                ctx.player().setGameMode(GameType.SURVIVAL); ctx.player().getAbilities().invulnerable = true; ctx.player().onUpdateAbilities();
                queen = ((QueenHeart) world.getBlockEntity(tree.pos(0, 9, 0))).awaken(ctx.player()).getUUID();
                world.getWeatherData().setRaining(false);
            }).waitUntilServer("入场结束", ctx -> !queen(ctx.player()).entering()).ticks(4)
            .step("记录阶段结束的真实帧", ctx -> QueenBattleCapture.start(queen))
            .server("演示真实剑雨招式", ctx -> {
                // 仅开发截图从侧面观察，实战招式不接管玩家镜头，也不改变物理目标。
                var q = queen(ctx.player());
                var from = CameraShots.reach(ctx.player().level(), q.arena().add(0, 5, 0), tree.pos(8, 9, -10).getCenter());
                CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(from), CameraPoint.world(tree.pos(0, 9, -3).getCenter())).timing(0, 700, 0).keepControl().keepHud());
                queen(ctx.player()).play("queen_sword_rain");
            })
            .ticks(25).screenshot("queen_crimson_sword_circle")
            .ticks(25).screenshot("queen_logos_sword_rain")
            .check("女王专属血条实际绘制", ctx -> QueenBossHud.frames() > hud)
            .check("猩红生命法阵已同步", ctx -> dev.logos.dimension.client.vfx.QueenCrimsonFx.sigils() > sigils)
            .ticks(45)
            .server("演示抬手生命射线", ctx -> queen(ctx.player()).play("queen_life_ray"))
            .ticks(32).screenshot("queen_crimson_life_ray")
            .ticks(40)
            .server("攻击控制韧性", ctx -> {
                var q = queen(ctx.player()); var world = ctx.player().level();
                for (int i = 0; i < 2; i++) { q.invulnerableTime = 0; q.hurtServer(world, world.damageSources().playerAttack(ctx.player()), 12); }
            }).waitUntilServer("女王挣脱控制并暴露根结", ctx -> queen(ctx.player()).rootsOpen() && queen(ctx.player()).tethers(ctx.player().level()).size() >= 3)
            .checkServer("女王受击不扣核心生命", ctx -> queen(ctx.player()).getHealth() == 400)
            .ticks(15).screenshot("queen_resisting")
            .server("斩断第一阶段根结", ctx -> QueenBattleTests.cut(queen(ctx.player()), ctx.player())).ticks(22).screenshot("queen_stage_one_end")
            .checkServer("第一阶段结束动画保护根结", ctx -> queen(ctx.player()).phase() == 2 && queen(ctx.player()).transitioning() && !queen(ctx.player()).rootsOpen())
            .waitUntilServer("挣扎动作结束后才开放援护", ctx -> queen(ctx.player()).phase() == 2 && queen(ctx.player()).rootsOpen())
            .ticks(10).checkServer("第二阶段援护", ctx -> queen(ctx.player()).phase() == 2 && queen(ctx.player()).rootsOpen())
            .screenshot("queen_helping")
            .server("斩断第二阶段根结", ctx -> QueenBattleTests.cut(queen(ctx.player()), ctx.player())).ticks(30).screenshot("queen_stage_two_end")
            .checkServer("第二阶段结束动作尚在播放", ctx -> queen(ctx.player()).phase() == 3 && queen(ctx.player()).transitioning() && !queen(ctx.player()).rootsOpen())
            .waitUntilServer("扯断动作结束", ctx -> !queen(ctx.player()).transitioning()).ticks(4)
            .checkServer("核心下降且保持供养护盾", ctx -> {
                var q = queen(ctx.player()); return q.phase() == 3 && q.corePosition(0).y <= tree.pos(0, 9, 0).getY() - 5.9
                        && !q.damageCore(ctx.player().level(), ctx.player().level().damageSources().playerAttack(ctx.player()), 90);
            }).screenshot("queen_binding_heart")
            .server("完成断链并击碎核心", ctx -> {
                var q = queen(ctx.player()); QueenBattleTests.cut(q, ctx.player()); q.invulnerableTime = 0;
                q.damageCore(ctx.player().level(), ctx.player().level().damageSources().playerAttack(ctx.player()), 400);
            }).waitUntil("结局与镜头启动", ctx -> QueenEndingClient.active())
            .checkServer("自爆前不提前写击败", ctx -> !ctx.player().level().getBlockState(tree.pos(0, 9, 0)).getValue(QueenHeartBlock.DEFEATED))
            .ticks(50).screenshot("queen_shared_ruin")
            .ticks(70).screenshot("queen_purification_near")
            .checkServer("自爆期间保护观看玩家", ctx -> {
                var p = ctx.player(); return QueenEndings.protectedNow(p) && dev.logos.dimension.corruption.CorruptionContact.immune(p)
                        && p.level().getBlockState(tree.pos(0, 9, 0)).getValue(QueenHeartBlock.DEFEATED);
            })
            .ticks(80).screenshot("queen_purification_aerial")
            .check("世界坐标净化通道实际绘制", ctx -> QueenPurificationScreen.passes() > passes)
            .ticks(40).screenshot("queen_forest_reborn")
            .waitUntilServer("地板真实恢复健康材质", ctx -> !ctx.player().level().getBlockState(tree.pos(0, 1, 0)).is(ElvenBlight.LUMEN.get()))
            .waitUntil("过场自然结束", ctx -> !QueenEndingClient.active() && !dev.logos.dimension.client.camera.CameraDirector.active())
            .check("过场结束交还镜头", ctx -> !QueenEndingClient.active() && !dev.logos.dimension.client.camera.CameraDirector.active())
            .checkServer("女王与核心不再重生", ctx -> ((QueenHeart) ctx.player().level().getBlockEntity(tree.pos(0, 9, 0))).awaken(ctx.player()) == null && !QueenEndings.protectedNow(ctx.player()))
            .teardown("恢复玩家设置", ctx -> { Minecraft.getInstance().options.hideGui = false; QueenEndingClient.hold(null); })
            .server("恢复玩家模式", ctx -> ctx.player().setGameMode(GameType.CREATIVE));
    }
}
