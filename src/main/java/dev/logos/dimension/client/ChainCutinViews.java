package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.cutin.ChainCutinRenderer;
import dev.logos.dimension.companion.CompanionDef;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.cutin.ChainLayout;
import dev.logos.dimension.cutin.ChainTimeline;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查连携切入（docs/CHAIN_CUTIN.md），一组 {@code chain_cutin}。白瓷台上四位同伴站成一排，玩家用 {@code /alchemy chain} 发动：
 * <ul>
 * <li>{@code chain_cutin_full}：四位同伴都在，停在每一段上截图（刺穿、冲入、落位、停留、预升、闪白、幕布、退潮）；</li>
 * <li>{@code chain_cutin_counts}：一位、两位、三位同伴时各停在停留期截一张，看分格和玩家是否在正中间；</li>
 * <li>{@code chain_cutin_timing}：真放一次，界面在黑底那一段藏起来、白光散去后回来，放完自己结束。</li>
 * </ul>
 */
public abstract class ChainCutinViews extends CompanionViews {
    /** 玩家站在台子南边看着她们。 */
    static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, -3.5);
    static final Vec3 LOOK = new Vec3(.5, FLOOR + 1.4, 3);
    /** 她们站成一排的位置（按同伴表的顺序）。 */
    static final List<Vec3> SPOTS = List.of(new Vec3(-2.5, FLOOR + 1, 2.5), new Vec3(-.8, FLOOR + 1, 3), new Vec3(1.8, FLOOR + 1, 3),
            new Vec3(3.5, FLOOR + 1, 2.5));

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("chain_cutin").defaultTimeoutMs(60_000).scenarioTimeoutMs(240_000);
    }

    /** 清掉四位，再叫出同伴表里的前 {@code count} 位。 */
    static void call(ServerPlayer p, int count) {
        dismissAll(p);
        for (int i = 0; i < count; i++) {
            CompanionDef d = Companions.ALL.get(i);
            Companions.wake(p, d);
            Companions.grantPact(p, d);
            Companions.summon(p, d, SPOTS.get(i));
        }
    }

    static void dismissAll(ServerPlayer p) {
        for (CompanionDef d : Companions.ALL) {
            Companions.dismiss(p, d);
            p.setData(d.bond().get(), Companions.Bond.NONE);
        }
        p.level().getEntitiesOfClass(CompanionEntity.class, AREA.inflate(8)).forEach(e -> e.discard());
    }

    /** 叫出来的都站好了（入场动画播完）。 */
    static boolean arrived(ServerPlayer p, int count) {
        int n = 0;
        for (CompanionDef d : Companions.ALL) {
            CompanionEntity c = Companions.out(p, d);
            if (c == null) continue;
            if ("summon".equals(c.npcMiscAnimation()) || c.hiddenForSummon()) return false;
            n++;
        }
        return n == count;
    }

    static ScenarioBuilder cutinStage(ScenarioBuilder s) {
        return stage(s, GameType.CREATIVE, 70).runCommand("difficulty peaceful")
                .server("对准台子，手里拿把剑", ctx -> {
                    aim(ctx.player(), EYE, LOOK);
                    ctx.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
                })
                .step("客户端也对准", ctx -> aimClient(LOOK));
    }

    static void chain() {
        Minecraft.getInstance().player.connection.sendCommand("alchemy chain");
    }

    static ScenarioBuilder teardown(ScenarioBuilder s) {
        return s.teardown("清掉", ctx -> {
            ChainCutinRenderer.hold(-1);
            ctx.server().execute(() -> {
                for (ServerPlayer p : ctx.server().getPlayerList().getPlayers()) dismissAll(p);
            });
        });
    }

    @LDLRegisterClient(name = "chain_cutin_full", group = "chain_cutin", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Full extends ChainCutinViews {
        private long passes, portraits;

        @Override
        public void define(ScenarioBuilder s) {
            cutinStage(s).server("四位都叫出来", ctx -> call(ctx.player(), 4))
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 4)).ticks(30)
                    .step("先停在刺穿那一拍，再发动", ctx -> {
                        ChainCutinRenderer.hold(1.4f);
                        chain();
                    })
                    .waitUntil("开始放了", ctx -> ChainCutinRenderer.active()).ticks(4)
                    .check("五格，玩家在正中间", ctx -> {
                        ChainLayout l = ChainCutinRenderer.layout();
                        return l != null && l.slots().size() == 5 && l.playerSlot() == 2 && l.slots().get(2).center() == .5f;
                    })
                    .step("记下通道跑了几次", ctx -> passes = ChainCutinRenderer.passes()).ticks(2)
                    .check("通道在画", ctx -> ChainCutinRenderer.passes() > passes)
                    .screenshot("chain_cutin_1_pierce")
                    .step("冲入", ctx -> ChainCutinRenderer.hold(2.7f)).ticks(3)
                    .screenshot("chain_cutin_2_slide")
                    .step("落位爆闪", ctx -> ChainCutinRenderer.hold(4.9f)).ticks(3)
                    .screenshot("chain_cutin_3_land")
                    .step("停留", ctx -> {
                        ChainCutinRenderer.hold(13);
                        portraits = ChainCutinRenderer.portraitsDrawn();
                    }).ticks(3)
                    .check("五格的角色都画了", ctx -> ChainCutinRenderer.portraitsDrawn() - portraits >= 5)
                    .screenshot("chain_cutin_4_hold")
                    .step("过曝预升", ctx -> ChainCutinRenderer.hold(22.2f)).ticks(3)
                    .screenshot("chain_cutin_5_rise")
                    .step("闪白甩出", ctx -> ChainCutinRenderer.hold(24.2f)).ticks(3)
                    .screenshot("chain_cutin_6_peak")
                    .step("白光幕布", ctx -> {
                        ChainCutinRenderer.hold(26.5f);
                        portraits = ChainCutinRenderer.portraitsDrawn();
                    }).ticks(3)
                    .check("幕布下不再画角色", ctx -> ChainCutinRenderer.portraitsDrawn() == portraits)
                    .screenshot("chain_cutin_7_swap")
                    .step("退潮", ctx -> ChainCutinRenderer.hold(29.5f)).ticks(3)
                    .screenshot("chain_cutin_8_fade")
                    .step("放开", ctx -> ChainCutinRenderer.hold(-1))
                    .waitUntil("放完了", ctx -> !ChainCutinRenderer.active());
            teardown(s);
        }
    }

    @LDLRegisterClient(name = "chain_cutin_counts", group = "chain_cutin", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Counts extends ChainCutinViews {
        @Override
        public void define(ScenarioBuilder s) {
            cutinStage(s);
            for (int count = 1; count <= 3; count++) {
                int n = count;
                s.server(n + " 位同伴", ctx -> call(ctx.player(), n))
                        .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), n)).ticks(20)
                        .step("停在停留期，发动", ctx -> {
                            ChainCutinRenderer.hold(13);
                            chain();
                        })
                        .waitUntil("开始放了", ctx -> ChainCutinRenderer.active()).ticks(4)
                        .check("玩家在正中间那一格", ctx -> {
                            ChainLayout l = ChainCutinRenderer.layout();
                            return l != null && l.slots().size() == n + 1 && l.slots().get(l.playerSlot()).center() == .5f;
                        })
                        .screenshot("chain_cutin_count_" + (n + 1))
                        .step("放开", ctx -> ChainCutinRenderer.hold(-1))
                        .waitUntil("放完了", ctx -> !ChainCutinRenderer.active());
            }
            teardown(s);
        }
    }

    @LDLRegisterClient(name = "chain_cutin_timing", group = "chain_cutin", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Timing extends ChainCutinViews {
        private long started, hidden;

        @Override
        public void define(ScenarioBuilder s) {
            cutinStage(s).step("这次显示界面", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("两位同伴", ctx -> call(ctx.player(), 2))
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .step("真放一次", ctx -> {
                        hidden = ChainCutinRenderer.hudHidden();
                        chain();
                    })
                    .waitUntil("开始放了", ctx -> {
                        if (!ChainCutinRenderer.active()) return false;
                        started = Minecraft.getInstance().level.getGameTime();
                        return true;
                    })
                    .waitUntil("放完了", ctx -> !ChainCutinRenderer.active())
                    .check("一共放了约 " + (int) ChainTimeline.END + " 刻", ctx -> {
                        long took = Minecraft.getInstance().level.getGameTime() - started;
                        return took >= ChainTimeline.END - 3 && took <= ChainTimeline.END + 6;
                    })
                    .check("黑底那一段界面藏起来了", ctx -> ChainCutinRenderer.hudHidden() > hidden)
                    .step("记下", ctx -> hidden = ChainCutinRenderer.hudHidden()).ticks(5)
                    .check("放完界面回来了", ctx -> ChainCutinRenderer.hudHidden() == hidden)
                    .step("界面藏回去", ctx -> Minecraft.getInstance().options.hideGui = true);
            teardown(s);
        }
    }
}
