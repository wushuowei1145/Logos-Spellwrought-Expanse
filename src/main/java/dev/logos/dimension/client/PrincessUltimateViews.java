package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.companion.PrincessUltimateFx;
import dev.logos.dimension.client.companion.PrincessUltimateScreen;
import dev.logos.dimension.client.ultimate.UltimateClient;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateFilmRenderer;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.PrincessUltimate;
import dev.logos.dimension.companion.PrincessUltimatePlan;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.ultimate.UltimateCast;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.Ultimates;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 实际客户端检查精灵公主的奥义「万分之一 · 世界树祈愿矢」（docs/ELF_PRINCESS_COMPANION.md 第 11 节），一组 {@code princess_ultimate}。
 * 白瓷台上她站在玩家左前方，十格外站着三只不动的尸壳（中间那只是主目标）：
 * <ul>
 * <li>{@code princess_ultimate_full}：只有她，放完整版：主人回血、中毒清掉、挂上护盾；光矢和圣箭打中尸壳，挂上生命易伤 II；
 *     放慢到每秒 2 刻、从下令起每刻截一张（{@code princess_ultimate_full_NNN}），拿 tools/frames_to_gif.py 拼成动图看节奏和运镜；</li>
 * <li>{@code princess_ultimate_condensed}：白梅先放，她第二位接上演缩短版（44 刻），同样逐刻截帧（{@code princess_ultimate_condensed_NNN}）；</li>
 * <li>{@code princess_ultimate_gift}：她先放，白梅接上：白梅这一段吃到森之加护（威力 × 1.3、耗魔 × 0.75）；凑齐、汇流、
 *     放最后一击，命中时荡开全队治愈脉冲。</li>
 * </ul>
 */
public abstract class PrincessUltimateViews extends UltimateViews {
    /** 她站的地方；三只尸壳围着的中心。 */
    static final Vec3 HER = new Vec3(-1.5, FLOOR + 1, 1), CENTER = new Vec3(.5, FLOOR + 1, 10.5);
    static final List<Vec3> FOES = List.of(CENTER, CENTER.add(-2.6, 0, 1.2), CENTER.add(2.4, 0, -.8));
    static final List<Husk> husks = new ArrayList<>();
    /** 截帧时游戏放慢到每秒几刻：截一张图比一刻长时会漏刻，放得更慢，动图里一张才正好是一刻。 */
    static final int RATE = 2;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("princess_ultimate").defaultTimeoutMs(90_000).scenarioTimeoutMs(300_000);
    }

    /** 清掉四位，只叫她出来。 */
    static void princess(ServerPlayer p) {
        dismissAll(p);
        Companions.wake(p, Companions.PRINCESS_DEF);
        Companions.grantPact(p, Companions.PRINCESS_DEF);
        Companions.summon(p, Companions.PRINCESS_DEF, HER);
    }

    /** 三只不动、不怕晒、血很厚的尸壳；中间那只记成 {@link UltimateViews#foe}（主目标，玩家对准它）。 */
    static void spawnFoes(ServerPlayer p) {
        removeFoes();
        for (Vec3 at : FOES) {
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
            husks.add(h);
        }
        foe = husks.isEmpty() ? null : husks.getFirst();
    }

    static void removeFoes() {
        for (Husk h : husks) h.discard();
        husks.clear();
        foe = null;
    }

    /** 每只尸壳都掉了血。 */
    static boolean allHit() {
        return !husks.isEmpty() && husks.stream().allMatch(h -> h.getHealth() < FOE_HEALTH);
    }

    /** 每只尸壳都挂着生命易伤 II。 */
    static boolean allVulnerable() {
        var effect = LogosNatureBridge.lifeVulnerability();
        return effect.isPresent() && !husks.isEmpty() && husks.stream().allMatch(h -> {
            MobEffectInstance v = h.getEffect(effect.get());
            return v != null && v.getAmplifier() == PrincessUltimatePlan.VULNERABILITY_LEVEL;
        });
    }

    static ScenarioBuilder princessStage(ScenarioBuilder s) {
        return ultimateStage(s).server("对准中间那只尸壳", ctx -> aim(ctx.player(), EYE, CENTER.add(0, 1, 0)))
                .step("客户端也对准", ctx -> aimClient(CENTER.add(0, 1, 0)));
    }

    static ScenarioBuilder princessTeardown(ScenarioBuilder s) {
        s.teardown("清掉尸壳", ctx -> ctx.server().execute(PrincessUltimateViews::removeFoes));
        return ultimateTeardown(s);
    }

    static @Nullable CompanionEntity her(ServerPlayer p) {
        return Companions.out(p, Companions.PRINCESS_DEF);
    }

    @LDLRegisterClient(name = "princess_ultimate_full", group = "princess_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Full extends PrincessUltimateViews {
        private Ultimates.Outcome outcome;
        private long passes, fx, spot;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            princessStage(s).server("只有她、三只尸壳", ctx -> {
                        princess(ctx.player());
                        spawnFoes(ctx.player());
                    })
                    .waitUntilServer("站好了", ctx -> arrived(ctx.player(), 1)).ticks(10)
                    .step("记下叠层和世界里的画画了几次", ctx -> {
                        passes = UltimateFilmRenderer.passes();
                        fx = PrincessUltimateFx.frames();
                        spot = PrincessUltimateScreen.passes();
                    })
                    .server("主人受了伤、中了毒，下令", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.setHealth(8);
                        p.addEffect(new MobEffectInstance(MobEffects.POISON, 400, 0));
                        health = p.getHealth();
                        outcome = Ultimates.order(p, "");
                    })
                    .checkServer("单人奥义，演完整版", ctx -> {
                        CompanionEntity c = her(ctx.player());
                        UltimateCast cast = c == null ? null : Ultimates.cast(c);
                        return outcome == Ultimates.Outcome.STARTED && Ultimates.phase(ctx.player()) == UltimateChain.Phase.SINGLE && cast != null
                                && !cast.condensed() && cast.script() instanceof PrincessUltimate u && u.plan() == PrincessUltimatePlan.FULL;
                    })
                    .waitUntil("过场开始", ctx -> UltimateFilm.playing() != null);
            film(s, "princess_ultimate_full", PrincessUltimatePlan.FULL.length() + 4, RATE)
                    .check("叠层、世界里的画、种子破土的压黑都画了", ctx -> UltimateFilmRenderer.passes() > passes && PrincessUltimateFx.frames() > fx
                            && PrincessUltimateScreen.passes() > spot)
                    .checkServer("主人回了血、中毒清掉了、挂着她的护盾", ctx -> {
                        ServerPlayer p = ctx.player();
                        return p.getHealth() > health && !p.hasEffect(MobEffects.POISON) && p.getAbsorptionAmount() > 0 && PrincessUltimate.shielded(p);
                    })
                    .checkServer("三只尸壳都挨了打、挂着生命易伤 II", ctx -> allHit() && allVulnerable())
                    .waitUntilServer("演完回到常态", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE)
                    .waitUntil("镜头回来了", ctx -> UltimateFilm.playing() == null && !CameraDirector.active())
                    .screenshot("princess_ultimate_after");
            princessTeardown(s);
        }
    }

    @LDLRegisterClient(name = "princess_ultimate_condensed", group = "princess_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Condensed extends PrincessUltimateViews {
        @Override
        public void define(ScenarioBuilder s) {
            princessStage(s).server("白梅和她、三只尸壳", ctx -> {
                        call(ctx.player(), 2);
                        spawnFoes(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .server("下令：白梅先放", ctx -> Ultimates.order(ctx.player(), "shiraume"))
                    .waitUntilServer("白梅演完了", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.WINDOW && !performing(ctx.player(), 0))
                    .ticks(2)
                    .server("她第二位接上", ctx -> Ultimates.order(ctx.player(), "elf_princess"))
                    .checkServer("演缩短版", ctx -> {
                        CompanionEntity c = her(ctx.player());
                        UltimateCast cast = c == null ? null : Ultimates.cast(c);
                        return cast != null && cast.condensed() && cast.stage().index() == 1 && cast.script() instanceof PrincessUltimate u
                                && u.plan() == PrincessUltimatePlan.CONDENSED && cast.script().length() <= 44;
                    })
                    .waitUntil("过场开始", ctx -> UltimateFilm.playing() != null);
            film(s, "princess_ultimate_condensed", PrincessUltimatePlan.CONDENSED.length() + 4, RATE)
                    .checkServer("三只尸壳都挨了打、挂着生命易伤 II", ctx -> allHit() && allVulnerable())
                    .waitUntil("两位都放完，放连携切入", ctx -> dev.logos.dimension.client.cutin.ChainCutinRenderer.active());
            princessTeardown(s);
        }
    }

    @LDLRegisterClient(name = "princess_ultimate_gift", group = "princess_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Gift extends PrincessUltimateViews {
        private double mana;
        private double power = -1;
        private int pulses;

        @Override
        public void define(ScenarioBuilder s) {
            princessStage(s).server("白梅和她、三只尸壳", ctx -> {
                        call(ctx.player(), 2);
                        spawnFoes(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .server("下令：她先放", ctx -> Ultimates.order(ctx.player(), "elf_princess"))
                    .waitUntilServer("她出手，交出森之加护", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.WINDOW && !performing(ctx.player(), 1))
                    .ticks(2)
                    .server("白梅接上", ctx -> {
                        LogosNatureBridge.mana(ctx.player(), 1000);
                        Ultimates.order(ctx.player(), "shiraume");
                        mana = LogosNatureBridge.mana(ctx.player());
                        CompanionEntity c = out(ctx.player(), 0);
                        UltimateCast cast = c == null ? null : Ultimates.cast(c);
                        power = cast == null ? -1 : cast.power();
                        pulses = PrincessUltimate.pulses();
                    })
                    .checkServer("白梅这一段：耗魔 260 × 0.85 × 0.75，威力 1.25 × 1.3", ctx -> Math.abs(1000 - mana - Math.round(260 * .85 * .75)) < .01
                            && Math.abs(power - 1.25 * 1.3) < 1e-6)
                    .waitUntilServer("凑齐、进汇流", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.CONVERGED)
                    .waitUntil("客户端在汇流", ctx -> UltimateClient.status().chainPhase() == UltimateChain.Phase.CONVERGED);
            press(s, "放最后一击")
                    .waitUntilServer("最后一击打中，回到常态", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE)
                    .checkServer("命中时荡开了一次全队治愈脉冲", ctx -> PrincessUltimate.pulses() == pulses + 1);
            princessTeardown(s);
        }
    }
}
