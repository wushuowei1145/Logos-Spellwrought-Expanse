package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.cloudsea.Omikuji;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 下界没有时钟，抽签跟主世界的日子走，而不是永远停在第 0 天。放在客户端场景里，因为世界测试的服务器不加载下界。 */
@LDLRegisterClient(name = "omikuji_clockless", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class OmikujiViews implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("fox_dev").defaultTimeoutMs(120000).scenarioTimeoutMs(180000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
            .server("draw in the nether, then let an overworld day pass", ctx -> {
                var nether = ctx.server().getLevel(Level.NETHER);
                ctx.check("the nether has no clock", nether.dimensionType().defaultClock().isEmpty());
                DimensionTravel.teleport(ctx.player(), nether, new Vec3(0.5, 120, 0.5), 0, 0);
                ctx.check("not drawn yet", !Omikuji.drawnToday(ctx.player()));
                Omikuji.draw(ctx.player(), nether.getRandom());
                ctx.check("drawn today", Omikuji.drawnToday(ctx.player()));
                var overworld = nether.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
                ctx.server().clockManager().addTicks(overworld, 24000);
                ctx.check("an overworld day later the nether has a new day too", !Omikuji.drawnToday(ctx.player()));
            })
            .teardownServer("back to the overworld", ctx ->
                DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(0.5, 200, 0.5), 0, 0));
    }
}
