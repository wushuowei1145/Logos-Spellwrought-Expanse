package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.explosion.ExplosionClient;
import dev.logos.dimension.ServerConfig;
import dev.logos.dimension.explosion.ExplosionMagic;
import dev.logos.dimension.explosion.ExplosionStaffItem;
import dev.logos.dimension.explosion.ExplosionTimeline;
import dev.logos.runtime.PlayerMagic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 爆裂魔法的开销：和 {@link ExplosionView} 同一个石台，从远处机位看一整次吟唱和爆炸，不截图、不停时钟，
 * 按阶段记下每帧的间隔（平均、99 分位、最慢），再取爆炸挖坑那几秒服务端每刻的耗时。日志里搜 {@code [explosion-bench]}。
 */
@LDLRegisterClient(name = "explosion_bench", group = "explosion_bench", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ExplosionBenchView implements UIScenario {
    private static boolean listening;
    private static String phase = "";
    private static long last;
    private static final Map<String, List<Long>> FRAMES = new LinkedHashMap<>();
    private int far = -1;
    private boolean previousHideGui;
    private int previousDistance;
    private net.minecraft.client.InactivityFpsLimit previousLimit = net.minecraft.client.InactivityFpsLimit.AFK;
    private int previousFps = 120;
    private boolean previousVsync;

    @Override public void configure(ScenarioOptions o) { o.tags("explosion").defaultTimeoutMs(90000).scenarioTimeoutMs(300000); }

    private static long lastGc;

    /** 进程里所有垃圾回收器到现在一共回收过几次。 */
    private static long gcCount() {
        long n = 0;
        for (var b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, b.getCollectionCount());
        return n;
    }

    /** 慢帧先记在这里，最后一起打印：在帧里写日志本身就会拖慢后面几帧。 */
    private static final List<String> SLOW = new ArrayList<>();

    private static void frame() {
        long now = Util.getNanos();
        long gc = gcCount();
        if (!phase.isEmpty() && last != 0) {
            long dt = now - last;
            FRAMES.computeIfAbsent(phase, k -> new ArrayList<>()).add(dt);
            // 慢的那几帧单独记下来：在哪一段、吟唱到哪、爆炸第几刻、这一帧里有没有跑过垃圾回收、区块网格还排着几个没建
            if (dt > 10_000_000L && SLOW.size() < 200) {
                var mc = Minecraft.getInstance();
                var sections = mc.levelRenderer.getSectionRenderDispatcher();
                SLOW.add(String.format("slow frame %.1f ms in %s (charge %s, blast age %.1f, chunk compiles queued %d, particles %s)%s", dt / 1e6, phase,
                        mc.player == null ? "-" : String.format("%.2f", ExplosionClient.progress(mc.player.getId())), ExplosionClient.blastAge(),
                        sections == null ? -1 : sections.getCompileQueueSize(), mc.particleEngine.countParticles(), gc != lastGc ? ", GC ran" : ""));
            }
        }
        last = now;
        lastGc = gc;
    }

    private static void report() {
        for (String s : SLOW) LogUtils.getLogger().info("[explosion-bench] {}", s);
        for (var e : FRAMES.entrySet()) {
            long[] t = e.getValue().stream().mapToLong(Long::longValue).sorted().toArray();
            if (t.length == 0) continue;
            double avg = Arrays.stream(t).average().orElse(0) / 1e6;
            double p99 = t[Math.min(t.length - 1, (int) (t.length * .99))] / 1e6, max = t[t.length - 1] / 1e6;
            LogUtils.getLogger().info("[explosion-bench] {}: {} frames, avg {} ms, p99 {} ms, max {} ms", e.getKey(), t.length,
                    String.format("%.2f", avg), String.format("%.2f", p99), String.format("%.2f", max));
        }
    }

    /** 爆炸进行到哪一段；{@code label} 区分破不破坏地形。 */
    private static String blastPhase(String label) {
        float age = ExplosionClient.blastAge();
        if (age < ExplosionTimeline.SPHERE_AT) return label + " pillar..flash";
        if (age < ExplosionTimeline.FULL_AT) return label + " sphere growing";
        if (age < ExplosionTimeline.GONE_AT) return label + " sphere fading";
        if (age < 240) return label + " smoke 162-240";
        return label + " smoke 240-320";
    }

    private static void blast(ScenarioBuilder s, String label) {
        for (int age = 2; age <= 320; age += 2) {
            int at = age;
            s.waitUntil(label + " 爆炸第 " + at + " 刻", ctx -> ExplosionClient.blastAge() >= at).step(label + " 阶段 " + at, ctx -> phase = blastPhase(label));
            if (at == ExplosionTimeline.FULL_AT + 4 || at == ExplosionTimeline.GONE_AT + 4) {
                s.server(label + " 服务端每刻耗时 " + at, ctx -> {
                    long[] ticks = ctx.server().getTickTimesNanos();
                    long max = Arrays.stream(ticks).max().orElse(0);
                    double avg = Arrays.stream(ticks).average().orElse(0);
                    LogUtils.getLogger().info("[explosion-bench] {} server ticks before age {}: avg {} ms, max {} ms", label, at,
                            String.format("%.2f", avg / 1e6), String.format("%.2f", max / 1e6));
                });
            }
        }
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL).runCommand("time set 6000").runCommand("weather clear").runCommand("difficulty easy")
                .runCommand("gamerule spawn_mobs false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    previousHideGui = mc.options.hideGui;
                    previousDistance = mc.options.renderDistance().get();
                    mc.options.hideGui = true;
                    mc.options.renderDistance().set(16);
                    mc.options.broadcastOptions();
                    mc.player.getInventory().setSelectedSlot(0);
                    // 原版在一分钟没有输入后把帧率限到 30：测帧时间时关掉，只在最小化时限
                    previousLimit = mc.options.inactivityFpsLimit().get();
                    mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                    // 不限帧率、不等垂直同步：量出的才是每帧真正花的时间，而不是一直贴着上限
                    previousFps = mc.options.framerateLimit().get();
                    previousVsync = mc.options.enableVsync().get();
                    mc.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
                    mc.options.enableVsync().set(false);
                    mc.getWindow().updateVsync(false);
                    mc.options.fov().set(70);
                    ExplosionClient.clear();
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    if (!listening) {
                        listening = true;
                        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> frame());
                    }
                    FRAMES.clear();
                    SLOW.clear();
                    phase = "";
                })
                .server("视距", ctx -> ctx.server().getPlayerList().setViewDistance(16))
                .server("搭石台", ctx -> {
                    ExplosionView.stage(ctx.server().overworld());
                    // 第一次不破坏地形，只看特效本身；第二次破坏地形，看挖坑和重发区块的额外开销
                    ServerConfig.EXPLOSION_BREAKS_TERRAIN.set(false);
                })
                .serverTicks(80)
                .waitUntil("石垫到了客户端", ctx -> Minecraft.getInstance().level.getBlockState(new BlockPos(0, ExplosionView.TOP, 0)).is(Blocks.GRASS_BLOCK))
                .server("站到石垫上", ctx -> {
                    DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), ExplosionView.CASTER, 0, .97f);
                    far = ExplosionView.camera(ctx.server().overworld(), new Vec3(-110, ExplosionView.TOP + 30, 20), new Vec3(0, ExplosionView.TOP + 26, 96));
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ExplosionStaffItem.ITEM.get()));
                    var magic = PlayerMagic.of(ctx.player());
                    magic.maxMana = magic.mana = 10000;
                })
                .waitUntil("站稳", ctx -> Minecraft.getInstance().player.getY() > ExplosionView.TOP && Minecraft.getInstance().player.onGround())
                .waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(far) != null)
                .step("远处机位", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().level.getEntity(far)))
                .ticks(40)
                .step("记平时", ctx -> phase = "idle")
                .ticks(100)
                .step("按住右键", ctx -> {
                    var mc = Minecraft.getInstance();
                    phase = "charge";
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntil("蓄满", ctx -> ExplosionClient.progress(Minecraft.getInstance().player.getId()) >= 1)
                .step("松开右键", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(false);
                    mc.gameMode.releaseUsingItem(mc.player);
                })
                .waitUntil("爆炸开始", ctx -> ExplosionClient.blasts() > 0)
                .step("记爆炸", ctx -> phase = blastPhase("no terrain"));
        blast(s, "no terrain");
        s.step("收掉第一次", ctx -> {
                    phase = "";
                    ExplosionClient.clear();
                })
                .server("第二次：破坏地形", ctx -> {
                    ServerConfig.EXPLOSION_BREAKS_TERRAIN.set(true);
                    ExplosionMagic.fire(ctx.server().overworld(), ExplosionView.TARGET, ctx.player());
                })
                .waitUntil("第二次爆炸开始", ctx -> ExplosionClient.blasts() > 0)
                .step("记第二次", ctx -> phase = blastPhase("terrain"));
        blast(s, "terrain");
        s.step("报告", ctx -> {
                    phase = "";
                    report();
                })
                .teardownServer("恢复配置", ctx -> ServerConfig.EXPLOSION_BREAKS_TERRAIN.set(true))
                .teardown("恢复", ctx -> {
                    var mc = Minecraft.getInstance();
                    phase = "";
                    mc.options.keyUse.setDown(false);
                    mc.setCameraEntity(mc.player);
                    mc.options.hideGui = previousHideGui;
                    mc.options.renderDistance().set(previousDistance);
                    mc.options.inactivityFpsLimit().set(previousLimit);
                    mc.options.framerateLimit().set(previousFps);
                    mc.options.enableVsync().set(previousVsync);
                    mc.getWindow().updateVsync(previousVsync);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                });
    }
}
