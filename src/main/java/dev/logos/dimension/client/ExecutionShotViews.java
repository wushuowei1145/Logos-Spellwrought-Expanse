package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.execution.ExecutionClient;
import dev.logos.dimension.client.execution.ExecutionRenderer;
import dev.logos.dimension.execution.ExecutionShot;
import dev.logos.dimension.execution.ExecutionShots;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.execution.ExecutionTimeline.Phase;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 处决特写的实际客户端检查，分组 {@code execution_shot}。要在不开光影、只装 Sodium、Iris 加两种光影包下各跑一遍：
 * 剪影靠深度还原，光影包会接管深度，最容易出问题。截图时把特写停在某一刻上（{@link ExecutionClient#hold}），
 * 时长和顺序另外按帧记录真放一次来查。
 */
public abstract class ExecutionShotViews implements UIScenario {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int GROUND = 100;
    private static final Vec3 FEET = new Vec3(.5, GROUND + 1, .5);
    /** 截图用的固定种子：刀的方向、机位每次都一样。 */
    static final int SEED = 7;

    @Override public void configure(ScenarioOptions o) {
        o.tags("execution_shot").defaultTimeoutMs(30000).scenarioTimeoutMs(150000);
    }

    /** 一片草地，几根彩色柱子、一棵树当参照：原画那几拍看得出镜头在动，黑白时它们全变成背景。 */
    private static void stage(ServerLevel level) {
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) {
            level.setBlockAndUpdate(new BlockPos(x, GROUND, z), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 1; y <= 14; y++) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, z), Blocks.AIR.defaultBlockState());
        }
        var pillars = new net.minecraft.world.level.block.Block[] {Blocks.RED_WOOL, Blocks.BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL};
        for (int i = 0; i < 4; i++) for (int y = 1; y <= 4; y++) {
            level.setBlockAndUpdate(new BlockPos(i % 2 == 0 ? -6 : 6, GROUND + y, i < 2 ? -6 : 7), pillars[i].defaultBlockState());
        }
        for (int y = 1; y <= 5; y++) level.setBlockAndUpdate(new BlockPos(-3, GROUND + y, 9), Blocks.OAK_LOG.defaultBlockState());
        for (int x = -5; x <= -1; x++) for (int z = 7; z <= 11; z++) for (int y = 5; y <= 7; y++) {
            if (level.getBlockState(new BlockPos(x, GROUND + y, z)).isAir()) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, z), Blocks.OAK_LEAVES.defaultBlockState());
        }
    }

    protected void arrive(ScenarioBuilder s, GameType mode) {
        s.setGameMode(mode).runCommand("time set 6000").runCommand("weather clear").runCommand("difficulty easy")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    mc.player.getInventory().setSelectedSlot(0);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.fov().set(70);
                    mc.options.hideLightningFlash().set(false);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                })
                .server("搭场地", ctx -> {
                    var level = ctx.server().overworld();
                    level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MONSTERS, false, ctx.server());
                    stage(level);
                    DimensionTravel.teleport(ctx.player(), level, FEET, 0, 10);
                    ctx.player().getInventory().clearContent();
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                })
                .waitUntil("到位", ctx -> {
                    var mc = Minecraft.getInstance();
                    return mc.player.position().distanceTo(FEET) < .01 && mc.player.onGround();
                })
                .ticks(20);
    }

    protected void cleanup(ScenarioBuilder s) {
        s.teardown("恢复", ctx -> {
            var mc = Minecraft.getInstance();
            ExecutionClient.hold(null);
            mc.options.hideLightningFlash().set(false);
            mc.options.keyUp.setDown(false);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }

    /** 停在第 {@code age} 刻上截一张。 */
    protected static void still(ScenarioBuilder s, String name, float age) {
        s.step("停在 " + name, ctx -> ExecutionClient.hold(age)).ticks(3).screenshot(name);
    }

    /** 特写结束之后一切都还给玩家了。 */
    protected static void restored(TestContext ctx) {
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        ctx.check("特写结束", !ExecutionClient.active() && !CameraDirector.active());
        ctx.check("身体回到原样", !ExecutionClient.posing(mc.player));
        ctx.check("视角回到第一人称", mc.options.getCameraType() == CameraType.FIRST_PERSON);
        ctx.check("操作已交还", !CameraDirector.locked());
        ctx.check("镜头回到玩家眼睛", camera.position().distanceTo(mc.player.getEyePosition()) < .01);
    }

    /** 处决自己：每个阶段停一拍截图，再放开让它走完，镜头、身体、界面都要还回来。 */
    @LDLRegisterClient(name = "execution_shot_stills", group = "execution_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Stills extends ExecutionShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, GameType.SURVIVAL);
            var shot = ExecutionShot.of(SEED);
            var t = new ExecutionTimeline(shot);
            long[] passes = new long[1];
            s.screenshot("before");
            s.server("处决自己", ctx -> ExecutionShots.play(ctx.player(), ctx.player(), shot));
            s.waitUntil("特写开始", ctx -> ExecutionClient.active()).step("停住", ctx -> {
                ExecutionClient.hold(0f);
                passes[0] = ExecutionRenderer.passes();
            });
            still(s, "launch", 2.5f);
            still(s, "ink", ExecutionTimeline.INK + 1.5f);
            var slashes = t.slashes();
            still(s, "slash_spark", slashes.get(2).contact());
            s.step("不在节拍上的一刀", ctx -> {
                ctx.check("不在节拍上的刀不对调", !ExecutionClient.swapped());
                ctx.check("这一拍是黑白", ExecutionClient.phase() == Phase.INK);
                ctx.check("全屏通道在画", ExecutionRenderer.passes() > passes[0]);
                var mc = Minecraft.getInstance();
                ctx.check("镜头离开了玩家", CameraDirector.weight() == 1 && mc.gameRenderer.getMainCamera().isDetached());
                ctx.check("身体按特写摆", ExecutionClient.posing(mc.player));
                ctx.check("操作被锁住", CameraDirector.locked());
            });
            still(s, "slash_spark_next", slashes.get(2).contact() + ExecutionTimeline.FRAME);
            still(s, "beat_swap", slashes.get(t.beats().get(1)).contact());
            s.step("节拍上的一刀", ctx -> ctx.check("节拍上的刀对调", ExecutionClient.swapped()));
            still(s, "beat_after", slashes.get(t.beats().get(1)).contact() + ExecutionTimeline.FRAME);
            s.step("下一拍换回来", ctx -> ctx.check("下一拍换回来了", !ExecutionClient.swapped()));
            still(s, "sliced", slashes.get(10).contact() + ExecutionTimeline.FRAME);
            still(s, "quiet", t.finalHit() - 1.5f);
            still(s, "final_hit", t.finalHit() + .2f);
            s.step("重击是红的", ctx -> ctx.check("重击后背景变红", ExecutionClient.phase() == Phase.RED));
            still(s, "final_hold", t.finalHit() + 3);
            still(s, "slam", t.slamStart() + 1.4f);
            still(s, "impact", t.impact() + .2f);
            s.step("落地一拍", ctx -> ctx.check("落地那一拍全白", ExecutionClient.phase() == Phase.IMPACT));
            still(s, "landed", t.impact() + 2);
            still(s, "returning", t.returnAt() + 6);
            s.step("放开", ctx -> ExecutionClient.hold(null));
            s.waitUntil("特写结束", ctx -> !ExecutionClient.active() && !CameraDirector.active()).ticks(5);
            s.screenshot("after");
            s.step("检查收尾", ExecutionShotViews::restored);
            cleanup(s);
        }
    }

    /** 第一版（每刀对调）原样保留：用命令选它，每刀都对调，没有火花和错位。 */
    @LDLRegisterClient(name = "execution_shot_every", group = "execution_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Every extends ExecutionShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, GameType.SURVIVAL);
            var t = new ExecutionTimeline(ExecutionShot.of(0));
            s.step("输入命令", ctx -> Minecraft.getInstance().player.connection.sendCommand("alchemy execution @s 12 every"));
            s.waitUntil("特写开始", ctx -> ExecutionClient.active());
            for (int i : new int[] {2, 3}) {
                still(s, "every_" + i, t.slashes().get(i).contact());
                s.step("第 " + i + " 刀对调", ctx -> ctx.check("每刀都对调", ExecutionClient.swapped()));
            }
            still(s, "every_quiet", t.finalHit() - 1.5f);
            s.step("放开", ctx -> ExecutionClient.hold(null));
            s.waitUntil("特写结束", ctx -> !ExecutionClient.active() && !CameraDirector.active()).ticks(5);
            s.step("检查收尾", ExecutionShotViews::restored);
            cleanup(s);
        }
    }

    /** 用命令处决一只会走动的僵尸（戴着头盔、拿着剑）：它被定住，人形的四肢和盔甲跟着甩，结束后照常走。 */
    @LDLRegisterClient(name = "execution_shot_mob", group = "execution_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Mob extends ExecutionShotViews {
        private int zombie;
        private Vec3 at = Vec3.ZERO;
        private double drift;

        @Override public void define(ScenarioBuilder s) {
            arrive(s, GameType.CREATIVE);
            s.server("放一只僵尸", ctx -> {
                ServerLevel level = ctx.server().overworld();
                var e = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                e.snapTo(.5, GROUND + 1, 5.5, 180, 0);
                e.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                e.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                e.setPersistenceRequired();
                level.addFreshEntity(e);
                zombie = e.getId();
            }).ticks(10);
            s.step("输入命令", ctx -> Minecraft.getInstance().player.connection.sendCommand("alchemy execution @e[type=minecraft:zombie,limit=1,sort=nearest] 10"));
            s.waitUntil("特写开始", ctx -> ExecutionClient.active());
            s.server("记下位置", ctx -> {
                var e = ctx.server().overworld().getEntity(zombie);
                at = e.position();
                ctx.check("服务端定住了僵尸", ExecutionShots.frozen(e));
            });
            s.step("停在黑白", ctx -> ExecutionClient.hold(ExecutionTimeline.INK + 1.5f)).ticks(3).screenshot("mob_ink");
            s.step("僵尸按特写摆", ctx -> {
                var e = Minecraft.getInstance().level.getEntity(zombie);
                ctx.check("僵尸的身体按特写摆", e != null && ExecutionClient.posing(e));
                ctx.check("自己的身体不受影响", !ExecutionClient.posing(Minecraft.getInstance().player));
            });
            s.step("停在第三刀", ctx -> ExecutionClient.hold(new ExecutionTimeline(ExecutionShot.of(0).slashes(10)).slashes().get(3).contact() + ExecutionTimeline.FRAME))
                    .ticks(3).screenshot("mob_slash");
            s.step("放开", ctx -> ExecutionClient.hold(null));
            s.waitUntilServer("走完前一直站着", ctx -> {
                var e = ctx.server().overworld().getEntity(zombie);
                drift = Math.max(drift, e.position().distanceTo(at));
                return !ExecutionShots.frozen(e);
            });
            s.step("检查", ctx -> ctx.check("特写期间僵尸没有动", drift < .01));
            s.waitUntil("特写结束", ctx -> !ExecutionClient.active() && !CameraDirector.active()).ticks(5);
            s.step("检查收尾", ctx -> {
                restored(ctx);
                var mc = Minecraft.getInstance();
                var e = mc.level.getEntity(zombie);
                ctx.check("僵尸的身体回到原样", e != null && !ExecutionClient.posing(e));
            });
            s.server("清场", ctx -> {
                var e = ctx.server().overworld().getEntity(zombie);
                if (e != null) e.discard();
            });
            cleanup(s);
        }
    }

    /** 真放一次：按帧记下阶段和对调，顺序、刀数、时长都要对；打开「隐藏闪电的闪烁」后不对调、落地不白。 */
    @LDLRegisterClient(name = "execution_shot_timing", group = "execution_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Timing extends ExecutionShotViews {
        /** 每个渲染帧的时间、阶段和这一拍是否对调。 */
        public static final class Frames {
            final List<Long> at = new ArrayList<>();
            final List<Phase> phases = new ArrayList<>();
            final List<Boolean> swaps = new ArrayList<>();

            @SubscribeEvent public void frame(RenderFrameEvent.Post event) {
                at.add(System.nanoTime());
                phases.add(ExecutionClient.phase());
                swaps.add(ExecutionClient.swapped());
            }

            void clear() {
                at.clear();
                phases.clear();
                swaps.clear();
            }

            /** 去掉连续重复之后的阶段序列。 */
            List<Phase> sequence() {
                var out = new ArrayList<Phase>();
                for (var p : phases) if (p != null && (out.isEmpty() || out.getLast() != p)) out.add(p);
                return out;
            }

            /** 每次对调开始的时间（连着的几帧算一次）。 */
            List<Long> swapStarts() {
                var out = new ArrayList<Long>();
                for (int i = 0; i < swaps.size(); i++) if (swaps.get(i) && (i == 0 || !swaps.get(i - 1))) out.add(at.get(i));
                return out;
            }

            int swapRuns() {
                return swapStarts().size();
            }

            /** 任何一秒里最多对调了几次。 */
            int worstSecond() {
                var starts = swapStarts();
                int worst = 0;
                for (int i = 0; i < starts.size(); i++) {
                    int n = 0;
                    for (int j = i; j < starts.size() && starts.get(j) - starts.get(i) < 1_000_000_000L; j++) n++;
                    worst = Math.max(worst, n);
                }
                return worst;
            }

            /** 从开始到落地多少秒。 */
            double secondsToImpact() {
                long first = -1, impact = -1;
                for (int i = 0; i < phases.size(); i++) {
                    if (phases.get(i) != null && first < 0) first = at.get(i);
                    if (phases.get(i) == Phase.IMPACT && impact < 0) impact = at.get(i);
                }
                return (impact - first) / 1e9;
            }
        }

        private final Frames log = new Frames();

        @Override public void define(ScenarioBuilder s) {
            arrive(s, GameType.SURVIVAL);
            var shot = ExecutionShot.of(SEED);
            s.step("开始记录", ctx -> NeoForge.EVENT_BUS.register(log));
            s.server("处决自己", ctx -> ExecutionShots.play(ctx.player(), ctx.player(), shot));
            s.waitUntil("特写开始", ctx -> ExecutionClient.active());
            s.step("按住前进", ctx -> Minecraft.getInstance().options.keyUp.setDown(true));
            s.waitUntil("到重击", ctx -> ExecutionClient.phase() == Phase.RED);
            s.step("按住前进也不动", ctx -> {
                var mc = Minecraft.getInstance();
                ctx.check("按住前进也没有走", mc.player.position().distanceTo(FEET) < .01);
                mc.options.keyUp.setDown(false);
            });
            s.waitUntil("特写结束", ctx -> !ExecutionClient.active() && !CameraDirector.active()).ticks(5);
            s.step("检查这一次", ctx -> {
                NeoForge.EVENT_BUS.unregister(log);
                LOG.info("[execution] beats: sequence {}, {} swaps (at most {} in a second), impact after {} s over {} frames", log.sequence(), log.swapRuns(), log.worstSecond(), log.secondsToImpact(), log.phases.size());
                ctx.check("阶段依次出现", log.sequence().equals(List.of(Phase.LAUNCH, Phase.INK, Phase.RED, Phase.IMPACT, Phase.AFTER)));
                ctx.check("只在三个节拍上对调", log.swapRuns() == 3);
                ctx.check("任何一秒里不超过三次", log.worstSecond() <= 3);
                ctx.check("约两秒落地", log.secondsToImpact() > 1.6 && log.secondsToImpact() < 2.6);
                restored(ctx);
            });
            s.step("改成每刀对调", ctx -> {
                log.clear();
                NeoForge.EVENT_BUS.register(log);
            });
            s.server("每刀对调地处决一次", ctx -> ExecutionShots.play(ctx.player(), ctx.player(), shot.swaps(ExecutionShot.Swaps.EVERY)));
            s.waitUntil("特写开始", ctx -> ExecutionClient.active());
            s.waitUntil("特写结束", ctx -> !ExecutionClient.active() && !CameraDirector.active()).ticks(5);
            s.step("检查每刀对调", ctx -> {
                NeoForge.EVENT_BUS.unregister(log);
                LOG.info("[execution] every: {} swaps (at most {} in a second)", log.swapRuns(), log.worstSecond());
                ctx.check("第一版十二刀各对调一次", log.swapRuns() == 12);
                restored(ctx);
            });
            s.step("打开隐藏闪烁的选项", ctx -> {
                Minecraft.getInstance().options.hideLightningFlash().set(true);
                log.clear();
                NeoForge.EVENT_BUS.register(log);
            });
            s.server("再处决一次", ctx -> ExecutionShots.play(ctx.player(), ctx.player(), shot));
            s.waitUntil("特写开始", ctx -> ExecutionClient.active());
            s.waitUntil("特写结束", ctx -> !ExecutionClient.active() && !CameraDirector.active()).ticks(5);
            s.step("检查第二次", ctx -> {
                NeoForge.EVENT_BUS.unregister(log);
                Minecraft.getInstance().options.hideLightningFlash().set(false);
                ctx.check("隐藏闪烁时不对调", log.swapRuns() == 0);
                ctx.check("仍然有黑白和重击", log.sequence().contains(Phase.INK) && log.sequence().contains(Phase.RED));
                restored(ctx);
            });
            cleanup(s);
        }
    }
}
