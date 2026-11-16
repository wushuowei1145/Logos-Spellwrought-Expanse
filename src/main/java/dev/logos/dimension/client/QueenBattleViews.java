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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * 实际树心里走一遍重做后的女王战：阶段一的法术、公主跃上祭坛、生命光束、衍生物、窗口压低腐败之心、砍断血管、挣脱束缚、
 * 拥心共殒和升空净化。自动驾驶只在开发场景里存在（窗口进度直接调到刻度，不等真实的 50 秒）。
 */
@LDLRegisterClient(name = "queen_core_battle", group = "elven_blight", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public final class QueenBattleViews implements UIScenario {
    private ElvenForestGameTests.Tree tree;
    private UUID queen;
    private long passes, sigils, hud, tints;
    @Override public void configure(ScenarioOptions o) { o.tags("elven_blight").defaultTimeoutMs(360000).scenarioTimeoutMs(600000); }
    private CorruptedElfQueen queen(ServerPlayer player) { return (CorruptedElfQueen) player.level().getEntity(queen); }
    private static void watch(ServerPlayer player, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 at) {
        var reach = CameraShots.reach(player.level(), at, from);
        CameraShots.play(player, CameraShot.of(CameraPoint.world(reach), CameraPoint.world(at)).timing(0, 900, 0).keepControl().keepHud());
    }
    /** 窗口里用生命伤害把这一段打完：每次轮询都打（服务端同一个人 8 刻内只算一下），直到这一段打掉。 */
    private static void clearWindow(ScenarioBuilder s, String name, QueenBattleViews v) {
        int[] before = {0};
        // 场景里没人真的守着公主：替玩家护住她（倒地、退进度是守护玩法本身，世界测试里单独测）。
        s.waitUntilServer(name + "：窗口打开", ctx -> { var q = v.queen(ctx.player()); guard(q, ctx.player()); before[0] = q.purification().cleared(); return q.purification().open(); })
            .waitUntilServer(name + "：打完这一段", ctx -> {
                var q = v.queen(ctx.player());
                if (q.purification().cleared() > before[0] || q.phase() == 4) return true;
                guard(q, ctx.player());
                if (q.purification().open()) QueenBattleTests.lifeHit(q, ctx.player());
                return false;
            });
    }
    private static void guard(CorruptedElfQueen q, ServerPlayer player) {
        if (q.princess(player.level()) instanceof ElfPrincess p) { p.setHealth(p.getMaxHealth()); if (p.downed()) p.forceRevive(); }
    }
    @Override public void define(ScenarioBuilder s) {
        s.runCommand("difficulty normal").setGameMode(GameType.CREATIVE)
            .step("准备战斗镜头", ctx -> { var mc = Minecraft.getInstance(); mc.options.hideGui = false; mc.options.renderDistance().set(16); mc.options.fov().set(76); mc.options.broadcastOptions();
                hud = QueenBossHud.frames(); passes = WorldTintScreen.passes(); tints = WorldTintScreen.spheresDrawn(); sigils = dev.logos.dimension.client.vfx.QueenCrimsonFx.sigils(); })
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
            .checkServer("公主在大厅入口等着", ctx -> queen(ctx.player()).princess(ctx.player().level()) instanceof ElfPrincess p && p.role() == ElfPrincess.Role.WAITING)
            .step("记录阶段结束的真实帧", ctx -> QueenBattleCapture.start(queen))
            .server("演示真实剑雨招式", ctx -> {
                // 仅开发截图从侧面观察，实战招式不接管玩家镜头，也不改变物理目标。
                watch(ctx.player(), tree.pos(8, 9, -10).getCenter(), tree.pos(0, 9, -3).getCenter());
                queen(ctx.player()).play("queen_sword_rain");
            })
            .ticks(25).screenshot("queen_crimson_sword_circle")
            .ticks(25).screenshot("queen_logos_sword_rain")
            .check("女王专属血条实际绘制", ctx -> QueenBossHud.frames() > hud)
            .check("猩红生命法阵已同步", ctx -> dev.logos.dimension.client.vfx.QueenCrimsonFx.sigils() > sigils)
            .ticks(45)
            .server("阶段一打空", ctx -> {
                var q = queen(ctx.player()); var world = ctx.player().level();
                q.invulnerableTime = 0; q.hurtServer(world, world.damageSources().playerAttack(ctx.player()), 5000);
            })
            .checkServer("女王跪地、进入守护净化", ctx -> queen(ctx.player()).phase() == 2 && queen(ctx.player()).isAlive())
            .ticks(22).screenshot("princess_leap")
            .waitUntilServer("公主跃上祭坛开始净化", ctx -> queen(ctx.player()).princess(ctx.player().level()).channeling())
            .server("从大厅一侧看祭坛和腐败之心", ctx -> watch(ctx.player(), tree.pos(-14, 6, 2).getCenter(), tree.pos(0, 6, 12).getCenter()))
            .ticks(20).screenshot("princess_life_beam")
            .check("净化圈的世界着色在画", ctx -> WorldTintScreen.spheresDrawn() > tints)
            .waitUntilServer("第一波衍生物从隧道出来", ctx -> !queen(ctx.player()).waveMobs().isEmpty())
            .server("看隧道口的衍生物", ctx -> watch(ctx.player(), tree.pos(4, 7, -4).getCenter(), tree.pos(-24, 2, 0).getCenter()))
            .ticks(12).screenshot("queen_wave_from_tunnel")
            .server("看高台上的血管接头", ctx -> {
                var q = queen(ctx.player());
                var knot = q.tethers(ctx.player().level()).stream().filter(t -> !t.heart()).findFirst().map(net.minecraft.world.entity.Entity::position).orElse(tree.pos(16, 12, 16).getCenter());
                var inward = q.arena().subtract(knot).multiply(1, 0, 1).normalize();
                watch(ctx.player(), knot.add(inward.scale(6)).add(0, 2.5, 0), knot.add(0, 1.2, 0));
            })
            .ticks(8).screenshot("queen_vein_knots")
            .server("砍断所有血管", ctx -> QueenBattleTests.cut(queen(ctx.player()), ctx.player()))
            .checkServer("血管都记下了", ctx -> Integer.bitCount(queen(ctx.player()).brokenRoots()) == QueenBattleRules.reachableVeins())
            .ticks(10).screenshot("queen_veins_severed")
            .server("进度调到第一个刻度", ctx -> {
                queen(ctx.player()).purification().debugProgress(.329f);
                watch(ctx.player(), tree.pos(0, 4, -14).getCenter(), tree.pos(0, 6, 0).getCenter());
            });
        clearWindow(s.ticks(30).screenshot("heart_window_open"), "第一个窗口", this);
        s.server("进度调到第二个刻度", ctx -> queen(ctx.player()).purification().debugProgress(.659f));
        clearWindow(s, "第二个窗口", this);
        s.checkServer("女王挣断控制", ctx -> queen(ctx.player()).phase() == 3)
            .ticks(30).screenshot("queen_chain_break")
            .waitUntilServer("束缚腐败之心", ctx -> !queen(ctx.player()).transitioning() && queen(ctx.player()).action().equals("queen_bind"))
            .ticks(20).screenshot("queen_binding_heart")
            .server("进度调到最后的刻度", ctx -> queen(ctx.player()).purification().debugProgress(.999f));
        clearWindow(s, "最后的窗口", this);
        s.waitUntil("结局与镜头启动", ctx -> QueenEndingClient.active())
            .checkServer("自爆前不提前写击败", ctx -> !ctx.player().level().getBlockState(tree.pos(0, 9, 0)).getValue(QueenHeartBlock.DEFEATED))
            .ticks(50).screenshot("queen_shared_ruin")
            .ticks(70).screenshot("queen_purification_near")
            .checkServer("自爆期间保护观看玩家", ctx -> {
                var p = ctx.player(); return QueenEndings.protectedNow(p) && dev.logos.dimension.corruption.CorruptionContact.immune(p)
                        && p.level().getBlockState(tree.pos(0, 9, 0)).getValue(QueenHeartBlock.DEFEATED);
            })
            .ticks(80).screenshot("queen_purification_aerial")
            .check("世界坐标净化通道实际绘制", ctx -> WorldTintScreen.passes() > passes)
            .ticks(40).screenshot("queen_forest_reborn")
            .waitUntilServer("地板真实恢复健康材质", ctx -> !ctx.player().level().getBlockState(tree.pos(0, 1, 0)).is(ElvenBlight.LUMEN.get()))
            .waitUntil("过场自然结束", ctx -> !QueenEndingClient.active() && !dev.logos.dimension.client.camera.CameraDirector.active())
            .check("过场结束交还镜头", ctx -> !QueenEndingClient.active() && !dev.logos.dimension.client.camera.CameraDirector.active())
            .checkServer("女王与核心不再重生", ctx -> ((QueenHeart) ctx.player().level().getBlockEntity(tree.pos(0, 9, 0))).awaken(ctx.player()) == null && !QueenEndings.protectedNow(ctx.player()))
            .teardown("恢复玩家设置", ctx -> { Minecraft.getInstance().options.hideGui = false; QueenEndingClient.hold(null); })
            .server("恢复玩家模式", ctx -> ctx.player().setGameMode(GameType.CREATIVE));
    }
}
