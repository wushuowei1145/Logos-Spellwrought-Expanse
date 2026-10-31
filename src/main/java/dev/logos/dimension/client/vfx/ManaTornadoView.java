package dev.logos.dimension.client.vfx;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;

import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.vfx.ManaTornado;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 魔力龙卷：卷在一个站着不动的村民（施法者）身上，从 16 格外平视，按卷起、成形、转色、散开各截一张。
 * 每个阶段都经服务端重新卷一次再采样。转色那张用 12 秒一圈的转色，等半圈。
 */
@LDLRegisterClient(name = "mana_tornado", group = "mana_tornado", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ManaTornadoView implements UIScenario {
    static final Vec3 AT = new Vec3(.5, 100, .5);
    static int caster = -1;
    private boolean previousHideGui;

    @Override public void configure(ScenarioOptions o) { o.tags("mana_tornado").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> previousHideGui = PillarBurstView.viewport())
                .server("铺混合地面", ctx -> PillarBurstView.arena(ctx.server().overworld()))
                .server("放一个施法者", ctx -> caster = summonCaster(ctx.server().overworld()))
                .server("远处平视", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(.5, 100, 16.5), 180, -14))
                .ticks(20);
        stage(s, "tornado_grow", 6, 0);
        stage(s, "tornado_full", 40, 0);
        stage(s, "tornado_hue", 120, ManaTornado.CYCLE);
        s.server("收掉", ctx -> ManaTornado.stop(ctx.server().overworld(), casterEntity(ctx.server().overworld())))
                .ticks(12).screenshot("tornado_fade")
                .server("收走施法者", ctx -> dismiss(ctx.server().overworld()));
        s.teardown("恢复视口", ctx -> restore(previousHideGui));
    }

    /** 清掉正在卷的，经服务端在施法者身上重新卷一次（一直卷），等 {@code ticks} 刻后截图。 */
    static void stage(ScenarioBuilder s, String name, int ticks, int cycle) {
        s.step("清空 " + name, ctx -> ManaTornados.clear())
                .server("卷起 " + name, ctx -> ManaTornado.play(ctx.server().overworld(), casterEntity(ctx.server().overworld()), -1, 1, cycle))
                .waitUntil("客户端收到龙卷", ctx -> ManaTornados.count() > 0)
                .ticks(ticks).screenshot(name);
    }

    /** 站在 {@link #AT} 不动、不出声的村民。 */
    static int summonCaster(ServerLevel level) {
        Entity old = caster >= 0 ? level.getEntity(caster) : null;
        if (old != null) old.discard();
        var villager = EntityType.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        villager.setNoAi(true);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.snapTo(AT.x, AT.y, AT.z, 0, 0);
        villager.setYHeadRot(0);
        level.addFreshEntity(villager);
        return villager.getId();
    }

    static Entity casterEntity(ServerLevel level) {
        Entity e = level.getEntity(caster);
        return e != null ? e : level.getEntity(summonCaster(level));
    }

    /** 收走施法者村民，免得下一个场景把玩家传送进它身体里。 */
    static void dismiss(ServerLevel level) {
        Entity old = caster >= 0 ? level.getEntity(caster) : null;
        if (old != null) old.discard();
        caster = -1;
    }

    static void restore(boolean hideGui) {
        ManaTornados.clear();
        Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
        Minecraft.getInstance().options.hideGui = hideGui;
    }

    /** 施法者自己的视角：第三人称背后、第一人称平视与仰视；再加夜里远看。 */
    @LDLRegisterClient(name = "mana_tornado_caster", group = "mana_tornado", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Caster implements UIScenario {
        private boolean previousHideGui;

        @Override public void configure(ScenarioOptions o) { o.tags("mana_tornado").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

        @Override public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                    .step("设置视口", ctx -> previousHideGui = PillarBurstView.viewport())
                    .server("铺混合地面", ctx -> PillarBurstView.arena(ctx.server().overworld()))
                    .server("收走施法者", ctx -> dismiss(ctx.server().overworld()))
                    .server("站到中间", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), AT, 180, 12))
                    .step("第三人称背后", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                    .ticks(20);
            self(s, "tornado_caster_back", 40);
            s.step("第一人称", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                    .server("平视", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), AT, 180, -5))
                    .ticks(5);
            self(s, "tornado_caster_eye", 40);
            s.server("仰视", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), AT, 180, -70)).ticks(5);
            self(s, "tornado_caster_up", 40);
            s.runCommand("time set night")
                    .server("放一个施法者", ctx -> caster = summonCaster(ctx.server().overworld()))
                    .server("夜里远看", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(.5, 100, 16.5), 180, -14))
                    .ticks(10);
            stage(s, "tornado_night", 40, 0);
            s.teardown("恢复视口", ctx -> restore(previousHideGui));
        }

        /** 卷在玩家自己身上。 */
        static void self(ScenarioBuilder s, String name, int ticks) {
            s.step("清空 " + name, ctx -> ManaTornados.clear())
                    .server("卷起 " + name, ctx -> ManaTornado.play(ctx.server().overworld(), ctx.player(), -1, 1, 0))
                    .waitUntil("客户端收到龙卷", ctx -> ManaTornados.count() > 0)
                    .ticks(ticks).screenshot(name);
        }
    }
}
