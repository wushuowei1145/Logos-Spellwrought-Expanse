package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.npc.NpcEntities;
import dev.logos.dimension.npc.NpcParts;
import java.util.Arrays;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 神狐渲染基准（实际客户端）：不限帧率，依次量空场地、20 只站着不动、20 只开 AI、50 只开 AI、50 只站着不动，
 * 把帧时间打到日志（{@code [fox-bench]}）。外观固定。只报数，不判定快慢：结果取决于机器。对比时用同一台机器、同一视角。
 */
@LDLRegisterClient(name = "npc_fox_render_bench", group = "fox_bench", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class FoxRenderBench implements UIScenario {
    private static final int COUNT = 20, MORE = 30, WALL_Z = 17;
    private static final long[] FRAMES = new long[20_000];
    private static int frames;
    private static long lastFrame;
    private static boolean recording, listening;

    private static void frame(RenderFrameEvent.Post event) {
        if (!recording) return;
        long now = System.nanoTime();
        if (lastFrame != 0 && frames < FRAMES.length) FRAMES[frames++] = now - lastFrame;
        lastFrame = now;
    }

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("fox_bench").defaultTimeoutMs(240_000).scenarioTimeoutMs(600_000);
    }

    /** 量 {@code ticks} 刻。墙上时钟也打出来，方便从 JFR 录制里截这一段。 */
    private static ScenarioBuilder measure(ScenarioBuilder s, String label, int ticks) {
        return s.step("开始量：" + label, ctx -> {
                    frames = 0;
                    lastFrame = 0;
                    recording = true;
                    NpcMesh.QUADS[0] = NpcMesh.QUADS[1] = 0;
                    System.out.println("[fox-bench] " + label + " start " + System.currentTimeMillis());
                })
                .ticks(ticks)
                .step("结束量：" + label, ctx -> {
                    recording = false;
                    long[] sorted = Arrays.copyOf(FRAMES, frames);
                    Arrays.sort(sorted);
                    double total = Arrays.stream(sorted).sum() / 1e6;
                    System.out.printf("[fox-bench] %s end %d frames=%d avg=%.2fms p50=%.2fms p95=%.2fms p99=%.2fms quads main=%d shadow=%d%n", label, System.currentTimeMillis(), frames,
                            total / Math.max(1, frames), ms(sorted, .5), ms(sorted, .95), ms(sorted, .99), NpcMesh.QUADS[0] / Math.max(1, frames), NpcMesh.QUADS[1] / Math.max(1, frames));
                });
    }

    private static double ms(long[] sorted, double q) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, sorted.length * q)] / 1e6;
    }

    /** 同一个带旋转和不等比缩放的姿势下，网格的顶点、贴图坐标和法线都要和 GeckoLib 一样，批量写入的字节也要和逐顶点写一样。 */
    private static boolean sameAsGeckoLib(EntityType<?> type) {
        var mc = Minecraft.getInstance();
        if (!(mc.getEntityRenderDispatcher().getRenderer(type.create(mc.level, EntitySpawnReason.COMMAND)) instanceof NpcRenderer<?, ?> renderer)) {
            System.out.println("[fox-bench] 没有找到 NPC 渲染器：" + type);
            return false;
        }
        NpcMesh mesh = renderer.defaultMesh();
        int[] counts = mesh.cubeCounts();
        System.out.printf("[fox-bench] %s 面 %d，封闭且不透明、可跳过背面 %d（%.0f%%）%n", type, counts[0], counts[1], 100.0 * counts[1] / Math.max(1, counts[0]));
        PoseStack pose = new PoseStack();
        pose.translate(1.5f, -2, .25f);
        pose.mulPose(new Quaternionf().rotationXYZ(.3f, 1.1f, -.7f));
        pose.scale(.5f, .5f, .75f);
        String difference = mesh.compareWithGeckoLib(pose.last());
        if (difference == null) difference = mesh.compareBulkWrite(pose.last());
        if (difference != null) System.out.println("[fox-bench] 不一致：" + difference);
        return difference == null;
    }

    @Override
    public void define(ScenarioBuilder s) {
        // 跑场景时才挂监听：这个类依赖开发环境才有的测试框架，正式游戏里不能被 @EventBusSubscriber 提前加载
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener(FoxRenderBench::frame);
        }
        FoxCombatViews.begin(s)
                .step("不限帧率", ctx -> {
                    var options = Minecraft.getInstance().options;
                    options.framerateLimit().set(260);
                    options.enableVsync().set(false);
                    options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
                    options.renderDistance().set(8);
                    Minecraft.getInstance().getWindow().updateVsync(false);
                })
                .server("白瓷台", ctx -> {
                    var level = ctx.server().overworld();
                    FoxCombatViews.arena(level);
                    // 围一圈屏障，开 AI 之后别走下白瓷台
                    for (int i = -15; i <= 16; i++) {
                        for (int y = FoxCombatViews.FLOOR + 1; y <= FoxCombatViews.FLOOR + 3; y++) {
                            level.setBlockAndUpdate(new BlockPos(i, y, -15), Blocks.BARRIER.defaultBlockState());
                            level.setBlockAndUpdate(new BlockPos(i, y, 16), Blocks.BARRIER.defaultBlockState());
                            level.setBlockAndUpdate(new BlockPos(-15, y, i), Blocks.BARRIER.defaultBlockState());
                            level.setBlockAndUpdate(new BlockPos(16, y, i), Blocks.BARRIER.defaultBlockState());
                        }
                    }
                    FoxCombatViews.camera(ctx.player(), new Vec3(0, FoxCombatViews.FLOOR + 11, 19), new Vec3(0, FoxCombatViews.FLOOR + 1, -1));
                })
                .ticks(100);
        measure(s, "empty", 200)
                .server("20 只神狐", ctx -> {
                    var level = ctx.server().overworld();
                    for (int i = 0; i < COUNT; i++) {
                        var fox = FoxCombatViews.fox(level, -8 + i % 5 * 4 + .5, -6 + i / 5 * 4 + .5, NpcParts.DIVINE_FOX.defaults().get("outfit"), false);
                        fox.setAppearance(NpcParts.DIVINE_FOX.random(RandomSource.create(1000 + i)));
                    }
                })
                .waitUntil("客户端看到 20 只", ctx -> FoxCombatViews.foxes().size() >= COUNT)
                .ticks(100);
        // 第三排中间那只抬到半空，好从下面看裙内、从背后看尾巴；定住时间，两次运行的截图才好比较
        double liftedX = .5, liftedZ = 6.5, liftedY = FoxCombatViews.FLOOR + 2.4;
        measure(s, "foxes", 300)
                .check("实际渲染走批量写入", ctx -> NpcMesh.bulkWrites() > 0)
                .screenshot("fox_render_bench")
                .server("抬起一只", ctx -> FoxCombatViews.serverFoxes(ctx.server().overworld()).stream()
                        .filter(f -> Math.abs(f.getX() - liftedX) < .1 && Math.abs(f.getZ() - liftedZ) < .1)
                        .forEach(f -> {
                            f.setNoGravity(true);
                            f.setPos(liftedX, liftedY, liftedZ);
                        }))
                .ticks(10)
                .runCommand("tick freeze")
                .server("近景", ctx -> FoxCombatViews.camera(ctx.player(), new Vec3(-5.5, FoxCombatViews.FLOOR + 2.2, 9.5), new Vec3(-5.5, FoxCombatViews.FLOOR + 1.1, 6.5)))
                .ticks(40)
                .screenshot("fox_render_closeup")
                .server("从下往上看裙内", ctx -> FoxCombatViews.camera(ctx.player(), new Vec3(liftedX, FoxCombatViews.FLOOR + 1.75, liftedZ + 1.7), new Vec3(liftedX, liftedY + .4, liftedZ)))
                .ticks(40)
                .screenshot("fox_render_below")
                .server("从背后看尾巴", ctx -> FoxCombatViews.camera(ctx.player(), new Vec3(liftedX, liftedY + 1.2, liftedZ - 2), new Vec3(liftedX, liftedY + .8, liftedZ)))
                .ticks(40)
                .screenshot("fox_render_back")
                .check("预算好的网格和 GeckoLib 逐顶点一致", ctx -> sameAsGeckoLib(NpcEntities.DIVINE_FOX.get()) && sameAsGeckoLib(NpcEntities.FOREST_ELF.get()))
                .runCommand("tick unfreeze")
                .server("回到全景，打开 AI", ctx -> {
                    FoxCombatViews.camera(ctx.player(), new Vec3(0, FoxCombatViews.FLOOR + 11, 19), new Vec3(0, FoxCombatViews.FLOOR + 1, -1));
                    FoxCombatViews.serverFoxes(ctx.server().overworld()).forEach(f -> f.setNoAi(false));
                })
                .ticks(60);
        measure(s, "foxes_ai", 200)
                .server("再放 30 只，一共 50 只，都开着 AI", ctx -> {
                    var level = ctx.server().overworld();
                    for (int i = 0; i < MORE; i++) {
                        var fox = FoxCombatViews.fox(level, -12 + i % 6 * 5 + 1, -12 + i / 6 * 5 + 1, NpcParts.DIVINE_FOX.defaults().get("outfit"), true);
                        fox.setAppearance(NpcParts.DIVINE_FOX.random(RandomSource.create(2000 + i)));
                    }
                })
                .waitUntil("客户端看到 50 只", ctx -> FoxCombatViews.foxes().size() >= COUNT + MORE)
                .ticks(60);
        measure(s, "foxes50_ai", 200)
                .server("50 只都站住", ctx -> FoxCombatViews.serverFoxes(ctx.server().overworld()).forEach(f -> f.setNoAi(true)))
                .ticks(40);
        measure(s, "foxes50", 200)
                .screenshot("fox_render_50")
                .runCommand("tick freeze");
        // 同一个定住的画面，跳过小面前后各拍一张，对比远处和中等距离少了什么。原版 NPC 约 57 格外就不画了，所以最远只看到这里
        lodPair(s, "far", new Vec3(0, FoxCombatViews.FLOOR + 15, 38), new Vec3(0, FoxCombatViews.FLOOR + 1, 0));
        lodPair(s, "mid", new Vec3(0, FoxCombatViews.FLOOR + 6, 23), new Vec3(0, FoxCombatViews.FLOOR + 1, 4));
        s.runCommand("tick unfreeze")
                .server("一堵墙挡在镜头前面", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -45; x <= 45; x++) {
                        for (int y = FoxCombatViews.FLOOR; y <= FoxCombatViews.FLOOR + 45; y++) {
                            level.setBlock(new BlockPos(x, y, WALL_Z), Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                        }
                    }
                    FoxCombatViews.camera(ctx.player(), new Vec3(0, FoxCombatViews.FLOOR + 11, 19), new Vec3(0, FoxCombatViews.FLOOR + 1, -1));
                })
                .ticks(60);
        measure(s, "occluded", 100)
                .check("被墙完全挡住的神狐主通道不写顶点", ctx -> NpcMesh.QUADS[0] == 0)
                .server("开一格窗", ctx -> ctx.server().overworld().setBlock(new BlockPos(0, FoxCombatViews.FLOOR + 10, WALL_Z), Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS))
                .ticks(10);
        measure(s, "window", 40)
                .check("从一格宽的窗户看得见里面的神狐", ctx -> NpcMesh.QUADS[0] > 0)
                .screenshot("fox_render_window")
                .teardown("恢复界面", ctx -> {
                    NpcMesh.skipSmallFaces = true;
                    Minecraft.getInstance().options.hideGui = false;
                });
    }

    /** 同一个画面，先拍跳过小面的，再拍不跳过的。 */
    private static void lodPair(ScenarioBuilder s, String name, Vec3 eye, Vec3 look) {
        s.server("镜头：" + name, ctx -> FoxCombatViews.camera(ctx.player(), eye, look))
                .ticks(60)
                .screenshot("fox_lod_" + name + "_on")
                .step("不跳过小面", ctx -> NpcMesh.skipSmallFaces = false)
                .ticks(60)
                .screenshot("fox_lod_" + name + "_off")
                .step("跳过小面", ctx -> NpcMesh.skipSmallFaces = true);
    }
}
