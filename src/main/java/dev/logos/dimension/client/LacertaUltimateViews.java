package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.companion.LacertaUltimateFx;
import dev.logos.dimension.client.companion.LacertaUltimateShow;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateFilmRenderer;
import dev.logos.dimension.companion.CompanionDef;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.LacertaUltimate;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.ultimate.UltimateCast;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.Ultimates;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 实际客户端检查拉塞塔的奥义「绯穹裁决 · 炽龙裂地破」（docs/LACERTA_COMPANION.md 第 22 节），一组 {@code lacerta_ultimate}：
 * <ul>
 * <li>{@code lacerta_ultimate_film}：她一个人放完整版，靶子和周围几只尸壳；放慢到每秒 3 刻逐刻截帧（{@code lacerta_ult_full_NNN}），
 *     拿 tools/frames_to_gif.py 拼成动图看节奏和运镜。打中靶子、四五十块碎岩崩飞、她一刻都没卡进方块、跳满高度、放完镜头回来；</li>
 * <li>{@code lacerta_ultimate_indoor}：头顶 4 格就是天花板的屋里照样放：跳的高度压低，她和镜头都不顶进天花板，截登穹定格那一拍；</li>
 * <li>{@code lacerta_ultimate_relay}：白梅先放，连携里第二位的她演缩短版（逐刻截帧 {@code lacerta_ult_short_NNN}），照样打中；</li>
 * <li>{@code lacerta_ultimate_gift}：她先放，交出「龙炎碎甲」；白梅接着打中带碎甲的靶子时吃到穿甲、再缠一层龙炎，玩家的最后一击也吃到。</li>
 * </ul>
 */
public abstract class LacertaUltimateViews extends UltimateViews {
    /** 她站在玩家和靶子之间偏右，冲阵要冲五六格。 */
    static final Vec3 LACERTA_SPOT = new Vec3(2.5, FLOOR + 1, -1.5), SHIRAUME_SPOT = new Vec3(-2.5, FLOOR + 1, 1);
    /** 室内：天花板在这一层（地面往上 4 格空着）。 */
    static final int CEILING = FLOOR + 5;
    static final List<Mob> extras = new ArrayList<>();


    @Override
    public void configure(ScenarioOptions o) {
        o.tags("lacerta_ultimate").defaultTimeoutMs(90_000).scenarioTimeoutMs(300_000);
    }

    /** 叫出这几位（按给的顺序站位）。 */
    static void callOnly(ServerPlayer p, CompanionDef... defs) {
        dismissAll(p);
        for (int i = 0; i < defs.length; i++) {
            Companions.wake(p, defs[i]);
            Companions.grantPact(p, defs[i]);
            Companions.summon(p, defs[i], defs[i] == Companions.LACERTA_DEF ? LACERTA_SPOT : SHIRAUME_SPOT);
        }
    }

    static @Nullable CompanionEntity lacerta(ServerPlayer p) {
        return Companions.out(p, Companions.LACERTA_DEF);
    }

    static @Nullable UltimateCast lacertaCast(ServerPlayer p) {
        CompanionEntity c = lacerta(p);
        return c == null ? null : Ultimates.cast(c);
    }

    /** 靶子周围几只会被定住、被拉、被挑飞的尸壳（血不厚，路上的轻伤打不死）。 */
    static void spawnExtras(ServerPlayer p) {
        removeExtras();
        double[][] at = {{-3.5, 4}, {3.8, 6}, {-1.5, 8.5}, {4.5, 2.5}};
        for (double[] xz : at) {
            Husk h = EntityType.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
            if (h == null) continue;
            h.setPersistenceRequired();
            h.setSilent(true);
            var hp = h.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(120);
            h.setHealth(120);
            h.snapTo(xz[0] + .5, FLOOR + 1, xz[1], 180, 0);
            p.level().addFreshEntity(h);
            extras.add(h);
        }
    }

    static void removeExtras() {
        extras.forEach(Mob::discard);
        extras.clear();
    }

    static ScenarioBuilder lacertaTeardown(ScenarioBuilder s, boolean ceiling) {
        s.teardown("清掉尸壳和天花板", ctx -> ctx.server().execute(() -> {
            removeExtras();
            if (ceiling) roof(ctx.server().overworld(), false);
        }));
        return ultimateTeardown(s);
    }

    /** 台子上方的天花板（铺满整个台子）。 */
    static void roof(ServerLevel level, boolean on) {
        for (int x = -14; x <= 15; x++)
            for (int z = -14; z <= 15; z++) level.setBlock(new BlockPos(x, CEILING, z), (on ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
    }

    @LDLRegisterClient(name = "lacerta_ultimate_film", group = "lacerta_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Film extends LacertaUltimateViews {
        private float health0;
        private long passes;
        private int rocks0;
        private boolean full;

        @Override
        public void define(ScenarioBuilder s) {
            filmStage(s).server("拉塞塔一个人、一个靶子", ctx -> {
                        callOnly(ctx.player(), Companions.LACERTA_DEF);
                        spawnFoe(ctx.player());
                    })
                    .waitUntilServer("站好了", ctx -> arrived(ctx.player(), 1)).ticks(10)
                    .step("记下叠层和碎岩", ctx -> {
                        passes = UltimateFilmRenderer.passes();
                        rocks0 = LacertaUltimateFx.rocksSpawned();
                    })
                    .server("周围几只尸壳，下令", ctx -> {
                        spawnExtras(ctx.player());
                        health0 = foeHealth();
                        Ultimates.order(ctx.player(), "");
                        UltimateCast cast = lacertaCast(ctx.player());
                        full = cast != null && !cast.condensed() && cast.script() instanceof LacertaUltimate u && !u.beats().condensed();
                    })
                    .checkServer("单人奥义，演的是她的完整版", ctx -> full && Ultimates.phase(ctx.player()) == UltimateChain.Phase.SINGLE)
                    .waitUntil("过场开始", ctx -> UltimateFilm.playing() != null);
            film(s, "lacerta_ult_full", 124, 3)
                    .check("叠层画了，碎岩崩了四五十块", ctx -> {
                        int rocks = LacertaUltimateFx.rocksSpawned() - rocks0;
                        return UltimateFilmRenderer.passes() > passes && rocks >= 40 && rocks <= 60;
                    })
                    .waitUntilServer("演完", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE)
                    .checkServer("打中了靶子", ctx -> foeHealth() < health0)
                    .checkServer("露天跳满高度、一刻都没卡进方块", ctx -> LacertaUltimate.lastLift() >= 6.9 && LacertaUltimate.stuckTicks() == 0)
                    .waitUntil("镜头回来了", ctx -> UltimateFilm.playing() == null && !CameraDirector.active())
                    .screenshot("lacerta_ult_after");
            lacertaTeardown(s, false);
        }
    }

    @LDLRegisterClient(name = "lacerta_ultimate_indoor", group = "lacerta_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Indoor extends LacertaUltimateViews {
        private float health0;

        @Override
        public void define(ScenarioBuilder s) {
            filmStage(s).server("头顶 4 格就是天花板；拉塞塔一个人、一个靶子", ctx -> {
                        roof(ctx.server().overworld(), true);
                        callOnly(ctx.player(), Companions.LACERTA_DEF);
                        spawnFoe(ctx.player());
                    })
                    .waitUntilServer("站好了", ctx -> arrived(ctx.player(), 1)).ticks(10)
                    .server("下令", ctx -> {
                        health0 = foeHealth();
                        Ultimates.order(ctx.player(), "");
                    })
                    .waitUntilServer("跳到顶了", ctx -> {
                        UltimateCast cast = lacertaCast(ctx.player());
                        return cast != null && cast.age() >= LacertaUltimate.Beats.FULL.apex() + 1;
                    })
                    .screenshot("lacerta_ult_indoor_apex")
                    .waitUntilServer("坠下、裂地", ctx -> {
                        UltimateCast cast = lacertaCast(ctx.player());
                        return cast != null && cast.age() >= LacertaUltimate.Beats.FULL.impact() + 6;
                    })
                    .screenshot("lacerta_ult_indoor_impact")
                    .waitUntilServer("演完", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE)
                    .checkServer("压低了跳的高度，头顶没顶进天花板，一刻都没卡进方块", ctx -> LacertaUltimate.lastLift() > 0
                            && LacertaUltimate.lastLift() < 3 && LacertaUltimate.peakHead() <= CEILING + 1e-3 && LacertaUltimate.stuckTicks() == 0)
                    .checkServer("照样打中了靶子", ctx -> foeHealth() < health0)
                    .waitUntil("镜头回来了", ctx -> UltimateFilm.playing() == null && !CameraDirector.active());
            lacertaTeardown(s, true);
        }
    }

    @LDLRegisterClient(name = "lacerta_ultimate_relay", group = "lacerta_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Relay extends LacertaUltimateViews {
        private float health0;

        @Override
        public void define(ScenarioBuilder s) {
            filmStage(s).server("白梅和拉塞塔、一个靶子", ctx -> {
                        callOnly(ctx.player(), Companions.SHIRAUME_DEF, Companions.LACERTA_DEF);
                        spawnFoe(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .server("白梅先放，拉塞塔排在后面", ctx -> {
                        Ultimates.order(ctx.player(), "shiraume");
                        Ultimates.order(ctx.player(), "lacerta");
                    })
                    .waitUntilServer("轮到拉塞塔：第二段、缩短版", ctx -> {
                        UltimateCast cast = lacertaCast(ctx.player());
                        if (cast == null) return false;
                        health0 = foeHealth();
                        return cast.condensed() && cast.stage().index() == 1 && cast.script() instanceof LacertaUltimate u && u.beats().condensed()
                                && cast.script().length() <= 44;
                    })
                    .waitUntil("本机放她的过场", ctx -> {
                        UltimateFilm f = UltimateFilm.playing();
                        return f != null && "lacerta".equals(f.cast().key());
                    });
            film(s, "lacerta_ult_short", 52, 3)
                    .waitUntilServer("她演完", ctx -> lacertaCast(ctx.player()) == null)
                    .checkServer("缩短版照样打中了靶子", ctx -> foeHealth() < health0);
            lacertaTeardown(s, false);
        }
    }

    @LDLRegisterClient(name = "lacerta_ultimate_gift", group = "lacerta_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Gift extends LacertaUltimateViews {
        private int pierced0, pierced1;

        @Override
        public void define(ScenarioBuilder s) {
            filmStage(s).server("拉塞塔和白梅、一个靶子", ctx -> {
                        callOnly(ctx.player(), Companions.LACERTA_DEF, Companions.SHIRAUME_DEF);
                        spawnFoe(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .server("拉塞塔先放，白梅排在后面", ctx -> {
                        pierced0 = LacertaUltimate.Sunder.pierced();
                        Ultimates.order(ctx.player(), "lacerta");
                        Ultimates.order(ctx.player(), "shiraume");
                    })
                    .waitUntilServer("裂地打中了，靶子缠着龙炎", ctx -> foe != null && Dragonfire.burning(foe) && Ultimates.phase(ctx.player()) == UltimateChain.Phase.WINDOW)
                    .waitUntilServer("白梅接上并打中：吃到碎甲（穿甲、再缠一层龙炎）", ctx -> LacertaUltimate.Sunder.pierced() > pierced0)
                    .server("记下", ctx -> pierced1 = LacertaUltimate.Sunder.pierced())
                    .waitUntilServer("凑齐，进汇流", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.CONVERGED)
                    // 按奥义键放最后一击由 ultimate_chain 测；这里只看碎甲管不管得到最后一击，直接在服务端下令
                    .server("放最后一击", ctx -> Ultimates.order(ctx.player(), ""))
                    .waitUntilServer("最后一击也吃到碎甲", ctx -> LacertaUltimate.Sunder.pierced() > pierced1)
                    .waitUntilServer("回到常态", ctx -> Ultimates.phase(ctx.player()) == UltimateChain.Phase.IDLE);
            lacertaTeardown(s, false);
        }
    }
}
