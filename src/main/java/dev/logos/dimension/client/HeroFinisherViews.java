package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.cutin.ChainCutinRenderer;
import dev.logos.dimension.client.hero.HeroFinisherScreen;
import dev.logos.dimension.client.hero.HeroFinisherShow;
import dev.logos.dimension.client.hero.VowLockMarker;
import dev.logos.dimension.client.ultimate.UltimateClient;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.ultimate.HeroFinisherTimeline;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.Ultimates;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;

/**
 * 实际客户端检查玩家空手的最后一击「星穹誓约 · 羁绊焚天炮」（docs/ULTIMATES.md 第六节），一组 {@code hero_finisher}。
 * 白瓷台上两位同伴放完连携、切入换场进汇流以后按奥义键（默认 C）：
 * <ul>
 * <li>{@code hero_finisher_film}：靶子头顶有誓约锁定的星芒；最后一击打中锁住的靶子，游戏放慢到每秒 2 刻（截一张图要一百多毫秒，慢一点才每刻都截得到），从下令起每刻截一张
 *     （{@code hero_finisher_frame_NNN}），拿 tools/frames_to_gif.py 拼成动图看节奏和运镜；全黑起手画了；镜头一直没塞进方块；放完回到玩家视角；</li>
 * <li>{@code hero_finisher_no_target}：没有靶子，朝准星前方的地面放完整段，不卡死、回到常态；</li>
 * <li>{@code hero_finisher_cave}：台子罩在一间矮石屋里，极远机位退不出去时改洞内退距，镜头不穿进墙和屋顶。</li>
 * </ul>
 */
public abstract class HeroFinisherViews extends UltimateViews {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** 镜头每一帧落在方块里的次数、极远镜那几刻看到的机位。 */
    static int buried;
    static @Nullable Vec3 farFrom;
    static boolean farCave;
    static float health1;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("hero_finisher").defaultTimeoutMs(60_000).scenarioTimeoutMs(420_000);
    }

    /** 两位同伴（靶子可选），放完连携，切入放完、进汇流。 */
    static ScenarioBuilder converge(ScenarioBuilder s, boolean withFoe) {
        return ultimateStage(s).server(withFoe ? "两位同伴、一个靶子" : "两位同伴，没有靶子", ctx -> {
                    call(ctx.player(), 2);
                    if (withFoe) spawnFoe(ctx.player());
                    else removeFoe();
                })
                .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                .server("白梅先放", ctx -> Ultimates.order(ctx.player(), ""))
                .waitUntilServer("白梅演完", ctx -> !performing(ctx.player(), 0) && Ultimates.released(ctx.player()).size() == 1)
                .server("精灵公主接上", ctx -> Ultimates.order(ctx.player(), ""))
                .waitUntilServer("凑齐、切入换场进汇流", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.CONVERGED)
                .waitUntil("切入放完", ctx -> !ChainCutinRenderer.active())
                .waitUntil("客户端在汇流", ctx -> UltimateClient.status().chainPhase() == UltimateChain.Phase.CONVERGED);
    }

    /** 按奥义键放最后一击，等本机的过场开始。 */
    static ScenarioBuilder finisher(ScenarioBuilder s) {
        s.step("清零镜头的记录", ctx -> {
            buried = 0;
            farFrom = null;
        });
        // 按下去一直按着，直到过场开始：按下的那一刻客户端就把令发出去了，过场一开始镜头会把按键放掉，等不到「看到按下了」
        return s.step("放最后一击：按下奥义键", ctx -> UltimateClient.KEY.setDown(true))
                .waitUntil("最后一击的过场开始", ctx -> {
                    if (show() == null) UltimateClient.KEY.setDown(true);
                    return show() != null && UltimateFilm.playing() != null;
                })
                .step("放最后一击：松开", ctx -> UltimateClient.KEY.setDown(false));
    }

    static @Nullable HeroFinisherShow show() {
        var p = Minecraft.getInstance().player;
        return p == null ? null : HeroFinisherShow.of(p.getId());
    }

    /** 记镜头的那一个监听只挂一次；{@link #recording} 开着时每画一帧记一次。 */
    private static boolean listening, recording;

    /** 和 {@link UltimateViews#film} 一样逐刻截帧；截帧期间每画一帧记下镜头有没有塞进方块、极远机位在哪。 */
    static ScenarioBuilder filmChecked(ScenarioBuilder s, String name, int frames, int rate) {
        s.step("开始记镜头", ctx -> {
            if (!listening) {
                NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> {
                    if (recording) record();
                });
                listening = true;
            }
            recording = true;
        });
        film(s, name, frames, rate);
        return s.step("停止记镜头", ctx -> recording = false);
    }

    /** 这一帧的镜头：在方块里就记一次；极远镜（Cut 6–7）记下机位。 */
    static void record() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !CameraDirector.active()) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        BlockPos pos = BlockPos.containing(cam);
        var shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos, CollisionContext.empty());
        if (!shape.isEmpty() && shape.bounds().move(pos).contains(cam) && buried++ == 0)
            LOGGER.warn("最后一击的镜头塞进了方块：第 {} 刻，{}", CameraDirector.age(), cam);
        HeroFinisherShow show = show();
        int age = CameraDirector.age();
        if (show != null && show.far() != null && age >= HeroFinisherTimeline.BLAST && age < HeroFinisherTimeline.FALL) {
            farFrom = cam;
            farCave = show.far().cave();
        }
    }

    /** 放完：过场收起、镜头回到玩家、连携回到常态。 */
    static ScenarioBuilder finished(ScenarioBuilder s) {
        return s.waitUntil("放完，镜头回来了", ctx -> UltimateFilm.playing() == null && !CameraDirector.active())
                .waitUntilServer("回到常态", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE)
                .waitUntil("画面收起来了", ctx -> show() == null)
                .check("镜头一直没塞进方块", ctx -> buried == 0);
    }

    @LDLRegisterClient(name = "hero_finisher_film", group = "hero_finisher", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Film extends HeroFinisherViews {
        private long passes;

        @Override
        public void define(ScenarioBuilder s) {
            converge(s, true)
                    .check("靶子头顶有誓约锁定的星芒", ctx -> {
                        float[] st = VowLockMarker.state(0);
                        return st != null && foe != null && (int) st[0] == foe.getId();
                    })
                    .ticks(8)
                    .screenshot("hero_vow_lock")
                    .server("记下靶子的血", ctx -> health1 = foeHealth())
                    .step("记下压黑画了几次", ctx -> passes = HeroFinisherScreen.passes());
            finisher(s);
            filmChecked(s, "hero_finisher_frame", HeroFinisherTimeline.LENGTH + 4, 2)
                    .check("全黑起手、火苗画了", ctx -> HeroFinisherScreen.passes() > passes)
                    .checkServer("打中了锁住的靶子", ctx -> foeHealth() < health1)
                    .check("极远镜退到了几十格外", ctx -> farFrom != null && !farCave && foe != null
                            && farFrom.distanceTo(foe.position()) >= HeroFinisherTimeline.CAVE_BELOW);
            finished(s);
            ultimateTeardown(s);
        }
    }

    @LDLRegisterClient(name = "hero_finisher_no_target", group = "hero_finisher", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class NoTarget extends HeroFinisherViews {
        @Override
        public void define(ScenarioBuilder s) {
            converge(s, false)
                    .check("没有锁定，不画星芒", ctx -> VowLockMarker.state(0) == null);
            finisher(s)
                    .waitUntil("砸下去了", ctx -> CameraDirector.age() >= HeroFinisherTimeline.IMPACT + 2).screenshot("hero_no_target_blast")
                    .waitUntil("火柱", ctx -> CameraDirector.age() >= HeroFinisherTimeline.PILLAR + 8).step("记下镜头", ctx -> record())
                    .screenshot("hero_no_target_pillar");
            finished(s);
            ultimateTeardown(s);
        }
    }

    /** 罩住台子的矮石屋：里面高 5 格。 */
    static final int ROOM_X0 = -9, ROOM_X1 = 10, ROOM_Z0 = -9, ROOM_Z1 = 11, ROOM_TOP = FLOOR + 6;

    static void room(ServerLevel level, boolean build) {
        var stone = build ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        for (int x = ROOM_X0; x <= ROOM_X1; x++)
            for (int z = ROOM_Z0; z <= ROOM_Z1; z++)
                for (int y = FLOOR + 1; y <= ROOM_TOP; y++) {
                    boolean shell = x == ROOM_X0 || x == ROOM_X1 || z == ROOM_Z0 || z == ROOM_Z1 || y == ROOM_TOP;
                    if (shell) level.setBlock(new BlockPos(x, y, z), stone, 2);
                }
    }

    @LDLRegisterClient(name = "hero_finisher_cave", group = "hero_finisher", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Cave extends HeroFinisherViews {
        @Override
        public void define(ScenarioBuilder s) {
            converge(s, true).server("台子罩上矮石屋", ctx -> room(ctx.server().overworld(), true)).ticks(4)
                    .server("记下靶子的血", ctx -> health1 = foeHealth());
            finisher(s);
            filmChecked(s, "hero_cave_frame", HeroFinisherTimeline.LENGTH + 4, 10)
                    .checkServer("打中了锁住的靶子", ctx -> foeHealth() < health1)
                    .check("屋里退不出去：改洞内退距", ctx -> farFrom != null && farCave);
            finished(s);
            ultimateTeardown(s);
            s.teardown("拆掉石屋", ctx -> ctx.server().execute(() -> room(ctx.server().overworld(), false)));
        }
    }
}
