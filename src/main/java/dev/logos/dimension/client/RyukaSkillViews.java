package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.execution.ExecutionClient;
import dev.logos.dimension.client.execution.ExecutionSlashes;
import dev.logos.dimension.client.longsword.FoxMarksClient;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.combat.Airborne;
import dev.logos.dimension.execution.ExecutionPayload;
import dev.logos.dimension.execution.ExecutionShot;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.execution.InteractiveExecutions;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.dimension.ryuka.FoxMarks;
import dev.logos.dimension.ryuka.Ryuka;
import dev.logos.dimension.ryuka.RyukaExecution;
import dev.logos.runtime.PlayerMagic;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 流火的平一文字、突进上挑、狐火印和可交互处决的实际按键试玩（docs/RYUKA.md 第 3.2–3.4 节）。数值的逐刻检查在世界测试
 * {@code longsword_ryuka_marks}、{@code longsword_ryuka_dash}、{@code longsword_ryuka_execution}；这里看样子、按键和两端对不对得上。
 * 分成两段：场景太长时测试工具会卡住。
 */
public final class RyukaSkillViews {
    private RyukaSkillViews() {}

    /** 两段共用：石砖练刀场，正前方 4 格一只不动的尸壳（不怕太阳，血够厚），流火在第 2 格。 */
    abstract static class Base implements UIScenario {
        boolean previousHideGui;
        int target;
        float health;
        /** 尸壳一开始站在哪（流火拿出来之前放好）。 */
        Vec3 huskAt() {
            return new Vec3(.5, 100, 4.5);
        }

        boolean phase(String... moves) {
            var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
            return p != null && Arrays.asList(moves).contains(p.move());
        }

        LivingEntity husk(MinecraftServer server) {
            return (LivingEntity) server.overworld().getEntity(target);
        }

        static void camera(CameraType type) {
            Minecraft.getInstance().options.setCameraType(type);
        }

        static void attack(boolean down) {
            Minecraft.getInstance().options.keyAttack.setDown(down);
        }

        static void guard(boolean down) {
            var mc = Minecraft.getInstance();
            mc.options.keyUse.setDown(down);
            if (down) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            else mc.gameMode.releaseUsingItem(mc.player);
        }

        @Override public void configure(ScenarioOptions o) {
            o.tags("ryuka").defaultTimeoutMs(60000).scenarioTimeoutMs(240000);
        }

        ScenarioBuilder arena(ScenarioBuilder s) {
            return s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                    .runCommand("gamerule spawn_mobs false")
                    .step("设置视口", ctx -> {
                        var mc = Minecraft.getInstance();
                        previousHideGui = mc.options.hideGui;
                        mc.options.hideGui = false;
                        mc.player.getInventory().setSelectedSlot(0);
                        camera(CameraType.THIRD_PERSON_FRONT);
                        mc.options.fov().set(70);
                        var w = mc.getWindow();
                        w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                        mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    }).server("练刀场与尸壳", ctx -> {
                        var level = ctx.server().overworld();
                        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 12; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                            for (int y = 100; y < 110; y++) level.removeBlock(new BlockPos(x, y, z), false);
                        }
                        DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                        var husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                        husk.setNoAi(true);
                        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(2000);
                        husk.setHealth(2000);
                        husk.setPos(huskAt());
                        husk.setYRot(180); husk.setYHeadRot(180); husk.yBodyRot = 180;
                        level.addFreshEntity(husk);
                        target = husk.getId();
                        var magic = PlayerMagic.of(ctx.player());
                        magic.maxMana = Math.max(magic.maxMana, 3000);
                        magic.mana = 3000;
                        ctx.player().getInventory().clearContent();
                        ctx.player().getInventory().setItem(1, new ItemStack(RyukaItem.ITEM.get()));
                    }).setGameMode(GameType.SURVIVAL).ticks(10)
                    .step("拿出流火", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(1))
                    .waitUntil("待机", ctx -> phase("idle")).ticks(4);
        }

        ScenarioBuilder finish(ScenarioBuilder s) {
            return s.server("移除尸壳", ctx -> {
                var e = ctx.server().overworld().getEntity(target);
                if (e != null) e.discard();
            }).teardown("恢复输入", ctx -> {
                var mc = Minecraft.getInstance();
                attack(false);
                mc.options.keyUse.setDown(false);
                mc.options.hideGui = previousHideGui;
                camera(CameraType.FIRST_PERSON);
            });
        }
    }

    /** 平一文字の構え、构え的格挡、蓄够了突进上挑挑飞；狐火印攒满爆开、之后能处决；刀身的狐火比原来淡。 */
    @LDLRegisterClient(name = "ryuka_stance", group = "ryuka", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static final class Stance extends Base {
        /** 先站在侧后方，免得挡住正面的镜头；突进之前再挪到正前方。 */
        @Override Vec3 huskAt() {
            return new Vec3(5.5, 100, -3.5);
        }

        @Override public void define(ScenarioBuilder s) {
            arena(s)
                    .step("侧面看刀身的狐火", ctx -> {
                        var p = Minecraft.getInstance().player;
                        p.yBodyRot = 60; p.yBodyRotO = 60;
                    }).ticks(1).screenshot("idle_side_pale")
                    .step("恢复身体朝向", ctx -> {
                        var p = Minecraft.getInstance().player;
                        p.yBodyRot = 0; p.yBodyRotO = 0;
                    })
                    // ---- 平一文字
                    .step("按住右键格挡", ctx -> guard(true))
                    .waitUntil("格挡", ctx -> phase("guard"))
                    .step("按住左键蓄力", ctx -> attack(true))
                    .waitUntil("平一文字の構え", ctx -> phase("stance")).ticks(8).screenshot("stance_front")
                    .step("背后看", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(2).screenshot("stance_back")
                    .step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(2).screenshot("stance_first_person")
                    .step("回到正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                    .server("构え里挨一下", ctx -> ctx.player().hurtServer(ctx.server().overworld(),
                            ctx.server().overworld().damageSources().mobAttack(husk(ctx.server())), 4))
                    .waitUntil("构え的格挡", ctx -> phase("stance_block")).ticks(1).screenshot("stance_block")
                    .waitUntil("回到构え", ctx -> phase("stance_hold"))
                    .server("尸壳挪到正前方 4 格", ctx -> {
                        // 构え里挨的那一下把人推开了一点：放回原处，正对着尸壳
                        ctx.player().teleportTo(.5, 100, .5);
                        ctx.player().setYRot(0); ctx.player().setXRot(0);
                        var husk = husk(ctx.server());
                        husk.teleportTo(.5, 100, 4.5);
                        husk.setYRot(180); husk.setYHeadRot(180); husk.yBodyRot = 180;
                        health = husk.getHealth();
                    }).ticks(2)
                    .step("从侧面看突进", ctx -> {
                        camera(CameraType.THIRD_PERSON_FRONT);
                        var p = Minecraft.getInstance().player;
                        p.setYRot(0);
                    })
                    .step("松开左键突进", ctx -> attack(false))
                    .waitUntilServer("突进到尸壳面前挑飞", ctx -> Airborne.is(husk(ctx.server())))
                    .checkServer("突进走了 1.5 格以上", ctx -> ctx.player().getZ() > 2)
                    .ticks(2).screenshot("rise")
                    .ticks(6).screenshot("launched")
                    .checkServer("上挑砍中", ctx -> husk(ctx.server()).getHealth() < health)
                    .check("尸壳没爆开过就是挑飞", ctx -> !ExecutionClient.active())
                    .step("松开右键", ctx -> guard(false))
                    .waitUntil("回到待机", ctx -> phase("idle"))
                    .waitUntilServer("尸壳落地", ctx -> !Airborne.is(husk(ctx.server())))
                    // ---- 狐火印
                    .step("正面看", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                    .server("砍四刀", ctx -> {
                        var husk = husk(ctx.server());
                        FoxMarks.clear(husk);
                        for (int i = 0; i < 4; i++) { husk.invulnerableTime = 0; Ryuka.strike(ctx.player(), husk, Ryuka.DAMAGE); }
                    })
                    .checkServer("四枚印、照常缠身、还不能处决", ctx -> FoxMarks.count(husk(ctx.server())) == 4
                            && FoxfireRules.burning(husk(ctx.server())) != null && !FoxMarks.executable(husk(ctx.server())))
                    .waitUntil("客户端看见四枚印", ctx -> FoxMarksClient.count(target) == 4).ticks(6).screenshot("marks_4")
                    .server("第五刀", ctx -> { var husk = husk(ctx.server()); health = husk.getHealth(); husk.invulnerableTime = 0; Ryuka.strike(ctx.player(), husk, Ryuka.DAMAGE); })
                    .checkServer("爆开：多打 30、印清零、淡粉的狐火缠身、可以处决", ctx -> {
                        var husk = husk(ctx.server());
                        return health - husk.getHealth() >= 38.9f && FoxMarks.count(husk) == 0 && FoxfireRules.burning(husk) == FoxfireFlame.SHIRAUME
                                && FoxMarks.executable(husk);
                    })
                    .ticks(2).screenshot("marks_burst")
                    .waitUntil("客户端看得出能处决", ctx -> FoxMarksClient.executable(target))
                    .ticks(14).screenshot("executable");
            finish(s);
        }
    }

    /**
     * 可交互处决：尸壳的狐火印爆开过，平一文字蓄满突进上挑，进处决特写；一刻点一下左键，每下都砍一刀，两端刀数对得上；重击、落地、镜头回来。
     * 最后在另一只尸壳上放一段“旁观”的处决，看世界里的刀光（单机只有一个客户端，旁观的样子只能这样在本机演一遍）。
     */
    @LDLRegisterClient(name = "ryuka_execution", group = "ryuka", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static final class Execution extends Base {
        private int clicks, serverSlashes, watched;
        private float lost;
        private long lastClick = -1;

        @Override public void define(ScenarioBuilder s) {
            arena(s)
                    .server("砍满五枚印爆开", ctx -> {
                        var husk = husk(ctx.server());
                        for (int i = 0; i < FoxMarks.FULL; i++) { husk.invulnerableTime = 0; Ryuka.strike(ctx.player(), husk, Ryuka.DAMAGE); }
                    })
                    .checkServer("爆开了、可以处决", ctx -> FoxMarks.executable(husk(ctx.server())))
                    .step("从背后看", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                    .step("按住右键格挡", ctx -> guard(true))
                    .waitUntil("格挡", ctx -> phase("guard"))
                    .step("按住左键蓄力", ctx -> attack(true))
                    .waitUntil("平一文字の構え", ctx -> phase("stance")).ticks(9)
                    .server("记录尸壳", ctx -> health = husk(ctx.server()).getHealth())
                    .step("松开左键突进", ctx -> attack(false))
                    .waitUntil("进处决特写", ctx -> ExecutionClient.active())
                    .checkServer("服务端在处决、尸壳被定住", ctx -> {
                        // 上挑那一刀（13.5）已经打过了，从这里开始记
                        health = husk(ctx.server()).getHealth();
                        return InteractiveExecutions.executing(ctx.player())
                                && dev.logos.dimension.execution.ExecutionShots.frozen(husk(ctx.server()))
                                && RyukaExecution.MOVE.equals(LongswordCombat.move(ctx.player()));
                    })
                    .step("松开右键", ctx -> guard(false))
                    .waitUntil("黑白、窗口打开", ctx -> ExecutionClient.clicking())
                    // 一刻点一下（两刀最短隔一刻，同一刻点两下只算一刀），一直点到窗口关上。场景每一步要花的刻数不固定，
                    // 所以点击放在一个每帧都查的等待里，按游戏刻点；截图靠把本机的特写停在某一刻上（服务端照常往下走）
                    .waitUntil("一刻一下点到窗口关上", ctx -> {
                        long now = Minecraft.getInstance().level.getGameTime();
                        if (ExecutionClient.clicking() && now != lastClick) {
                            lastClick = now;
                            ExecutionClient.click();
                            clicks++;
                        }
                        return !ExecutionClient.clicking();
                    })
                    .waitUntilServer("服务端结算完排队的刀", ctx -> {
                        serverSlashes = InteractiveExecutions.slashes(ctx.player());
                        return serverSlashes == ExecutionClient.slashes();
                    })
                    .check("点的都砍了（两边刀数一样、不多于点的次数）", ctx -> ExecutionClient.slashes() >= 10 && ExecutionClient.slashes() <= clicks)
                    .step("停在砍到一半", ctx -> ExecutionClient.hold(ExecutionTimeline.FIRST_SLASH + 18.5f)).ticks(2).screenshot("execution_slashes")
                    // 重击、落地各只有几拍，截图的步骤赶不上：把本机的特写停在那一刻上截（服务端照常往下走）
                    .step("停在重击", ctx -> ExecutionClient.hold(ExecutionTimeline.FIRST_SLASH + RyukaExecution.WINDOW + ExecutionTimeline.QUIET + 1.5f)).ticks(2)
                    .check("重击变红", ctx -> ExecutionClient.phase() == ExecutionTimeline.Phase.RED).screenshot("execution_red")
                    .step("停在落地", ctx -> ExecutionClient.hold(ExecutionTimeline.FIRST_SLASH + RyukaExecution.WINDOW + ExecutionTimeline.QUIET
                            + ExecutionTimeline.HIT_STOP + ExecutionTimeline.SLAM + .2f)).ticks(2)
                    .check("落地白一拍", ctx -> ExecutionClient.phase() == ExecutionTimeline.Phase.IMPACT).screenshot("execution_impact")
                    .step("放开", ctx -> ExecutionClient.hold(null))
                    .waitUntilServer("处决结束", ctx -> {
                        if (InteractiveExecutions.executing(ctx.player())) return false;
                        lost = health - husk(ctx.server()).getHealth();
                        return true;
                    })
                    .check("刀、重击、摔落都打进去了", ctx -> {
                        // 处决期间尸壳被定住，缠身不烧；结束的那一刻最多再烧 1 点
                        float expected = serverSlashes * RyukaExecution.SLASH + RyukaExecution.FINAL + RyukaExecution.FALL;
                        System.out.println("[ryuka-execution] slashes=" + serverSlashes + " lost=" + lost + " expected=" + expected);
                        return lost >= expected - .01f && lost <= expected + 1.01f;
                    })
                    .waitUntil("镜头回来", ctx -> !ExecutionClient.active())
                    .waitUntil("回到待机", ctx -> phase("idle")).ticks(2).screenshot("execution_after")
                    // ---- 旁观的人看到的：另一只尸壳在半空挨刀，身上闪过刀光
                    .server("第二只尸壳", ctx -> {
                        var level = ctx.server().overworld();
                        var husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                        husk.setNoAi(true);
                        husk.setPos(3.5, 100, 6.5);
                        level.addFreshEntity(husk);
                        watched = husk.getId();
                    }).ticks(4)
                    .step("看向它", ctx -> {
                        camera(CameraType.FIRST_PERSON);
                        var p = Minecraft.getInstance().player;
                        p.setYRot(-35); p.setXRot(-18);
                    })
                    .step("本机演一段旁观的处决", ctx -> ExecutionClient.start(new ExecutionPayload(watched,
                            ExecutionShot.of(9).live(RyukaExecution.WINDOW, RyukaExecution.TINT), 0, false)))
                    .ticks(10)
                    .step("一刀", ctx -> ExecutionClient.slash(new ExecutionPayload.Slash(watched, 0)))
                    .ticks(2)
                    .step("又一刀", ctx -> ExecutionClient.slash(new ExecutionPayload.Slash(watched, 0)))
                    .ticks(1).screenshot("watch_slashes")
                    .check("世界里画了刀光", ctx -> ExecutionSlashes.drawn() > 0)
                    .server("移除第二只", ctx -> {
                        var e = ctx.server().overworld().getEntity(watched);
                        if (e != null) e.discard();
                    });
            finish(s);
        }
    }
}
