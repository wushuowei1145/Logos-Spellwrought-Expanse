package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.camera.*;
import dev.logos.dimension.client.companion.CompanionLinesHud;
import dev.logos.dimension.client.vfx.CorruptionFx;
import dev.logos.dimension.client.vfx.CorruptionThorns;
import dev.logos.dimension.elven.blight.*;
import dev.logos.dimension.gametest.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 实际树心里走一遍重做后的女王战：阶段一的法术（含腐化荆棘、荆棘穿刺）、镜头切到祭坛看公主落下、腐败之心周围的净化之光、
 * 光柱和落点飞溅、衍生物、血管、五刀处决、狂乱和拥心共殒。自动驾驶只在开发场景里存在（进度直接调到刻度，不等真实的 20 秒）。
 */
@LDLRegisterClient(name = "queen_core_battle", group = "elven_blight", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public final class QueenBattleViews implements UIScenario {
    private ElvenForestGameTests.Tree tree;
    private UUID queen;
    private long passes, sigils, hud, tints, pillars, cuts, rings;
    private int fired;
    @Override public void configure(ScenarioOptions o) { o.tags("elven_blight").defaultTimeoutMs(360000).scenarioTimeoutMs(600000); }
    private CorruptedElfQueen queen(ServerPlayer player) { return (CorruptedElfQueen) player.level().getEntity(queen); }
    private static void watch(ServerPlayer player, Vec3 from, Vec3 at) {
        var reach = CameraShots.reach(player.level(), at, from);
        CameraShots.play(player, CameraShot.of(CameraPoint.world(reach), CameraPoint.world(at)).timing(0, 900, 0).keepControl().keepHud());
    }
    /** 把进度调到下一个刻度，等它降下来，砍一刀。每次轮询都替玩家护住公主（倒地、退进度在世界测试里单独测）。 */
    private static void cutOnce(ScenarioBuilder s, String name, QueenBattleViews v, boolean shots) {
        int[] before = {0};
        s.waitUntilServer(name + "：等处决的停顿过去", ctx -> { var q = v.queen(ctx.player()); guard(q, ctx.player()); return !q.cutting() || q.phase() != 2; })
            .server(name + "：进度调到刻度", ctx -> {
                var q = v.queen(ctx.player());
                before[0] = q.purification().cleared();
                if (q.phase() == 2 && !q.purification().open() && before[0] < HeartPurification.SEGMENTS)
                    q.purification().debugProgress(HeartPurification.THRESHOLDS[before[0]] - .001f);
            })
            .waitUntilServer(name + "：腐败之心降下来", ctx -> { var q = v.queen(ctx.player()); guard(q, ctx.player()); return q.purification().open() || q.phase() != 2; });
        if (shots) s.ticks(24).screenshot("heart_window_down");
        s.waitUntilServer(name + "：砍中一刀", ctx -> {
            var q = v.queen(ctx.player());
            if (q.purification().cleared() > before[0] || q.phase() != 2) return true;
            guard(q, ctx.player());
            if (q.purification().open()) QueenBattleTests.cutHeart(q, ctx.player());
            return false;
        });
        if (shots) s.ticks(3).screenshot("heart_cut_slash").ticks(8).screenshot("heart_cut_spray");
    }
    private static void guard(CorruptedElfQueen q, ServerPlayer player) {
        if (q.princess(player.level()) instanceof ElfPrincess p) { p.setHealth(p.getMaxHealth()); if (p.downed()) p.forceRevive(); }
    }
    @Override public void define(ScenarioBuilder s) {
        s.runCommand("difficulty normal").setGameMode(GameType.CREATIVE)
            .step("准备战斗镜头", ctx -> { var mc = Minecraft.getInstance(); mc.options.hideGui = false; mc.options.renderDistance().set(16); mc.options.fov().set(76); mc.options.broadcastOptions();
                hud = QueenBossHud.frames(); passes = WorldTintScreen.passes(); tints = WorldTintScreen.spheresDrawn(); sigils = dev.logos.dimension.client.vfx.QueenCrimsonFx.sigils();
                pillars = CorruptionFx.pillars(); cuts = CorruptionFx.cuts(); rings = CorruptionFx.rings(); })
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
            .server("演示腐化荆棘", ctx -> {
                var q = queen(ctx.player());
                watch(ctx.player(), q.position().add(-7, 5, -3), q.position().add(0, .5, -5));
                q.setTarget(ctx.player()); q.play("queen_thorn_vein");
            })
            .ticks(48).screenshot("queen_thorn_vein")
            .ticks(40)
            .server("演示荆棘穿刺", ctx -> {
                var q = queen(ctx.player());
                watch(ctx.player(), ctx.player().position().add(5, 3.5, -5), ctx.player().position().add(0, 1, 0));
                q.setTarget(ctx.player()); q.play("queen_thorn_pierce");
            })
            .waitUntil("荆棘从地里刺出来", ctx -> CorruptionThorns.spikes() > 0)
            .ticks(2).screenshot("queen_thorn_pierce")
            .ticks(40)
            .server("阶段一打空", ctx -> {
                var q = queen(ctx.player()); var world = ctx.player().level();
                q.invulnerableTime = 0; q.hurtServer(world, world.damageSources().playerAttack(ctx.player()), 5000);
            })
            .checkServer("女王被拽回中央、公主从祭坛上方落下", ctx -> queen(ctx.player()).phase() == 2 && queen(ctx.player()).princess(ctx.player().level()).role() == ElfPrincess.Role.LEAP)
            .ticks(7).screenshot("princess_drop")
            .ticks(9).screenshot("princess_landing")
            .ticks(18).screenshot("princess_stand_cast")
            .waitUntilServer("公主开始净化", ctx -> queen(ctx.player()).princess(ctx.player().level()).channeling())
            .waitUntil("公主说话的字幕", ctx -> CompanionLinesHud.showing() != null && CompanionLinesHud.showing().endsWith("elf_princess.land"))
            .ticks(10).screenshot("princess_line_subtitle")
            .server("从大厅一侧看祭坛和腐败之心", ctx -> watch(ctx.player(), tree.pos(-14, 6, 2).getCenter(), tree.pos(0, 6, 12).getCenter()))
            .ticks(20).screenshot("princess_life_beam")
            .check("净化圈的世界着色在画", ctx -> WorldTintScreen.spheresDrawn() > tints)
            .server("看腐败之心周围的净化之光", ctx -> { var q = queen(ctx.player()); q.purification().debugProgress(.12f);
                var c = q.coreCenter(0); watch(ctx.player(), c.add(9, -1.5, -7), c); })
            .ticks(16).screenshot("heart_purify_ring")
            .check("腐败之心周围的光在画", ctx -> CorruptionFx.rings() > rings)
            .step("记下光柱数", ctx -> fired = 0)
            .server("看玩家脚边的光柱", ctx -> { fired = queen(ctx.player()).pillarsFired();
                watch(ctx.player(), ctx.player().position().add(7, 5, -7), ctx.player().position().add(0, 1, 0)); })
            .waitUntilServer("腐败之心轰下一道光柱", ctx -> { guard(queen(ctx.player()), ctx.player()); return queen(ctx.player()).pillarsFired() > fired; })
            .ticks(2).screenshot("heart_pillar_beam")
            .ticks(8).screenshot("heart_pillar_splash")
            .check("光柱真的画了", ctx -> CorruptionFx.pillars() > pillars)
            .waitUntilServer("第一波衍生物从隧道出来", ctx -> { guard(queen(ctx.player()), ctx.player()); return !queen(ctx.player()).waveMobs().isEmpty(); })
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
            .server("回到玩家视角", ctx -> watch(ctx.player(), tree.pos(0, 4, -14).getCenter(), tree.pos(0, 6, 0).getCenter()));
        cutOnce(s, "第一刀", this, true);
        s.check("处决的刀光真的画了", ctx -> CorruptionFx.cuts() > cuts);
        for (int i = 2; i <= 5; i++) cutOnce(s, "第" + "二三四五".charAt(i - 2) + "刀", this, false);
        s.checkServer("第五刀后进入狂乱", ctx -> queen(ctx.player()).phase() == QueenBattleRules.FRENZY)
            .server("从侧面看狂乱", ctx -> watch(ctx.player(), tree.pos(-16, 7, -8).getCenter(), tree.pos(0, 3, 4).getCenter()))
            .ticks(12).screenshot("frenzy_strings_severed")
            .waitUntil("公主喊出来", ctx -> CompanionLinesHud.showing() != null && CompanionLinesHud.showing().endsWith("elf_princess.frenzy"))
            .ticks(8).screenshot("frenzy_princess_line")
            .ticks(90).screenshot("frenzy_spreading")
            .waitUntilServer("女王站起来", ctx -> queen(ctx.player()).phase() == QueenBattleRules.ENDING || queen(ctx.player()).action().equals("queen_rise"))
            .ticks(10).screenshot("frenzy_queen_rises")
            .waitUntil("结局与镜头启动", ctx -> QueenEndingClient.active())
            .checkServer("自爆前不提前写击败", ctx -> !ctx.player().level().getBlockState(tree.pos(0, 9, 0)).getValue(QueenHeartBlock.DEFEATED))
            .ticks(50).screenshot("queen_shared_ruin")
            .ticks(70).screenshot("queen_purification_near")
            .checkServer("自爆期间保护观看玩家", ctx -> {
                var p = ctx.player(); return QueenEndings.protectedNow(p) && dev.logos.dimension.corruption.CorruptionContact.immune(p)
                        && p.level().getBlockState(tree.pos(0, 9, 0)).getValue(QueenHeartBlock.DEFEATED);
            })
            .checkServer("漫开的脉络随她散掉", ctx -> queen(ctx.player()) == null || queen(ctx.player()).filthCount() == 0)
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
