package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.cutin.ChainCutinRenderer;
import dev.logos.dimension.client.ultimate.UltimateClient;
import dev.logos.dimension.client.ultimate.UltimateWheelScreen;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.CompanionLines;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimateCooldowns;
import dev.logos.dimension.ultimate.Ultimates;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 实际客户端检查奥义（docs/ULTIMATES.md），一组 {@code ultimate}。白瓷台上同伴站成一排，台子后面一只不动的尸壳当靶子，玩家对准它：
 * <ul>
 * <li>{@code ultimate_chain}：两位同伴，下令一位、短按 V 接第二位，凑齐放连携切入，换场进汇流，按 V 放最后一击，回到常态；</li>
 * <li>{@code ultimate_single}：只有一位同伴，放完不开窗口、不放切入；</li>
 * <li>{@code ultimate_denied}：魔力不够（她说一句、那一格闪红、不扣冷却）；都在冷却（整列抖一下）；</li>
 * <li>{@code ultimate_wheel}：三位同伴，按住 V 打开号令轮盘，甩到第二位松手就是她。</li>
 * </ul>
 */
public abstract class UltimateViews extends ChainCutinViews {
    /** 靶子站在台子后面，准星正好穿过两位同伴中间对着它。 */
    static final Vec3 FOE = new Vec3(.5, FLOOR + 1, 5);
    static final float FOE_HEALTH = 2000;
    static final Identifier MANA_BOOST = LogosDimension.id("ultimate_test_mana");
    static @Nullable Husk foe;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("ultimate").defaultTimeoutMs(60_000).scenarioTimeoutMs(240_000);
    }

    /** 生存模式（要扣魔力），魔力上限加到够放几次，清掉冷却。 */
    static ScenarioBuilder ultimateStage(ScenarioBuilder s) {
        return stage(s, GameType.SURVIVAL, 70).runCommand("difficulty easy")
                .server("对准台子后面，魔力上限加满，清掉冷却", ctx -> {
                    aim(ctx.player(), EYE, FOE.add(0, 1, 0));
                    boostMana(ctx.player());
                    Ultimates.reset(ctx.player());
                })
                .step("客户端也对准", ctx -> aimClient(FOE.add(0, 1, 0)));
    }

    static void boostMana(ServerPlayer p) {
        var attr = BuiltInRegistries.ATTRIBUTE.get(Identifier.fromNamespaceAndPath("logos", "max_mana")).orElseThrow();
        var inst = p.getAttribute(attr);
        if (inst != null && !inst.hasModifier(MANA_BOOST)) inst.addTransientModifier(new AttributeModifier(MANA_BOOST, 3000, AttributeModifier.Operation.ADD_VALUE));
        LogosNatureBridge.mana(p, LogosNatureBridge.maxMana(p));
    }

    /** 台子后面一只不动、不怕晒的尸壳，血很厚。 */
    static void spawnFoe(ServerPlayer p) {
        removeFoe();
        Husk h = EntityType.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
        if (h == null) return;
        h.setNoAi(true);
        h.setPersistenceRequired();
        h.setSilent(true);
        var hp = h.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(FOE_HEALTH);
        h.setHealth(FOE_HEALTH);
        h.snapTo(FOE.x, FOE.y, FOE.z, 180, 0);
        p.level().addFreshEntity(h);
        foe = h;
    }

    static void removeFoe() {
        if (foe != null) foe.discard();
        foe = null;
    }

    static float foeHealth() {
        return foe == null ? -1 : foe.getHealth();
    }

    static @Nullable CompanionEntity out(ServerPlayer p, int index) {
        return Companions.out(p, Companions.ALL.get(index));
    }

    /** 第 {@code index} 位同伴（按同伴表）正在演奥义。 */
    static boolean performing(ServerPlayer p, int index) {
        CompanionEntity c = out(p, index);
        return c != null && Ultimates.performing(c);
    }

    static ScenarioBuilder press(ScenarioBuilder s, String what) {
        return s.step(what + "：按下 V", ctx -> UltimateClient.KEY.setDown(true))
                .waitUntil(what + "：客户端看到按下了", ctx -> UltimateClient.held() >= 1)
                .step(what + "：松开（短按）", ctx -> UltimateClient.KEY.setDown(false)).ticks(2);
    }

    static ScenarioBuilder ultimateTeardown(ScenarioBuilder s) {
        return s.teardown("清掉", ctx -> {
            UltimateClient.KEY.setDown(false);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof UltimateWheelScreen) mc.setScreen(null);
            mc.options.hideGui = true;
            ChainCutinRenderer.hold(-1);
            ctx.server().execute(() -> {
                removeFoe();
                for (ServerPlayer p : ctx.server().getPlayerList().getPlayers()) {
                    dismissAll(p);
                    Ultimates.reset(p);
                    var attr = BuiltInRegistries.ATTRIBUTE.get(Identifier.fromNamespaceAndPath("logos", "max_mana"));
                    attr.ifPresent(a -> {
                        var inst = p.getAttribute(a);
                        if (inst != null) inst.removeModifier(MANA_BOOST);
                    });
                }
            });
        });
    }

    @LDLRegisterClient(name = "ultimate_chain", group = "ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Chain extends UltimateViews {
        private Ultimates.Outcome outcome;
        private double manaAfter;
        private long cooldownAfter;
        private float health0, health1;

        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("两位同伴、一个靶子", ctx -> {
                        call(ctx.player(), 2);
                        spawnFoe(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .step("显示界面", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("下令（服务端挑：准星没对着同伴，按队伍顺序是白梅）", ctx -> {
                        LogosNatureBridge.mana(ctx.player(), 1000);
                        health0 = foeHealth();
                        outcome = Ultimates.order(ctx.player(), "");
                        manaAfter = LogosNatureBridge.mana(ctx.player());
                        cooldownAfter = Ultimates.cooldown(ctx.player(), Companions.SHIRAUME_DEF);
                    })
                    .checkServer("白梅开演，扣了 260 魔力，冷却 35 秒", ctx -> outcome == Ultimates.Outcome.STARTED
                            && performing(ctx.player(), 0) && Math.abs(1000 - manaAfter - 260) < .01
                            && cooldownAfter == 700)
                    .waitUntilServer("出手打中了靶子", ctx -> foeHealth() < health0)
                    .waitUntil("客户端看到接力窗口", ctx -> UltimateClient.status().chainPhase() == UltimateChain.Phase.WINDOW)
                    .waitUntilServer("白梅演完了", ctx -> !performing(ctx.player(), 0)).ticks(2)
                    .screenshot("ultimate_window");
            press(s, "接第二位")
                    .checkServer("精灵公主接上：第二段", ctx -> Ultimates.released(ctx.player()).equals(List.of("shiraume", "elf_princess"))
                            && performing(ctx.player(), 1))
                    .waitUntil("两位都放完，放连携切入", ctx -> ChainCutinRenderer.active())
                    .waitUntilServer("换场后进汇流", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.CONVERGED)
                    .waitUntil("切入放完", ctx -> !ChainCutinRenderer.active())
                    .waitUntil("客户端在汇流", ctx -> UltimateClient.status().chainPhase() == UltimateChain.Phase.CONVERGED)
                    .checkServer("锁住了靶子", ctx -> foe != null && foe.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS))
                    .screenshot("ultimate_converged")
                    .server("记下靶子的血", ctx -> health1 = foeHealth());
            press(s, "放最后一击")
                    .waitUntilServer("最后一击打中，回到常态", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE && foeHealth() < health1)
                    .waitUntil("客户端也回到常态", ctx -> UltimateClient.status().chainPhase() == UltimateChain.Phase.IDLE)
                    .checkServer("两位都在冷却", ctx -> Ultimates.cooldown(ctx.player(), Companions.SHIRAUME_DEF) > 0
                            && Ultimates.cooldown(ctx.player(), Companions.PRINCESS_DEF) > 0)
                    .screenshot("ultimate_cooling");
            ultimateTeardown(s);
        }
    }

    @LDLRegisterClient(name = "ultimate_single", group = "ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Single extends UltimateViews {
        private long passes;

        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("一位同伴、一个靶子", ctx -> {
                        call(ctx.player(), 1);
                        spawnFoe(ctx.player());
                    })
                    .waitUntilServer("站好了", ctx -> arrived(ctx.player(), 1)).ticks(10)
                    .step("记下切入通道", ctx -> passes = ChainCutinRenderer.passes());
            press(s, "下令")
                    .checkServer("单人奥义，不开窗口", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.SINGLE
                            && performing(ctx.player(), 0) && Ultimates.released(ctx.player()).isEmpty())
                    .waitUntilServer("演完回到常态", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE).ticks(20)
                    .check("没放连携切入", ctx -> !ChainCutinRenderer.active() && ChainCutinRenderer.passes() == passes);
            ultimateTeardown(s);
        }
    }

    @LDLRegisterClient(name = "ultimate_denied", group = "ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Denied extends UltimateViews {
        private Ultimates.Outcome outcome;

        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("两位同伴", ctx -> call(ctx.player(), 2))
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .step("显示界面", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("魔力见底，下令", ctx -> {
                        LogosNatureBridge.mana(ctx.player(), 0);
                        outcome = Ultimates.order(ctx.player(), "");
                    })
                    .checkServer("没放、没扣冷却，白梅说了缺魔那一句", ctx -> outcome == Ultimates.Outcome.DENIED
                            && !performing(ctx.player(), 0) && Ultimates.cooldown(ctx.player(), Companions.SHIRAUME_DEF) == 0
                            && CompanionLines.last().contains("line.logos_dimension.shiraume.ultimate_no_mana"))
                    .waitUntil("她那一格闪红", ctx -> UltimateClient.flash("shiraume") > 0)
                    .screenshot("ultimate_no_mana")
                    .server("魔力加满，两位都在冷却，下令", ctx -> {
                        LogosNatureBridge.mana(ctx.player(), 1000);
                        long now = ctx.player().level().getGameTime();
                        ctx.player().setData(Ultimates.COOLDOWNS, UltimateCooldowns.NONE.with("shiraume", now + 700).with("elf_princess", now + 300));
                        outcome = Ultimates.order(ctx.player(), "");
                    })
                    .checkServer("没人能放", ctx -> outcome == Ultimates.Outcome.DENIED && Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE)
                    .waitUntil("整列抖一下", ctx -> UltimateClient.shake() > 0).ticks(12)
                    .screenshot("ultimate_cooldown_pies");
            ultimateTeardown(s);
        }
    }

    @LDLRegisterClient(name = "ultimate_wheel", group = "ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Wheel extends UltimateViews {
        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("三位同伴、一个靶子", ctx -> {
                        call(ctx.player(), 3);
                        spawnFoe(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 3)).ticks(10)
                    .waitUntil("客户端拿到三位的状态", ctx -> UltimateClient.status().members().size() == 3)
                    .step("显示界面，按住 V", ctx -> {
                        Minecraft.getInstance().options.hideGui = false;
                        UltimateClient.KEY.setDown(true);
                    })
                    .waitUntil("按住够久，号令轮盘开了", ctx -> Minecraft.getInstance().screen instanceof UltimateWheelScreen).ticks(2)
                    .screenshot("ultimate_wheel")
                    .step("甩到第二格（精灵公主）松手", ctx -> {
                        UltimateClient.KEY.setDown(false);
                        if (Minecraft.getInstance().screen instanceof UltimateWheelScreen w) w.pick(1);
                    }).ticks(3)
                    .check("轮盘收起来了", ctx -> Minecraft.getInstance().screen == null)
                    .checkServer("是精灵公主在放", ctx -> performing(ctx.player(), 1) && !performing(ctx.player(), 0)
                            && Ultimates.released(ctx.player()).equals(List.of("elf_princess")));
            ultimateTeardown(s);
        }
    }
}
