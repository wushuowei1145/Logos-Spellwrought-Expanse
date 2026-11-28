package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.companion.KohakuUltimateFx;
import dev.logos.dimension.client.companion.KohakuUltimateScreen;
import dev.logos.dimension.client.companion.KohakuUltimateShow;
import dev.logos.dimension.client.ultimate.UltimateClient;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateFilmRenderer;
import dev.logos.dimension.companion.CompanionDef;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KohakuSeal;
import dev.logos.dimension.companion.KohakuUltimate;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.ultimate.UltimateCast;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.Ultimates;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 实际客户端检查琥珀的奥义「星辰花炼成 · 凝时琥珀」（docs/KOHAKU_COMPANION.md 第 20 节），一组 {@code kohaku_ultimate}。
 * 白瓷台上琥珀站在左前方，台子后面三只不动的尸壳当敌群，玩家对准它们：
 * <ul>
 * <li>{@code kohaku_ultimate_full}：只有她一位，放完整版，放慢逐刻截帧（{@code kohaku_ult_full_NNN}，拿 tools/frames_to_gif.py 拼成动图看节奏和运镜）；
 *     打中了靶子、阵里三只都被凝时、身上画着晶壳，自己的后期画了、抓下了那一帧，放完镜头回来；</li>
 * <li>{@code kohaku_ultimate_condensed}：白梅先放，她第二位接上，演缩短版（42 刻），同样逐刻截帧（{@code kohaku_ult_short_NNN}）；</li>
 * <li>{@code kohaku_ultimate_relay}：她第一位放，出手时炼金回流返还主人 280 的四成（112）；凝时时间到了也被按着，白梅接上打中被封着的靶子，
 *     琥珀壳碎开一下；白梅演完就放开。</li>
 * </ul>
 */
public abstract class KohakuUltimateViews extends UltimateViews {
    /** 她站在台子左前方；敌群在台子后面。 */
    static final Vec3 KOHAKU = new Vec3(-1.5, FLOOR + 1, 1.2), SHIRAUME = new Vec3(2.6, FLOOR + 1, 1.2);
    static final List<Vec3> CROWD = List.of(new Vec3(-1.6, FLOOR + 1, 6.2), new Vec3(2.4, FLOOR + 1, 6.6));
    static final List<Husk> extra = new ArrayList<>();
    /** 截帧时游戏放慢到每秒几刻。 */
    static final int RATE = 2;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("kohaku_ultimate").defaultTimeoutMs(60_000).scenarioTimeoutMs(300_000);
    }

    /** 清掉四位，再按顺序叫出 {@code defs}（琥珀站在左前方，白梅右前方）。 */
    static void callParty(ServerPlayer p, CompanionDef... defs) {
        dismissAll(p);
        for (CompanionDef d : defs) {
            Companions.wake(p, d);
            Companions.grantPact(p, d);
            Companions.summon(p, d, d == Companions.KOHAKU_DEF ? KOHAKU : SHIRAUME);
        }
    }

    /** 靶子加上两只同样不动的尸壳：一小群。 */
    static void spawnCrowd(ServerPlayer p) {
        spawnFoe(p);
        removeCrowd();
        for (Vec3 at : CROWD) {
            Husk h = EntityType.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
            if (h == null) continue;
            h.setNoAi(true);
            h.setPersistenceRequired();
            h.setSilent(true);
            var hp = h.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(FOE_HEALTH);
            h.setHealth(FOE_HEALTH);
            h.snapTo(at.x, at.y, at.z, 180, 0);
            p.level().addFreshEntity(h);
            extra.add(h);
        }
    }

    static void removeCrowd() {
        for (Husk h : extra) h.discard();
        extra.clear();
    }

    static @Nullable CompanionEntity kohaku(ServerPlayer p) {
        return Companions.out(p, Companions.KOHAKU_DEF);
    }

    static @Nullable UltimateCast kohakuCast(ServerPlayer p) {
        CompanionEntity k = kohaku(p);
        return k == null ? null : Ultimates.cast(k);
    }

    /**
     * 逐刻截第 {@code from} 到 {@code to} 张（{@code <name>_NNN}）。游戏要先放慢（{@code /tick rate}）：截一张图要一两百毫秒，
     * 放得不够慢时两张之间会过好几刻，动图的节奏就不对了。
     */
    static ScenarioBuilder frames(ScenarioBuilder s, String name, int from, int to) {
        for (int i = from; i < to; i++) s.ticks(1).screenshot(String.format("%s_%03d", name, i));
        return s;
    }

    static ScenarioBuilder kohakuTeardown(ScenarioBuilder s) {
        s.teardown("清掉敌群", ctx -> ctx.server().execute(KohakuUltimateViews::removeCrowd));
        return ultimateTeardown(s);
    }

    @LDLRegisterClient(name = "kohaku_ultimate_full", group = "kohaku_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Full extends KohakuUltimateViews {
        private long passes, captures, filmPasses;
        private float health0;
        private int struck;

        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("琥珀一位、三只尸壳", ctx -> {
                        callParty(ctx.player(), Companions.KOHAKU_DEF);
                        spawnCrowd(ctx.player());
                    })
                    .waitUntilServer("站好了", ctx -> arrived(ctx.player(), 1)).ticks(10)
                    .step("记下后期画了几次", ctx -> {
                        passes = KohakuUltimateScreen.passes();
                        captures = KohakuUltimateScreen.captures();
                        filmPasses = UltimateFilmRenderer.passes();
                    })
                    .runCommand("tick rate " + RATE)
                    .server("下令", ctx -> {
                        health0 = foeHealth();
                        Ultimates.order(ctx.player(), "");
                    })
                    .checkServer("她在演完整版（106 刻，第 75 刻出手）", ctx -> {
                        UltimateCast cast = kohakuCast(ctx.player());
                        return cast != null && !cast.condensed() && cast.script() instanceof KohakuUltimate k && k.beats() == KohakuUltimate.FULL
                                && cast.script().length() == 106 && cast.script().impact() == 75;
                    })
                    .waitUntil("过场开始", ctx -> KohakuUltimateShow.playing());
            frames(s, "kohaku_ult_full", 0, 84)
                    .checkServer("出手了：打中靶子，阵里的三只都被凝时", ctx -> foe != null && foe.getHealth() < health0 && KohakuSeal.sealed(foe)
                            && extra.stream().allMatch(KohakuSeal::sealed))
                    .check("身上画着琥珀晶壳，世界里的阵还在", ctx -> KohakuUltimateFx.shells() >= 3 && KohakuUltimateFx.live() > 0);
            frames(s, "kohaku_ult_full", 84, 112)
                    .runCommand("tick rate 20")
                    .check("她自己的后期画了，抓下了凝时那一帧，过场叠层也画了", ctx -> KohakuUltimateScreen.passes() > passes
                            && KohakuUltimateScreen.captures() > captures && UltimateFilmRenderer.passes() > filmPasses)
                    .waitUntil("放完，镜头回来了", ctx -> UltimateFilm.playing() == null && !CameraDirector.active())
                    .screenshot("kohaku_ult_after")
                    .checkServer("单人奥义：不开窗口", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE)
                    .waitUntilServer("凝时 3.5 秒以后放开", ctx -> foe != null && !KohakuSeal.sealed(foe) && extra.stream().noneMatch(KohakuSeal::sealed));
            kohakuTeardown(s);
        }
    }

    @LDLRegisterClient(name = "kohaku_ultimate_condensed", group = "kohaku_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Condensed extends KohakuUltimateViews {
        private long passes;

        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("白梅和琥珀、三只尸壳", ctx -> {
                        callParty(ctx.player(), Companions.SHIRAUME_DEF, Companions.KOHAKU_DEF);
                        spawnCrowd(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .server("白梅先放", ctx -> Ultimates.order(ctx.player(), "shiraume"))
                    .waitUntilServer("白梅演完", ctx -> {
                        CompanionEntity w = Companions.out(ctx.player(), Companions.SHIRAUME_DEF);
                        return w != null && !Ultimates.performing(w);
                    })
                    .waitUntil("客户端看到接力窗口", ctx -> UltimateClient.status().chainPhase() == UltimateChain.Phase.WINDOW).ticks(4)
                    .step("记下后期画了几次", ctx -> passes = KohakuUltimateScreen.passes())
                    .runCommand("tick rate " + RATE);
            press(s, "接琥珀")
                    .checkServer("琥珀第二位接上，演缩短版（42 刻）", ctx -> {
                        UltimateCast cast = kohakuCast(ctx.player());
                        return cast != null && cast.condensed() && cast.stage().index() == 1 && cast.script().length() == 42
                                && cast.script().length() <= dev.logos.dimension.ultimate.UltimateDef.MAX_CONDENSED;
                    })
                    .waitUntil("过场开始", ctx -> KohakuUltimateShow.playing());
            frames(s, "kohaku_ult_short", 0, 46)
                    .runCommand("tick rate 20")
                    .check("后期画了", ctx -> KohakuUltimateScreen.passes() > passes)
                    .checkServer("打中、凝时", ctx -> foe != null && KohakuSeal.sealed(foe))
                    .waitUntilServer("两位都放完，进连携切入", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.CUTIN
                            || Ultimates.phase(ctx.player()) == UltimateChain.Phase.CONVERGED);
            kohakuTeardown(s);
        }
    }

    @LDLRegisterClient(name = "kohaku_ultimate_relay", group = "kohaku_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Relay extends KohakuUltimateViews {
        private double manaPaid, manaBefore, manaAfter;
        private int ageBefore;
        private int cracks;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("琥珀和白梅、三只尸壳", ctx -> {
                        callParty(ctx.player(), Companions.SHIRAUME_DEF, Companions.KOHAKU_DEF);
                        spawnCrowd(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .server("魔力 1000，琥珀第一位放", ctx -> {
                        LogosNatureBridge.mana(ctx.player(), 1000);
                        Ultimates.order(ctx.player(), "kohaku");
                        manaPaid = 1000 - LogosNatureBridge.mana(ctx.player());
                    })
                    .checkServer("扣了 280", ctx -> Math.abs(manaPaid - 280) < .01 && kohakuCast(ctx.player()) != null)
                    // 出手前后各读一次魔力：放慢到每秒两刻，两次正好落在出手那一刻的前后（不然中间会混进几刻自然回魔）
                    .runCommand("tick rate 2")
                    .waitUntilServer("下一刻出手", ctx -> {
                        UltimateCast cast = kohakuCast(ctx.player());
                        return cast != null && cast.age() >= KohakuUltimate.FULL.impact();
                    })
                    .server("记下出手前的魔力", ctx -> {
                        UltimateCast cast = kohakuCast(ctx.player());
                        ageBefore = cast == null ? -1 : cast.age();
                        manaBefore = LogosNatureBridge.mana(ctx.player());
                    })
                    .waitUntilServer("出手了", ctx -> {
                        UltimateCast cast = kohakuCast(ctx.player());
                        return cast == null || cast.age() > KohakuUltimate.FULL.impact();
                    })
                    .server("记下出手后的魔力", ctx -> manaAfter = LogosNatureBridge.mana(ctx.player()))
                    .runCommand("tick rate 20")
                    .checkServer("炼金回流：返还 280 的四成（112）", ctx -> ageBefore == KohakuUltimate.FULL.impact() && KohakuUltimate.lastRefund() == 112
                            && manaAfter - manaBefore >= 111 && manaAfter - manaBefore < 125)
                    .waitUntilServer("她演完了", ctx -> kohakuCast(ctx.player()) == null)
                    .ticks(50)
                    .checkServer("凝时 3.5 秒早过了，还被馈赠按着", ctx -> foe != null && KohakuSeal.sealed(foe))
                    .check("客户端的晶壳也还在", ctx -> KohakuUltimateFx.shells() >= 1)
                    .screenshot("kohaku_ult_held")
                    .server("记下", ctx -> {
                        cracks = KohakuUltimate.cracks();
                        health = foeHealth();
                    });
            press(s, "接白梅")
                    .checkServer("白梅接上：第二段", ctx -> Ultimates.released(ctx.player()).equals(List.of("kohaku", "shiraume")))
                    .waitUntilServer("白梅打中被封着的靶子，琥珀壳碎开", ctx -> KohakuUltimate.cracks() > cracks && foeHealth() < health)
                    .waitUntilServer("白梅演完，放开", ctx -> foe != null && !KohakuSeal.sealed(foe));
            kohakuTeardown(s);
        }
    }
}
