package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;

@LDLRegisterClient(name="glass_array",group="glass_array",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public class GlassArrayView extends AlchemicalViews.Sky {
    private int oldFov;
    @Override protected float pitch(){return -88;}
    @Override protected int settleTicks(){return 80;}
    @Override protected void extra(ScenarioBuilder s){
        s.step("frame the complete glass array",ctx->{var mc=Minecraft.getInstance();oldFov=mc.options.fov().get();mc.options.fov().set(95);})
            .runCommand("alchemy phase nigredo")
            .server("start the activation",ctx->{var level=ctx.player().level();level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(OpusPhase.CLOCK),0);});
        for(int frame=0;frame<16;frame++)s.ticks(20).screenshot(String.format("glass_spell_%02d",frame));
        s.check("spell clock advanced in game",ctx->OpusPhase.ticks(Minecraft.getInstance().level)>=300)
            .teardown("restore field of view",ctx->Minecraft.getInstance().options.fov().set(oldFov));
    }
}
