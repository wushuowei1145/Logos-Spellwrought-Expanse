package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.companion.ShiraumeUltimateScreen;
import dev.logos.dimension.client.companion.ShiraumeUltimateShow;
import dev.logos.dimension.client.ultimate.UltimateClient;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.ShiraumeUltimate;
import dev.logos.dimension.ultimate.UltimateCast;
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
 * 实际客户端检查白梅的奥义「狐嫁入 · 水镜形代断」（docs/SHIRAUME_COMPANION.md 第 11 节），一组 {@code shiraume_ultimate}。
 * 同 {@link UltimateViews} 的白瓷台：靶子在台子后面，旁边再站两只不动的尸壳（被纸形代贴住、牵上因果丝、吃溅射）。
 * <ul>
 * <li>{@code shiraume_ultimate_full}：只有她一位，单人放完整版，打中靶子、溅射到旁边两只；放慢到每秒 2 刻，从下令起每刻截一张
 *     （{@code shiraume_ult_full_NNN}），拿 tools/frames_to_gif.py 拼成动图看节奏、运镜和后期；全屏后期画了、破镜存下了镜中画面，
 *     放完镜头回到玩家。</li>
 * <li>{@code shiraume_ultimate_condensed}：她和精灵公主，公主先放、她排在后面，是连携第二段：演缩短版（44 刻），同样逐刻截帧
 *     （{@code shiraume_ult_short_NNN}）。</li>
 * <li>{@code shiraume_ultimate_relay}：她先放，出手时把「狐火蚀骨」交出去（靶子身上转着残樱狐印），接着公主放：
 *     打中带印的靶子时伤害 ×1.25、二次引爆，印散了。</li>
 * </ul>
 */
public abstract class ShiraumeUltimateViews extends UltimateViews {
    /** 靶子两边各一只，都在溅射范围里。 */
    static final List<Vec3> SIDES = List.of(new Vec3(-1.4, FLOOR + 1, 5.6), new Vec3(2.3, FLOOR + 1, 5.4));
    static final List<Husk> sides = new ArrayList<>();
    /** 截帧时游戏放慢到每秒几刻：截一张图要三百多毫秒，每秒 5 刻会两刻才截到一张，拼出来的动图节奏就不对了。 */
    static final int RATE = 2;
    static float[] sideHealth = new float[0];

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("shiraume_ultimate").defaultTimeoutMs(90_000).scenarioTimeoutMs(300_000);
    }

    static void spawnSides(ServerPlayer p) {
        removeSides();
        for (Vec3 at : SIDES) {
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
            sides.add(h);
        }
        sideHealth = new float[sides.size()];
        for (int i = 0; i < sides.size(); i++) sideHealth[i] = sides.get(i).getHealth();
    }

    static void removeSides() {
        for (Husk h : sides) h.discard();
        sides.clear();
    }

    /** 旁边的每一只都掉血了（吃到溅射）。 */
    static boolean sidesHurt() {
        if (sides.isEmpty()) return false;
        for (int i = 0; i < sides.size(); i++) if (sides.get(i).getHealth() >= sideHealth[i]) return false;
        return true;
    }

    static @Nullable UltimateCast castOf(ServerPlayer p, int index) {
        CompanionEntity c = out(p, index);
        return c == null ? null : Ultimates.cast(c);
    }

    static ScenarioBuilder shiraumeTeardown(ScenarioBuilder s) {
        s.teardown("清掉两边的靶子", ctx -> ctx.server().execute(ShiraumeUltimateViews::removeSides));
        return ultimateTeardown(s);
    }

    @LDLRegisterClient(name = "shiraume_ultimate_full", group = "shiraume_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Full extends ShiraumeUltimateViews {
        private float health0;
        private long passes, holds, frames;

        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("白梅一位、三只靶子", ctx -> {
                        call(ctx.player(), 1);
                        spawnFoe(ctx.player());
                        spawnSides(ctx.player());
                    })
                    .waitUntilServer("站好了", ctx -> arrived(ctx.player(), 1)).ticks(10)
                    .step("记下后期画了几次", ctx -> {
                        passes = ShiraumeUltimateScreen.passes();
                        holds = ShiraumeUltimateScreen.holds();
                        frames = ShiraumeUltimateShow.frames();
                    })
                    .server("下令", ctx -> {
                        health0 = foeHealth();
                        Ultimates.order(ctx.player(), "");
                    })
                    .checkServer("单人，演完整版：107 刻、第 78 刻出手", ctx -> {
                        UltimateCast cast = castOf(ctx.player(), 0);
                        return cast != null && !cast.condensed() && cast.script().length() == ShiraumeUltimate.FULL.length()
                                && cast.script().impact() == ShiraumeUltimate.FULL.impact();
                    })
                    .waitUntil("过场开始", ctx -> UltimateFilm.playing() != null);
            film(s, "shiraume_ult_full", ShiraumeUltimate.FULL.length() + 3, RATE)
                    .check("她的全屏后期画了，破镜存下了镜中画面", ctx -> ShiraumeUltimateScreen.passes() > passes && ShiraumeUltimateScreen.holds() > holds
                            && ShiraumeUltimateShow.frames() > frames)
                    .waitUntilServer("打中靶子，两边的也吃到溅射", ctx -> foeHealth() < health0 && sidesHurt())
                    .waitUntil("放完，镜头回来了", ctx -> UltimateFilm.playing() == null && !CameraDirector.active() && UltimateClient.showing() == 0)
                    .checkServer("单人奥义不交馈赠：靶子身上没有残樱狐印", ctx -> foe != null && !ShiraumeUltimate.marked(foe));
            shiraumeTeardown(s);
        }
    }

    @LDLRegisterClient(name = "shiraume_ultimate_condensed", group = "shiraume_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Condensed extends ShiraumeUltimateViews {
        private float health0;

        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("白梅和精灵公主、三只靶子", ctx -> {
                        call(ctx.player(), 2);
                        spawnFoe(ctx.player());
                        spawnSides(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .server("公主先放，白梅排在后面", ctx -> {
                        Ultimates.order(ctx.player(), "elf_princess");
                        Ultimates.order(ctx.player(), "shiraume");
                    })
                    .checkServer("白梅排上了", ctx -> Ultimates.queued(ctx.player()).equals(List.of("shiraume")))
                    .waitUntilServer("轮到白梅：连携第二段，演缩短版（44 刻、第 24 刻出手）", ctx -> {
                        UltimateCast cast = castOf(ctx.player(), 0);
                        if (cast == null) return false;
                        health0 = foeHealth();
                        return cast.condensed() && cast.script().length() == ShiraumeUltimate.CONDENSED.length()
                                && cast.script().impact() == ShiraumeUltimate.CONDENSED.impact();
                    })
                    .waitUntil("她的过场开始", ctx -> UltimateFilm.playing() != null && UltimateClient.show(clientId(0)) instanceof ShiraumeUltimateShow);
            film(s, "shiraume_ult_short", ShiraumeUltimate.CONDENSED.length() + 3, RATE)
                    .waitUntilServer("打中靶子", ctx -> foeHealth() < health0);
            shiraumeTeardown(s);
        }
    }

    @LDLRegisterClient(name = "shiraume_ultimate_relay", group = "shiraume_ultimate", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Relay extends ShiraumeUltimateViews {
        private int corroded;

        @Override
        public void define(ScenarioBuilder s) {
            ultimateStage(s).server("白梅和精灵公主、一个靶子", ctx -> {
                        call(ctx.player(), 2);
                        spawnFoe(ctx.player());
                    })
                    .waitUntilServer("都站好了", ctx -> arrived(ctx.player(), 2)).ticks(10)
                    .server("白梅先放", ctx -> {
                        corroded = ShiraumeUltimate.corroded();
                        Ultimates.order(ctx.player(), "shiraume");
                    })
                    .waitUntilServer("出手：靶子身上挂上残樱狐印", ctx -> foe != null && ShiraumeUltimate.marked(foe))
                    .screenshot("shiraume_ult_marked")
                    .waitUntilServer("白梅演完", ctx -> !performing(ctx.player(), 0))
                    .server("接精灵公主", ctx -> Ultimates.order(ctx.player(), "elf_princess"))
                    .waitUntilServer("公主打中带印的靶子：加伤、二次引爆，印散了", ctx -> ShiraumeUltimate.corroded() > corroded
                            && foe != null && !ShiraumeUltimate.marked(foe))
                    .screenshot("shiraume_ult_corroded");
            shiraumeTeardown(s);
        }
    }

    /** 第 {@code index} 位同伴在客户端的实体 ID（服务端和客户端同一个）。 */
    static int clientId(int index) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return -1;
        ServerPlayer p = server.getPlayerList().getPlayer(mc.player.getUUID());
        CompanionEntity c = p == null ? null : out(p, index);
        return c == null ? -1 : c.getId();
    }
}
