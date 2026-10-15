package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Real client gravity must carry a player across the liquid surface, rather than calling the portal directly. */
@LDLRegisterClient(name="crucible_cauldron", group="logos_dimension", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class CauldronView implements UIScenario {
    private BlockPos pot;
    @Override public void configure(ScenarioOptions o) { o.tags("logos_dimension", "portal").scenarioTimeoutMs(90_000).defaultTimeoutMs(40_000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
                .server("place cauldron on a real platform", ctx -> {
                    var p = ctx.player(); var level = ctx.server().overworld();
                    pot = new BlockPos(0, 101, 0);
                    for (int x=-4; x<=4; x++) for (int z=-4; z<=4; z++) {
                        level.setBlockAndUpdate(pot.offset(x,-1,z), DimensionBlocks.STRATA_STONE.get().defaultBlockState());
                        for (int y=0; y<6; y++) level.removeBlock(pot.offset(x,y,z), false);
                    }
                    level.setBlockAndUpdate(pot, DimensionBlocks.ALCHEMICAL_CAULDRON.get().defaultBlockState());
                    DimensionTravel.teleport(p, level, new Vec3(2.8,103.1,4.5),150,30);
                    p.getAbilities().flying=true; p.onUpdateAbilities();
                }).step("hide HUD", ctx -> Minecraft.getInstance().options.hideGui=true)
                .waitUntil("client receives the cauldron", ctx -> Minecraft.getInstance().level.getBlockState(pot).is(DimensionBlocks.ALCHEMICAL_CAULDRON.get()))
                .ticks(30).screenshot("alchemical_cauldron")
                .server("stand on the solid rim", ctx -> {
                    var p=ctx.player(); p.setGameMode(GameType.SURVIVAL);
                    DimensionTravel.teleport(p,p.level(),new Vec3(-.15,101+dev.logos.dimension.block.AlchemicalCauldronBlock.RIM_HEIGHT+.002,.5),0,0);
                }).ticks(25)
                .checkServer("rim does not trigger portal", ctx -> !ctx.player().level().dimension().equals(LogosDimension.CRUCIBLE))
                .server("drop through the open mouth", ctx -> DimensionTravel.teleport(ctx.player(),ctx.player().level(),new Vec3(.5,103,.5),0,0))
                .waitUntil("jump enters the dimension",ctx -> Minecraft.getInstance().level.dimension().equals(LogosDimension.CRUCIBLE))
                .checkServer("server reached Crucible and cooldown is active",ctx -> ctx.player().level().dimension().equals(LogosDimension.CRUCIBLE) && ctx.player().isOnPortalCooldown())
                .ticks(20).screenshot("cauldron_arrival")
                .teardown("restore HUD",ctx -> Minecraft.getInstance().options.hideGui=false);
    }
}
