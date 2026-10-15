package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import dev.logos.dimension.OpusPhase;
import net.minecraft.core.registries.Registries;

@LDLRegisterClient(name="arcane_transformation",group="arcane_animation",registry=com.lowdragmc.lowdraglib2.uitest.UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public class ArcaneArrayView extends AlchemicalViews.Sky {
    @Override protected int settleTicks(){return 100;}
    @Override protected void extra(ScenarioBuilder steps) {
        steps.runCommand("alchemy phase nigredo")
                .server("synchronize transformation capture",ctx->{var level=ctx.player().level();level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(OpusPhase.CLOCK),0);})
                .ticks(10);
        for(int frame=0;frame<32;frame++) steps.ticks(12).screenshot(String.format("transformation_%02d",frame));
        steps.check("array choreography progressed",ctx->ArcaneArrayMotion.sample(180).fracture()>.95);
    }
}
